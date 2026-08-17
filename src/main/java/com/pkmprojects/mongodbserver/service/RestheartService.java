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
     * Creates a new RESTHeart user. The password is bcrypt-hashed before storage.
     *
     * @param id       username (used as _id)
     * @param password plaintext password
     * @param roles    list of role names (e.g. ["user", "admin"])
     */
    public void createUser(String id, String password, List<String> roles) {
        if (findUser(id) != null) {
            throw new ProvisioningException("RESTHeart user '" + id + "' already exists", null);
        }
        Document doc = new Document("_id", id)
                .append("password", passwordEncoder.encode(password))
                .append("roles", roles);
        try {
            usersCollection.insertOne(doc);
            log.info("Created RESTHeart user '{}' with roles {}", id, roles);
        } catch (MongoCommandException e) {
            log.error("Failed to create RESTHeart user '{}'", id, e);
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
     * Creates or updates an ACL entry.
     *
     * @param id              unique identifier for the rule
     * @param url             URL pattern (e.g. {@code /})
     * @param methods         allowed HTTP methods (e.g. ["GET", "POST"])
     * @param roles           roles that can access this pattern (e.g. ["user"])
     * @param authenticationRequired whether authentication is required
     */
    public void upsertAclEntry(String id, String url, List<String> methods,
                               List<String> roles, boolean authenticationRequired) {
        Document doc = new Document("_id", id)
                .append("url", url)
                .append("methods", methods)
                .append("roles", roles)
                .append("authentication-required", authenticationRequired);
        try {
            Document existing = findAclEntry(id);
            if (existing != null) {
                aclCollection.replaceOne(new Document("_id", id), doc);
                log.info("Updated ACL entry '{}': url={}, roles={}", id, url, roles);
            } else {
                aclCollection.insertOne(doc);
                log.info("Created ACL entry '{}': url={}, roles={}", id, url, roles);
            }
        } catch (MongoCommandException e) {
            log.error("Failed to upsert ACL entry '{}'", id, e);
            throw new ProvisioningException("Could not save ACL entry '" + id + "'", e);
        }
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
