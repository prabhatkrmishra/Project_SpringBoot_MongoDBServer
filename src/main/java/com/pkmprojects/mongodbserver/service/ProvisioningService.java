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
 * <p>Passwords are generated on demand, shown once to the admin, and never
 * persisted - only non-secret metadata lives in {@code mongodb_admin}. Every
 * lifecycle action is recorded in the {@code admin_activity} audit trail.</p>
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
    private final RestheartService restheartService;

    public ProvisioningService(MongoDatabaseRepository mongoDatabaseRepository,
                               ManagedDatabaseRepository managedDatabaseRepository,
                               AuditLogRepository auditLogRepository,
                               MongoNameValidator nameValidator,
                               PasswordGenerator passwordGenerator,
                               Clock clock,
                               Environment environment,
                               RestheartService restheartService) {
        this.mongoDatabaseRepository = mongoDatabaseRepository;
        this.managedDatabaseRepository = managedDatabaseRepository;
        this.auditLogRepository = auditLogRepository;
        this.nameValidator = nameValidator;
        this.passwordGenerator = passwordGenerator;
        this.clock = clock;
        this.environment = environment;
        this.restheartService = restheartService;
    }

    /**
     * Creates a database and a Mongo user with readWrite rights scoped to it.
     * The returned {@link DatabaseInfo} carries the RESTHeart env vars (with password)
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
                // Database first, then the scoped user: if user creation fails partway
                // through, dropping the just-created (still-empty) database fully
                // resets state for a clean retry. The previous order (user, then
                // database) left an orphaned database-less user on failure that
                // required a separate cleanup path and could still leave
                // mongoDatabaseRepository.databaseExists(dbName) == false while a
                // same-named Mongo user lingered, confusing later retries.
                mongoDatabaseRepository.createDatabase(dbName);
                mongoDatabaseRepository.createUser(dbName, userName, password);
            } catch (MongoException e) {
                if (e instanceof MongoCommandException commandException
                        && commandException.getErrorCode() == MONGO_CODE_USER_ALREADY_EXISTS) {
                    // lost a concurrent provision race - the duplicate user already exists
                    throw new DatabaseAlreadyExistsException("Database user '" + userName + "' already exists");
                }
                // Best-effort cleanup of the just-created (still-empty) database so a
                // retry is not blocked by it. Widened to MongoException: a
                // timeout/connection failure after the database was created would
                // otherwise leak an orphaned empty database.
                try {
                    mongoDatabaseRepository.dropDatabase(dbName);
                } catch (MongoException cleanupFailure) {
                    log.warn("Could not clean up partially created database '{}' after failed provisioning", dbName,
                            cleanupFailure);
                }
                log.error("Failed to provision database '{}' (user '{}')", dbName, userName, e);
                throw new ProvisioningException("Could not provision database '" + dbName + "'", e);
            }

            Instant now = clock.instant();
            ManagedDatabase metadata = new ManagedDatabase(dbName, userName, List.of("readWrite:" + dbName), now, now, null);
            managedDatabaseRepository.save(metadata);

            // Create RESTHeart API user + ACL so apps can hit /{db} through RESTHeart
            createRestheartUserAndAcl(userName, password, dbName);

            audit(AuditEvent.PROVISION, dbName, userName, now);
            log.info("Provisioned database '{}' with user '{}'", dbName, userName);

            return toInfo(dbName, metadata, collectionCount(dbName), null)
                    .withRestheartEnvVars(buildRestheartEnvVars(userName, password, dbName), resolveRestheartUrl());
        });
    }

    /**
     * Rotates the provisioned user's password. Returns the new env vars
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

            // Sync the password to the RESTHeart API user
            restheartService.updatePassword(metadata.getUserName(), password);

            metadata.setLastPasswordResetAt(clock.instant());
            managedDatabaseRepository.save(metadata);
            audit(AuditEvent.RESET_PASSWORD, dbName, metadata.getUserName(), metadata.getLastPasswordResetAt());
            log.info("Reset password for user '{}' on database '{}'", metadata.getUserName(), dbName);

            return toInfo(dbName, metadata, collectionCount(dbName), null)
                    .withRestheartEnvVars(buildRestheartEnvVars(metadata.getUserName(), password, dbName), resolveRestheartUrl());
        });
    }

    /**
     * Drops the database and (if provisioned) its user and metadata. Tolerates a
     * database whose namespace or user is already gone (e.g. an earlier partial
     * failure), so delete is always retryable.
     *
     * @return any non-fatal warnings from best-effort RESTHeart cleanup (empty if
     *         everything succeeded). Deletion of the Mongo database/metadata is
     *         never blocked by these, but callers should show them to the admin —
     *         a leftover RESTHeart user/ACL entry from a failed cleanup can
     *         silently corrupt a future re-provision of the same database name.
     */
    public List<String> delete(String dbName) {
        nameValidator.validateDatabaseName(dbName);
        return withDatabaseLock(dbName, () -> {
            Optional<ManagedDatabase> metadata = managedDatabaseRepository.findByDbName(dbName);
            List<String> warnings = new ArrayList<>();

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
                    restheartService.deleteUserIfExists(m.getUserName());
                } catch (Exception e) {
                    log.error("Could not delete RESTHeart user '{}' while deleting database '{}'",
                            m.getUserName(), dbName, e);
                    warnings.add("Could not delete RESTHeart user '" + m.getUserName() + "': " + e.getMessage());
                }
                try {
                    restheartService.deleteAclEntryIfExists(m.getUserName() + "-access");
                } catch (Exception e) {
                    log.error("Could not delete ACL entry '{}-access' while deleting database '{}'",
                            m.getUserName(), dbName, e);
                    warnings.add("Could not delete RESTHeart ACL entry '" + m.getUserName() + "-access': "
                            + e.getMessage());
                }

                // Best-effort: after dropDatabase() the user is already gone
                // (users live inside the DB). Tolerate UserNotFound and any
                // other error so metadata cleanup is never blocked.
                try {
                    mongoDatabaseRepository.dropUser(dbName, m.getUserName());
                } catch (Exception e) {
                    log.debug(".dropUser for '{}' was best-effort: {}", m.getUserName(), e.getMessage());
                }
            });

            metadata.ifPresent(m -> managedDatabaseRepository.deleteByDbName(dbName));
            audit(AuditEvent.DELETE, dbName, metadata.map(ManagedDatabase::getUserName).orElse(null), clock.instant());
            log.info("Deleted database '{}'{}", dbName, warnings.isEmpty() ? "" : " with warnings: " + warnings);
            return warnings;
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
        return toInfo(dbName, metadata.orElse(null), collectionCount(dbName), null);
    }

    /**
     * RESTHeart HTTP API base URL shown in the connection-info card.
     * Set via {@code RESTHEART_URL} env var; defaults to {@code http://localhost:9814}.
     */
    String resolveRestheartUrl() {
        String url = environment.getProperty("app.restheart-url", "");
        if (url != null && !url.isBlank()) {
            return url;
        }
        return "http://localhost:9814";
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

    /**
     * Builds a copy-pasteable env-vars block for the RESTHeart connection info.
     * Shown once after provisioning or password reset.
     */
    private String buildRestheartEnvVars(String userName, String password, String dbName) {
        String url = resolveRestheartUrl();
        return "RESTHEART_URL=" + url + "\n"
                + "DB_USER=" + userName + "\n"
                + "DB_PASS=" + password + "\n"
                + "MONGODB_DB=" + dbName;
    }

    private int collectionCount(String dbName) {
        try {
            return mongoDatabaseRepository.listCollectionNames(dbName).size();
        } catch (MongoCommandException e) {
            return 0;
        }
    }

    private DatabaseInfo toInfo(String dbName, ManagedDatabase metadata, Integer collectionsCount,
                                String restheartEnvVars) {
        if (metadata == null) {
            return new DatabaseInfo(dbName, null, List.of(), collectionsCount, null, null, null, false, restheartEnvVars, resolveRestheartUrl());
        }
        return new DatabaseInfo(dbName, metadata.getUserName(), metadata.getRoles(),
                collectionsCount, metadata.getCreatedAt(), metadata.getUpdatedAt(),
                metadata.getLastPasswordResetAt(), true, restheartEnvVars, resolveRestheartUrl());
    }

    private boolean isMongoCode(MongoCommandException e, int code) {
        return e.getErrorCode() == code;
    }

    /**
     * Creates a RESTHeart user (for API auth) and an ACL rule granting that user
     * access to the database path.
     *
     * <p>Both calls are now upserts (see {@link RestheartService#createUser} and
     * {@link RestheartService#upsertAclEntry}), so this is safely retryable. Failures
     * are <strong>not</strong> swallowed here anymore: previously they were only
     * logged at {@code warn}, which let provisioning report success to the admin
     * (with a freshly-generated password shown once) while the RESTHeart side was
     * left with stale or missing credentials/ACL — producing "access denied"
     * errors against the RESTHeart API with no indication of what went wrong. The
     * Mongo-level database and user are already created by this point, so on
     * failure we surface a clear error telling the admin the RESTHeart side needs
     * attention, rather than silently pretending everything succeeded.</p>
     */
    private void createRestheartUserAndAcl(String userName, String password, String dbName) {
        try {
            restheartService.createUser(userName, password, List.of(userName));
        } catch (Exception e) {
            log.error("Could not create RESTHeart user '{}' for database '{}'", userName, dbName, e);
            throw new ProvisioningException(
                    "Database '" + dbName + "' was created, but the RESTHeart API user '" + userName
                            + "' could not be provisioned. RESTHeart access will fail until this is fixed "
                            + "(retry password reset, or fix manually under /restheart/users). Cause: "
                            + e.getMessage(), e);
        }
        try {
            restheartService.upsertAclEntry(
                    userName + "-access",
                    // path-prefix() in Undertow's predicate language does a plain string
                    // prefix match, not a path-segment match: path-prefix('/app') also
                    // matches '/app2', '/application', '/apple', etc. Anchoring on '/dbName'
                    // or '/dbName/...' scopes the rule to exactly this database and its
                    // sub-paths, so a user provisioned for one database can't reach a
                    // differently-named database that happens to share a prefix.
                    "path('/" + dbName + "') or path-prefix('/" + dbName + "/')",
                    List.of(userName),
                    100,
                    true);
        } catch (Exception e) {
            log.error("Could not create ACL entry for user '{}' on database '{}'", userName, dbName, e);
            throw new ProvisioningException(
                    "Database '" + dbName + "' and RESTHeart user '" + userName
                            + "' were created, but the ACL rule could not be provisioned. RESTHeart requests will "
                            + "be denied until this is fixed (retry, or fix manually under /restheart/acl). Cause: "
                            + e.getMessage(), e);
        }
    }
}
