package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.error.DatabaseAlreadyExistsException;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.ManagedDatabase;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.repository.ManagedDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.security.PasswordGenerator;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the provisioning lifecycle (mock repositories). Concurrency
 * behavior is covered separately by {@code ProvisioningConcurrencyTest}.
 */
@ExtendWith(MockitoExtension.class)
class ProvisioningServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Mock
    private MongoDatabaseRepository mongoDatabaseRepository;
    @Mock
    private ManagedDatabaseRepository managedDatabaseRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private PasswordGenerator passwordGenerator;
    @Mock
    private Environment environment;
    @Mock
    private RestheartService restheartService;

    private ProvisioningService service;

    @BeforeEach
    void setUp() {
        // Only exercised by tests that build RESTHeart env vars; lenient so the
        // lifecycle-only tests do not trip Mockito's strict stubbing.
        lenient().when(environment.getProperty("app.restheart-url", ""))
                .thenReturn("http://localhost:9814");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        service = new ProvisioningService(mongoDatabaseRepository, managedDatabaseRepository,
                auditLogRepository, new MongoNameValidator(), passwordGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC), environment, restheartService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void provisionCreatesUserDatabaseMetadataAndAudit() {
        when(passwordGenerator.generate(16)).thenReturn("generatedPass123");
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of("_bootstrap"));

        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", "appuser", ""));

        verify(mongoDatabaseRepository).createUser("myapp", "appuser", "generatedPass123");
        verify(mongoDatabaseRepository).createDatabase("myapp");
        ArgumentCaptor<ManagedDatabase> metadataCaptor = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(managedDatabaseRepository).save(metadataCaptor.capture());
        ManagedDatabase saved = metadataCaptor.getValue();
        assertThat(saved.getDbName()).isEqualTo("myapp");
        assertThat(saved.getUserName()).isEqualTo("appuser");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getLastPasswordResetAt()).isNull();

        // Verify RESTHeart API user and ACL were created
        verify(restheartService).createUser("appuser", "generatedPass123", List.of("appuser"));
        verify(restheartService).upsertAclEntry("appuser-access", "path('/myapp') or path-prefix('/myapp/')", List.of("appuser"), 100, true);

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.PROVISION);
        assertThat(auditCaptor.getValue().getPerformedBy()).isEqualTo("admin");
        assertThat(auditCaptor.getValue().getPerformedAt()).isEqualTo(NOW);

        assertThat(info.provisioned()).isTrue();
        assertThat(info.restheartEnvVars()).isEqualTo("RESTHEART_URL=http://localhost:9814\nDB_USER=appuser\nDB_PASS=generatedPass123\nMONGODB_DB=myapp");
    }

    @Test
    void provisionUsesExplicitPassword() {
        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", "appuser", "mysecret123"));

        verify(mongoDatabaseRepository).createUser("myapp", "appuser", "mysecret123");
        assertThat(info.restheartEnvVars()).contains("DB_USER=appuser\nDB_PASS=mysecret123\nMONGODB_DB=myapp");
    }

    @Test
    void buildRestheartEnvVarsIncludesPasswordAsIs() {
        when(passwordGenerator.generate(16)).thenReturn("p@ss#word/x?y");

        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", "app.user", ""));

        assertThat(info.restheartEnvVars())
                .isEqualTo("RESTHEART_URL=http://localhost:9814\nDB_USER=app.user\nDB_PASS=p@ss#word/x?y\nMONGODB_DB=myapp");
    }

    @Test
    void provisionEncodesSpecialCharactersInUserSuppliedPassword() {
        DatabaseInfo info = service.provision(new CreateDatabaseForm("myapp", "appuser", "s3cret%#@:"));

        assertThat(info.restheartEnvVars()).isEqualTo("RESTHEART_URL=http://localhost:9814\nDB_USER=appuser\nDB_PASS=s3cret%#@:\nMONGODB_DB=myapp");
    }

    @Test
    void provisionRejectsExistingDatabase() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);

        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", "appuser", "")))
                .isInstanceOf(DatabaseAlreadyExistsException.class);
        verify(mongoDatabaseRepository, never()).createUser(any(), any(), any());
    }

    @Test
    void provisionRejectsExistingMetadata() {
        when(managedDatabaseRepository.existsByDbName("myapp")).thenReturn(true);

        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", "appuser", "")))
                .isInstanceOf(DatabaseAlreadyExistsException.class);
        verify(mongoDatabaseRepository, never()).createUser(any(), any(), any());
    }

    @Test
    void provisionFailureCleansUpPartialDatabase() {
        org.mockito.Mockito.doThrow(mongoError(13, "Unauthorized"))
                .when(mongoDatabaseRepository).createDatabase(eq("myapp"));

        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", "appuser", "mysecret123")))
                .isInstanceOf(ProvisioningException.class);

        verify(mongoDatabaseRepository).dropDatabase("myapp");
        verify(managedDatabaseRepository, never()).save(any());
    }

    @Test
    void provisionMapsConcurrentDuplicateUserToConflict() {
        org.mockito.Mockito.doThrow(mongoError(51003, "UserAlreadyExists"))
                .when(mongoDatabaseRepository).createUser(eq("myapp"), eq("appuser"), any());

        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("myapp", "appuser", "mysecret123")))
                .isInstanceOf(DatabaseAlreadyExistsException.class);
    }

    @Test
    void resetPasswordRotatesAndReturnsNewCredentials() {
        ManagedDatabase metadata = new ManagedDatabase("myapp", "appuser", List.of("readWrite:myapp"),
                NOW, NOW, null);
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.of(metadata));

        DatabaseInfo info = service.resetPassword("myapp", new ResetPasswordForm("newsecret456"));

        verify(mongoDatabaseRepository).updateUserPassword("myapp", "appuser", "newsecret456");
        verify(restheartService).updatePassword("appuser", "newsecret456");
        assertThat(info.restheartEnvVars()).isEqualTo("RESTHEART_URL=http://localhost:9814\nDB_USER=appuser\nDB_PASS=newsecret456\nMONGODB_DB=myapp");
        assertThat(metadata.getLastPasswordResetAt()).isEqualTo(NOW);
        verify(managedDatabaseRepository).save(metadata);

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.RESET_PASSWORD);
    }

    @Test
    void resetPasswordGeneratesWhenBlank() {
        ManagedDatabase metadata = new ManagedDatabase("myapp", "appuser", List.of("readWrite:myapp"),
                NOW, NOW, null);
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.of(metadata));
        when(passwordGenerator.generate(16)).thenReturn("rotatedPass456");

        service.resetPassword("myapp", new ResetPasswordForm(""));

        verify(mongoDatabaseRepository).updateUserPassword("myapp", "appuser", "rotatedPass456");
    }

    @Test
    void resetPasswordOnUnprovisionedDatabaseThrows() {
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("myapp", new ResetPasswordForm("newsecret456")))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void deleteDropsUserDatabaseAndMetadata() {
        ManagedDatabase metadata = new ManagedDatabase("myapp", "appuser", List.of("readWrite:myapp"),
                NOW, NOW, null);
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.of(metadata));

        service.delete("myapp");

        verify(mongoDatabaseRepository).dropDatabase("myapp");
        verify(mongoDatabaseRepository).dropUser("myapp", "appuser");
        verify(restheartService).deleteUserIfExists("appuser");
        verify(restheartService).deleteAclEntryIfExists("appuser-access");
        verify(managedDatabaseRepository).deleteByDbName("myapp");

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.DELETE);
    }

    @Test
    void deleteWithoutMetadataSkipsUserAndMetadata() {
        when(managedDatabaseRepository.findByDbName("externaldb")).thenReturn(Optional.empty());

        service.delete("externaldb");

        verify(mongoDatabaseRepository, never()).dropUser(any(), any());
        verify(mongoDatabaseRepository).dropDatabase("externaldb");
        verify(managedDatabaseRepository, never()).deleteByDbName(any());
        verify(restheartService, never()).deleteUserIfExists(any());
        verify(restheartService, never()).deleteAclEntryIfExists(any());
    }

    @Test
    void deleteToleratesMissingUserAndNamespace() {
        ManagedDatabase metadata = new ManagedDatabase("myapp", "appuser", List.of("readWrite:myapp"),
                NOW, NOW, null);
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.of(metadata));
        org.mockito.Mockito.doThrow(mongoError(26, "NamespaceNotFound"))
                .when(mongoDatabaseRepository).dropDatabase("myapp");
        org.mockito.Mockito.doThrow(mongoError(11, "UserNotFound"))
                .when(mongoDatabaseRepository).dropUser("myapp", "appuser");

        service.delete("myapp");

        verify(mongoDatabaseRepository).dropDatabase("myapp");
        verify(restheartService).deleteUserIfExists("appuser");
        verify(restheartService).deleteAclEntryIfExists("appuser-access");
        verify(mongoDatabaseRepository).dropUser("myapp", "appuser");
        verify(managedDatabaseRepository).deleteByDbName("myapp");
    }

    @Test
    void deletePropagatesRealErrors() {
        when(managedDatabaseRepository.findByDbName("myapp")).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(mongoError(13, "Unauthorized"))
                .when(mongoDatabaseRepository).dropDatabase("myapp");

        assertThatThrownBy(() -> service.delete("myapp"))
                .isInstanceOf(ProvisioningException.class);
        verify(managedDatabaseRepository, never()).deleteByDbName(any());
    }

    @Test
    void createCollectionRequiresExistingDatabase() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(false);

        assertThatThrownBy(() -> service.createCollection("myapp", "items"))
                .isInstanceOf(DatabaseNotFoundException.class);
        verify(mongoDatabaseRepository, never()).createCollection(any(), any());
    }

    @Test
    void createCollectionRejectsDuplicate() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(true);

        assertThatThrownBy(() -> service.createCollection("myapp", "items"))
                .isInstanceOf(DatabaseAlreadyExistsException.class);
        verify(mongoDatabaseRepository, never()).createCollection(any(), any());
    }

    @Test
    void createCollectionSucceedsOnExistingDatabase() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(false);

        service.createCollection("myapp", "items");

        verify(mongoDatabaseRepository).createCollection("myapp", "items");
    }

    @Test
    void dropCollectionOnMissingCollectionThrows() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(false);

        assertThatThrownBy(() -> service.dropCollection("myapp", "items"))
                .isInstanceOf(DatabaseNotFoundException.class);
        verify(mongoDatabaseRepository, never()).dropCollection(any(), any());
    }

    @Test
    void dropCollectionSucceeds() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(true);

        service.dropCollection("myapp", "items");

        verify(mongoDatabaseRepository).dropCollection("myapp", "items");
    }

    @Test
    void listDatabasesExcludesSystemAndMetadataDatabases() {
        when(mongoDatabaseRepository.listDatabaseNames())
                .thenReturn(List.of("admin", "config", "local", "mongodb_admin", "myapp", "externaldb"));
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of("_bootstrap"));
        when(mongoDatabaseRepository.listCollectionNames("externaldb")).thenReturn(List.of());
        ManagedDatabase metadata = new ManagedDatabase("myapp", "appuser", List.of("readWrite:myapp"),
                NOW, NOW, null);
        when(managedDatabaseRepository.findAll()).thenReturn(List.of(metadata));

        List<DatabaseInfo> databases = service.listDatabases();

        assertThat(databases).extracting(DatabaseInfo::dbName).containsExactly("externaldb", "myapp");
        DatabaseInfo myapp = databases.get(1);
        assertThat(myapp.provisioned()).isTrue();
        assertThat(myapp.userName()).isEqualTo("appuser");
        assertThat(myapp.collectionsCount()).isEqualTo(1);
        assertThat(databases.get(0).provisioned()).isFalse();
    }

    @Test
    void getDatabaseThrowsWhenMissing() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(false);

        assertThatThrownBy(() -> service.getDatabase("myapp"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void resolveRestheartUrlReturnsConfiguredValue() {
        when(environment.getProperty("app.restheart-url", "")).thenReturn("https://mongoapi.pkmprojects.online");
        assertThat(service.resolveRestheartUrl()).isEqualTo("https://mongoapi.pkmprojects.online");

        when(environment.getProperty("app.restheart-url", "")).thenReturn("");
        assertThat(service.resolveRestheartUrl()).isEqualTo("http://localhost:9814");
    }

    @Test
    void provisionRejectsSystemDatabaseName() {
        assertThatThrownBy(() -> service.provision(new CreateDatabaseForm("admin", "appuser", "")))
                .isInstanceOf(NameNotAllowedException.class);
        verify(mongoDatabaseRepository, never()).createUser(any(), any(), any());
    }

    private MongoCommandException mongoError(int code, String message) {
        return new MongoCommandException(
                new BsonDocument("ok", new BsonInt32(0))
                        .append("code", new BsonInt32(code))
                        .append("errmsg", new BsonString(message)),
                new ServerAddress("localhost", 27017));
    }
}
