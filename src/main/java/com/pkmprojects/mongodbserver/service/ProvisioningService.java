package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.error.DatabaseAlreadyExistsException;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.repository.ManagedDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.security.PasswordGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Orchestrates the Atlas-style provisioning lifecycle:
 * create a database with a dedicated db-scoped user, rotate its password,
 * and delete the database together with its user.
 *
 * <p>The database user's password is persisted in provisioning metadata so the
 * connection string can be reconstructed and shown on the database detail page
 * at any time. Every lifecycle action is recorded in the {@code admin_activity}
 * audit trail.</p>
 *
 * <p><strong>Concurrency contract:</strong> all lifecycle operations for the
 * same database name are serialized per name (see {@link #databaseLocks}).
 * Without this, concurrent {@link #provision(CreateDatabaseForm)} and
 * {@link #delete(String)} calls interleave into inconsistent states (orphaned
 * metadata, a database whose user was already dropped) and concurrent
 * {@code createUser} commands against a brand-new database can lose the user
 * insert entirely while still reporting success - MongoDB does not serialize
 * user creation on a not-yet-existing database. Different database names are
 * never blocked by each other.</p>
 */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    private static final int GENERATED_PASSWORD_LENGTH = 16;
    private static final int MONGO_CODE_USER_NOT_FOUND = 11;
    private static final int MONGO_CODE_NAMESPACE_NOT_FOUND = 26;
    private static final int MONGO_CODE_USER_ALREADY_EXISTS = 51003;

    /**
     * Per-database-name lock registry. The lifecycle operations are check-then-act
     * sequences spanning multiple MongoDB commands, so two concurrent calls for
     * the same name must not interleave (see the class Javadoc for the failure
     * modes this prevents). Entries are intentionally never removed: the set of
     * distinct names is bounded by the databases an admin manages, and removing
     * a lock after use reintroduces a check-then-act race on the removal itself.
     */
    private final ConcurrentHashMap<String, Object> databaseLocks = new ConcurrentHashMap<>();

    private final MongoDatabaseRepository mongoDatabaseRepository;
    private final ManagedDatabaseRepository managedDatabaseRepository;
    private final AuditLogRepository auditLogRepository;
    private final MongoNameValidator nameValidator;
    private final PasswordGenerator passwordGenerator;
    private final Clock clock;
    private final Environment environment;

    public ProvisioningService(MongoDatabaseRepository mongoDatabaseRepository,
                               ManagedDatabaseRepository managedDatabaseRepository,
                               AuditLogRepository auditLogRepository,
                               MongoNameValidator nameValidator,
                               PasswordGenerator passwordGenerator,
                               Clock clock,
                               Environment environment) {
        this.mongoDatabaseRepository = mongoDatabaseRepository;
        this.managedDatabaseRepository = managedDatabaseRepository;
        this.auditLogRepository = auditLogRepository;
        this.nameValidator = nameValidator;
        this.passwordGenerator = passwordGenerator;
        this.clock = clock;
        this.environment = environment;
    }

    /**
     * Percent-encodes a URI userinfo component (unreserved characters kept as-is).
     */
    private static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '.' || b == '_' || b == '~') {
                encoded.append((char) b);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    /**
     * Creates a database and a Mongo user with readWrite rights scoped to it.
     * The returned {@link DatabaseInfo} carries the connection string (with password)
     * for the "show once" flash message.
     */
    public DatabaseInfo provision(CreateDatabaseForm form) {
        String dbName = form.dbName().trim();
        String userName = form.userName().trim();
        String requestedPassword = form.password() == null ? "" : form.password().trim();
        nameValidator.validateDatabaseName(dbName);
        nameValidator.validateUserName(userName);
        nameValidator.validatePassword(requestedPassword);

        return withDatabaseLock(dbName, () -> {
            if (managedDatabaseRepository.existsByDbName(dbName) || mongoDatabaseRepository.databaseExists(dbName)) {
                throw new DatabaseAlreadyExistsException("Database '" + dbName + "' already exists");
            }

            String password = requestedPassword.isBlank()
                    ? passwordGenerator.generate(GENERATED_PASSWORD_LENGTH)
                    : requestedPassword;

            try {
                mongoDatabaseRepository.createUser(dbName, userName, password);
                mongoDatabaseRepository.createDatabase(dbName);
            } catch (MongoException e) {
                if (e instanceof MongoCommandException commandException
                        && commandException.getErrorCode() == MONGO_CODE_USER_ALREADY_EXISTS) {
                    // lost a concurrent provision race - the duplicate user already exists
                    throw new DatabaseAlreadyExistsException("Database user '" + userName + "' already exists");
                }
                // Best-effort cleanup of a partially created user so a retry is not blocked.
                // Widened to MongoException: a timeout/connection failure after the user was
                // created would otherwise leak an orphaned user.
                try {
                    mongoDatabaseRepository.dropUser(dbName, userName);
                } catch (MongoException cleanupFailure) {
                    log.warn("Could not clean up partially created user '{}' after failed provisioning", userName,
                            cleanupFailure);
                }
                log.error("Failed to provision database '{}' (user '{}')", dbName, userName, e);
                throw new ProvisioningException("Could not provision database '" + dbName + "'", e);
            }

            Instant now = clock.instant();
            ManagedDatabase metadata = new ManagedDatabase(dbName, userName, List.of("readWrite:" + dbName), now, now, null);
            metadata.setStoredPassword(password);
            managedDatabaseRepository.save(metadata);
            audit(AuditEvent.PROVISION, dbName, userName, now);
            log.info("Provisioned database '{}' with user '{}'", dbName, userName);

            return toInfo(dbName, metadata, collectionCount(dbName), null)
                    .withConnectionString(buildConnectionString(userName, password, dbName));
        });
    }

    /**
     * Rotates the provisioned user's password. Returns the new connection string
     * (shown once).
     */
    public DatabaseInfo resetPassword(String dbName, ResetPasswordForm form) {
        nameValidator.validateDatabaseName(dbName);
        String requestedPassword = form.password() == null ? "" : form.password().trim();
        nameValidator.validatePassword(requestedPassword);

        return withDatabaseLock(dbName, () -> {
            ManagedDatabase metadata = managedDatabaseRepository.findByDbName(dbName)
                    .orElseThrow(() -> new DatabaseNotFoundException("Database '" + dbName + "' is not provisioned"));

            String password = requestedPassword.isBlank()
                    ? passwordGenerator.generate(GENERATED_PASSWORD_LENGTH)
                    : requestedPassword;

            try {
                mongoDatabaseRepository.updateUserPassword(dbName, metadata.getUserName(), password);
            } catch (MongoCommandException e) {
                log.error("Failed to reset password for user '{}' on database '{}'", metadata.getUserName(), dbName, e);
                throw new ProvisioningException("Could not reset password for database '" + dbName + "'", e);
            }

            metadata.setStoredPassword(password);
            metadata.setLastPasswordResetAt(clock.instant());
            managedDatabaseRepository.save(metadata);
            audit(AuditEvent.RESET_PASSWORD, dbName, metadata.getUserName(), metadata.getLastPasswordResetAt());
            log.info("Reset password for user '{}' on database '{}'", metadata.getUserName(), dbName);

            return toInfo(dbName, metadata, collectionCount(dbName), null)
                    .withConnectionString(buildConnectionString(metadata.getUserName(), password, dbName));
        });
    }

    /**
     * Drops the database and (if provisioned) its user and metadata. Tolerates a
     * database whose namespace or user is already gone (e.g. an earlier partial
     * failure), so delete is always retryable.
     */
    public void delete(String dbName) {
        nameValidator.validateDatabaseName(dbName);
        withDatabaseLock(dbName, () -> {
            Optional<ManagedDatabase> metadata = managedDatabaseRepository.findByDbName(dbName);

            try {
                mongoDatabaseRepository.dropDatabase(dbName);
            } catch (MongoCommandException e) {
                if (!isMongoCode(e, MONGO_CODE_NAMESPACE_NOT_FOUND)) {
                    log.error("Failed to drop database '{}'", dbName, e);
                    throw new ProvisioningException("Could not drop database '" + dbName + "'", e);
                }
            }

            metadata.ifPresent(m -> {
                try {
                    mongoDatabaseRepository.dropUser(dbName, m.getUserName());
                } catch (MongoCommandException e) {
                    if (!isMongoCode(e, MONGO_CODE_USER_NOT_FOUND)) {
                        log.error("Failed to drop user '{}' for database '{}'", m.getUserName(), dbName, e);
                        throw new ProvisioningException("Could not drop user for database '" + dbName + "'", e);
                    }
                }
            });

            metadata.ifPresent(m -> managedDatabaseRepository.deleteByDbName(dbName));
            audit(AuditEvent.DELETE, dbName, metadata.map(ManagedDatabase::getUserName).orElse(null), clock.instant());
            log.info("Deleted database '{}'", dbName);
        });
    }

    /**
     * Creates a collection inside an existing database. The database must already
     * exist - MongoDB would otherwise create it implicitly, which is not what an
     * admin expects when a typo sneaks into the database name.
     */
    public void createCollection(String dbName, String collectionName) {
        nameValidator.validateDatabaseName(dbName);
        nameValidator.validateCollectionName(collectionName);
        withDatabaseLock(dbName, () -> {
            requireDatabase(dbName);
            if (mongoDatabaseRepository.collectionExists(dbName, collectionName)) {
                throw new DatabaseAlreadyExistsException("Collection '" + collectionName + "' already exists");
            }
            try {
                mongoDatabaseRepository.createCollection(dbName, collectionName);
            } catch (MongoCommandException e) {
                log.error("Failed to create collection {}.{}", dbName, collectionName, e);
                throw new ProvisioningException("Could not create collection '" + collectionName + "'", e);
            }
        });
    }

    /**
     * Drops a collection inside an existing database. Throws
     * {@link DatabaseNotFoundException} when the collection (or database) is
     * already gone, so the action is retryable.
     */
    public void dropCollection(String dbName, String collectionName) {
        nameValidator.validateDatabaseName(dbName);
        nameValidator.validateCollectionName(collectionName);
        withDatabaseLock(dbName, () -> {
            requireDatabase(dbName);
            if (!mongoDatabaseRepository.collectionExists(dbName, collectionName)) {
                throw new DatabaseNotFoundException("Collection '" + collectionName + "' does not exist");
            }
            try {
                mongoDatabaseRepository.dropCollection(dbName, collectionName);
            } catch (MongoCommandException e) {
                if (isMongoCode(e, MONGO_CODE_NAMESPACE_NOT_FOUND)) {
                    throw new DatabaseNotFoundException("Collection '" + collectionName + "' does not exist");
                }
                log.error("Failed to drop collection {}.{}", dbName, collectionName, e);
                throw new ProvisioningException("Could not drop collection '" + collectionName + "'", e);
            }
        });
    }

    /**
     * All user-manageable databases (system and metadata DBs excluded), sorted by name.
     */
    public List<DatabaseInfo> listDatabases() {
        Map<String, ManagedDatabase> byName = managedDatabaseRepository.findAll().stream()
                .collect(Collectors.toMap(ManagedDatabase::getDbName, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        return mongoDatabaseRepository.listDatabaseNames().stream()
                .filter(dbName -> !MongoNameValidator.SYSTEM_DATABASES.contains(dbName.toLowerCase(Locale.ROOT)))
                .map(dbName -> toInfo(dbName, byName.get(dbName), collectionCount(dbName), null))
                .sorted(Comparator.comparing(DatabaseInfo::dbName))
                .toList();
    }

    /**
     * Returns the details of one database (whether provisioned or not).
     *
     * @throws DatabaseNotFoundException when the database does not exist on the server
     */
    public DatabaseInfo getDatabase(String dbName) {
        nameValidator.validateDatabaseName(dbName);
        if (!mongoDatabaseRepository.databaseExists(dbName)) {
            throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
        }
        Optional<ManagedDatabase> metadata = managedDatabaseRepository.findByDbName(dbName);
        ManagedDatabase md = metadata.orElse(null);
        // Rebuild the connection string from the stored password so the detail
        // page always shows it without relying on the one-time flash message.
        String connectionString = null;
        if (md != null && md.getStoredPassword() != null) {
            connectionString = buildConnectionString(md.getUserName(), md.getStoredPassword(), dbName);
        }
        return toInfo(dbName, md, collectionCount(dbName), connectionString);
    }

    /**
     * Host portion for connection strings: the explicit
     * {@code app.mongo-public-host} (e.g. {@code mongo.pkmprojects.online:9812})
     * when set, otherwise derived from the active {@code spring.mongodb.uri}
     * (e.g. Atlas cluster) or 127.0.0.1:9812.
     */
    String resolveConnectionHost() {
        String publicHost = environment.getProperty("app.mongo-public-host", "");
        if (publicHost != null && !publicHost.isBlank()) {
            return publicHost;
        }
        String uri = environment.getProperty("spring.mongodb.uri", "");
        if (uri.isBlank()) {
            return "127.0.0.1:9812";
        }
        int at = uri.lastIndexOf('@');
        if (at < 0) {
            return "127.0.0.1:9812";
        }
        String rest = uri.substring(at + 1);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private void requireDatabase(String dbName) {
        if (!mongoDatabaseRepository.databaseExists(dbName)) {
            throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
        }
    }

    /**
     * Runs {@code action} while holding the per-database lock for {@code dbName}.
     */
    private void withDatabaseLock(String dbName, Runnable action) {
        synchronized (databaseLocks.computeIfAbsent(dbName, key -> new Object())) {
            action.run();
        }
    }

    /**
     * Runs {@code action} while holding the per-database lock for {@code dbName}.
     */
    private <T> T withDatabaseLock(String dbName, Supplier<T> action) {
        synchronized (databaseLocks.computeIfAbsent(dbName, key -> new Object())) {
            return action.get();
        }
    }

    private void audit(String eventType, String dbName, String userName, Instant performedAt) {
        auditLogRepository.save(new AuditEvent(eventType, dbName, userName, currentUsername(), performedAt));
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null ? authentication.getName() : "unknown";
    }

    private String buildConnectionString(String userName, String password, String dbName) {
        // RFC 3986: '@', '/', '?', '#', '%' and friends inside credentials must be
        // percent-encoded or the generated URI is not dialable. Generated passwords
        // deliberately contain '@', '#', '%', so this is not a corner case.
        //
        // The provisioned user lives in <db>.system.users, so the URI path names the
        // database and the explicit authSource keeps authentication unambiguous for
        // every driver. Consumers connect directly to MongoDB with this string - the
        // app is only the credential-issuing control plane, never a data-plane proxy.
        return "mongodb://" + uriEncode(userName) + ":" + uriEncode(password) + "@" + resolveConnectionHost() + "/" + dbName
                + "?authSource=" + dbName;
    }

    private int collectionCount(String dbName) {
        try {
            return mongoDatabaseRepository.listCollectionNames(dbName).size();
        } catch (MongoCommandException e) {
            return 0;
        }
    }

    private DatabaseInfo toInfo(String dbName, ManagedDatabase metadata, Integer collectionsCount,
                                String connectionString) {
        if (metadata == null) {
            return new DatabaseInfo(dbName, null, List.of(), collectionsCount, null, null, null, false, connectionString);
        }
        return new DatabaseInfo(dbName, metadata.getUserName(), metadata.getRoles(),
                collectionsCount, metadata.getCreatedAt(), metadata.getUpdatedAt(),
                metadata.getLastPasswordResetAt(), true, connectionString);
    }

    private boolean isMongoCode(MongoCommandException e, int code) {
        return e.getErrorCode() == code;
    }
}
