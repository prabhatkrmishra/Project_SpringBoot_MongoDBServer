package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages RESTHeart's internal {@code restheart.users} and {@code restheart.acl}
 * collections via direct MongoDB driver access. These collections control which
 * credentials RESTHeart accepts and which URL patterns they are allowed to reach.
 *
 * <p>RESTHeart's {@code MongoDBRealmAuthenticator} reads from these collections
 * (NOT the application's own user collections) to authenticate HTTP Basic requests.</p>
 */
@Service
public class RestheartService {

    private static final Logger log = LoggerFactory.getLogger(RestheartService.class);

    private static final String RESTHEART_DB = "restheart";
    private static final String USERS_COLLECTION = "users";
    private static final String ACL_COLLECTION = "acl";

    private final MongoCollection<Document> usersCollection;
    private final MongoCollection<Document> aclCollection;
    private final PasswordEncoder passwordEncoder;

    public RestheartService(MongoClient mongoClient, PasswordEncoder passwordEncoder) {
        this.usersCollection = mongoClient.getDatabase(RESTHEART_DB).getCollection(USERS_COLLECTION);
        this.aclCollection = mongoClient.getDatabase(RESTHEART_DB).getCollection(ACL_COLLECTION);
        this.passwordEncoder = passwordEncoder;
    }

    // ─── Users ────────────────────────────────────────────────────────────────

    /**
     * @return all RESTHeart users (password field included for display, bcrypt hashes)
     */
    public List<Document> listUsers() {
        List<Document> users = new ArrayList<>();
        usersCollection.find().forEach(users::add);
        return users;
    }

    /**
     * @return a single user by _id, or null if not found
     */
    public Document findUser(String id) {
        return usersCollection.find(new Document("_id", id)).first();
    }

    /**
     * Creates (or repairs) a RESTHeart user. The password is bcrypt-hashed before
     * storage. This is an upsert: provisioning must be safely retryable, and a
     * stale leftover document (e.g. from a database that was deleted outside the
     * app's own delete flow, or from a previous partially-failed provision) must
     * not silently keep an old password/roles combination alive. A plain
     * insert-or-throw here would leave the RESTHeart credentials out of sync with
     * whatever password the admin was just shown, producing confusing
     * "access denied" errors against the RESTHeart API even though the app
     * reported success.
     *
     * @param id       username (used as _id)
     * @param password plaintext password
     * @param roles    list of role names (e.g. ["user", "admin"])
     */
    public void createUser(String id, String password, List<String> roles) {
        Document doc = new Document("_id", id)
                .append("password", passwordEncoder.encode(password))
                .append("roles", roles);
        try {
            Document existing = findUser(id);
            if (existing != null) {
                usersCollection.replaceOne(new Document("_id", id), doc);
                log.info("Replaced existing RESTHeart user '{}' with roles {}", id, roles);
            } else {
                usersCollection.insertOne(doc);
                log.info("Created RESTHeart user '{}' with roles {}", id, roles);
            }
        } catch (MongoCommandException e) {
            log.error("Failed to create/replace RESTHeart user '{}'", id, e);
            throw new ProvisioningException("Could not create RESTHeart user '" + id + "'", e);
        }
    }

    /**
     * Resets a RESTHeart user's password. The new password is bcrypt-hashed.
     */
    public void resetPassword(String id, String newPassword) {
        Document doc = findUser(id);
        if (doc == null) {
            throw new ProvisioningException("RESTHeart user '" + id + "' not found", null);
        }
        Document update = new Document("$set",
                new Document("password", passwordEncoder.encode(newPassword)));
        try {
            usersCollection.updateOne(new Document("_id", id), update);
            log.info("Reset password for RESTHeart user '{}'", id);
        } catch (MongoCommandException e) {
            log.error("Failed to reset password for RESTHeart user '{}'", id, e);
            throw new ProvisioningException("Could not reset password for RESTHeart user '" + id + "'", e);
        }
    }

    /**
     * Best-effort password update for a RESTHeart user, called by provisioning
     * after the MongoDB password has already been rotated. Does not throw if the
     * update fails, so a RESTHeart-side hiccup does not roll back a MongoDB
     * password rotation that already succeeded.
     *
     * <p>If the RESTHeart user is missing entirely (e.g. an earlier provisioning
     * step failed before the user was created), this <em>creates</em> it with
     * default role {@code [id]} rather than silently skipping — a silent skip
     * here previously left RESTHeart permanently out of sync with no way to
     * self-heal on the next password reset, producing persistent "access denied"
     * errors even after the admin thought they'd fixed things by rotating the
     * password again.</p>
     */
    public void updatePassword(String id, String newPassword) {
        try {
            Document doc = findUser(id);
            if (doc == null) {
                log.warn("RESTHeart user '{}' not found — creating it during password sync", id);
                createUser(id, newPassword, List.of(id));
                return;
            }
            Document update = new Document("$set",
                    new Document("password", passwordEncoder.encode(newPassword)));
            usersCollection.updateOne(new Document("_id", id), update);
            log.info("Updated password for RESTHeart user '{}'", id);
        } catch (Exception e) {
            log.warn("Could not update password for RESTHeart user '{}': {}", id, e.getMessage());
        }
    }

    /**
     * Updates a RESTHeart user's roles.
     */
    public void updateRoles(String id, List<String> roles) {
        Document doc = findUser(id);
        if (doc == null) {
            throw new ProvisioningException("RESTHeart user '" + id + "' not found", null);
        }
        Document update = new Document("$set", new Document("roles", roles));
        try {
            usersCollection.updateOne(new Document("_id", id), update);
            log.info("Updated roles for RESTHeart user '{}' to {}", id, roles);
        } catch (MongoCommandException e) {
            log.error("Failed to update roles for RESTHeart user '{}'", id, e);
            throw new ProvisioningException("Could not update roles for RESTHeart user '" + id + "'", e);
        }
    }

    /**
     * Deletes a RESTHeart user.
     */
    public void deleteUser(String id) {
        try {
            usersCollection.deleteOne(new Document("_id", id));
            log.info("Deleted RESTHeart user '{}'", id);
        } catch (MongoCommandException e) {
            log.error("Failed to delete RESTHeart user '{}'", id, e);
            throw new ProvisioningException("Could not delete RESTHeart user '" + id + "'", e);
        }
    }

    /**
     * Charset allowed in Undertow predicate expressions as used by RESTHeart's
     * ACL rules: identifiers, string literals in single/double quotes, numbers,
     * and the small set of punctuation the grammar uses (parens, commas, dots,
     * slashes, colons, braces, brackets, operators, {@code @} for @user
     * references, {@code $} for placeholders/BSON operators).
     */
    private static final java.util.regex.Pattern PREDICATE_CHARSET =
            java.util.regex.Pattern.compile("[A-Za-z0-9_./:@$*\\-{}\\[\\], '\"()=!<>]+");

    /**
     * Rejects obviously-malformed ACL predicates before they are written to
     * {@code restheart.acl}. This is not a full Undertow-predicate-language
     * parser — it only catches the common failure modes (unbalanced quotes or
     * parens, disallowed characters, blank input). RESTHeart parses the whole
     * {@code restheart.acl} collection to build its authorizer, so a single
     * malformed document written here — most likely via the free-text predicate
     * field on the manual ACL admin form, rather than the auto-generated
     * per-database predicate — can affect ACL evaluation for other rules too,
     * not just this one. Catching syntax problems here, before they reach
     * RESTHeart, avoids that blast radius.
     */
    private void validatePredicateSyntax(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            throw new ProvisioningException("ACL predicate must not be blank", null);
        }
        if (!PREDICATE_CHARSET.matcher(predicate).matches()) {
            throw new ProvisioningException(
                    "ACL predicate contains characters outside the Undertow predicate syntax: " + predicate, null);
        }
        int parens = 0;
        Character quote = null;
        for (char c : predicate.toCharArray()) {
            if (quote != null) {
                if (c == quote) {
                    quote = null;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                parens++;
            } else if (c == ')') {
                parens--;
                if (parens < 0) {
                    throw new ProvisioningException("ACL predicate has an unmatched ')': " + predicate, null);
                }
            }
        }
        if (quote != null) {
            throw new ProvisioningException("ACL predicate has an unterminated " + quote + " quote: " + predicate,
                    null);
        }
        if (parens != 0) {
            throw new ProvisioningException("ACL predicate has " + parens + " unmatched '(': " + predicate, null);
        }
    }

    // ─── ACL ──────────────────────────────────────────────────────────────────

    /**
     * @return all ACL entries
     */
    public List<Document> listAcl() {
        List<Document> acl = new ArrayList<>();
        aclCollection.find().forEach(acl::add);
        return acl;
    }

    /**
     * @return a single ACL entry by _id
     */
    public Document findAclEntry(String id) {
        try {
            return aclCollection.find(new Document("_id", id)).first();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Creates or updates an ACL entry using RESTHeart's predicate-based format.
     *
     * @param id                      unique identifier for the rule
     * @param predicate               undertow predicate expression (e.g. {@code path-prefix('/')})
     * @param roles                   roles that can access this pattern (e.g. ["user"])
     * @param priority                evaluation precedence (higher = evaluated first)
     * @param allowManagementRequests when true, allows the user to create databases and
     *                                collections via RESTHeart (e.g. PUT /{db}/{collection})
     */
    public void upsertAclEntry(String id, String predicate, List<String> roles, int priority,
                               boolean allowManagementRequests) {
        validatePredicateSyntax(predicate);
        Document doc = new Document("_id", id)
                .append("roles", roles)
                .append("predicate", predicate)
                .append("priority", priority);
        if (allowManagementRequests) {
            doc.append("mongo", new Document("allowManagementRequests", true));
        }
        try {
            Document existing = findAclEntry(id);
            if (existing != null) {
                aclCollection.replaceOne(new Document("_id", id), doc);
                log.info("Updated ACL entry '{}': predicate={}, roles={}, management={}", id, predicate, roles, allowManagementRequests);
            } else {
                aclCollection.insertOne(doc);
                log.info("Created ACL entry '{}': predicate={}, roles={}, management={}", id, predicate, roles, allowManagementRequests);
            }
        } catch (MongoCommandException e) {
            log.error("Failed to upsert ACL entry '{}'", id, e);
            throw new ProvisioningException("Could not save ACL entry '" + id + "'", e);
        }
    }

    /**
     * Best-effort delete of a RESTHeart user. Tolerates the user not existing,
     * but network/server errors are propagated so the caller (see
     * {@link ProvisioningService#delete}) can surface them instead of leaving a
     * leftover RESTHeart credential invisibly behind — which previously could
     * collide with, and corrupt, a later re-provision of the same database name.
     */
    public void deleteUserIfExists(String id) {
        usersCollection.deleteOne(new Document("_id", id));
        log.info("Deleted RESTHeart user '{}'", id);
    }

    /**
     * Best-effort delete of an ACL entry. Tolerates the entry not existing, but
     * network/server errors are propagated — see {@link #deleteUserIfExists}.
     */
    public void deleteAclEntryIfExists(String id) {
        aclCollection.deleteOne(new Document("_id", id));
        log.info("Deleted ACL entry '{}'", id);
    }

    /**
     * Deletes an ACL entry.
     */
    public void deleteAclEntry(String id) {
        try {
            aclCollection.deleteOne(new Document("_id", id));
            log.info("Deleted ACL entry '{}'", id);
        } catch (MongoCommandException e) {
            log.error("Failed to delete ACL entry '{}'", id, e);
            throw new ProvisioningException("Could not delete ACL entry '" + id + "'", e);
        }
    }
}
