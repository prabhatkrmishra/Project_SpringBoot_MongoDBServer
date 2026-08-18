package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import com.pkmprojects.mongodbserver.dto.DatabaseStats;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the database statistics assembly (mock repository).
 */
@ExtendWith(MockitoExtension.class)
class StatisticsServiceTest {

    @Mock
    private MongoDatabaseRepository mongoDatabaseRepository;

    private StatisticsService service;

    @BeforeEach
    void setUp() {
        service = new StatisticsService(mongoDatabaseRepository, new MongoNameValidator());
    }

    @Test
    void getDatabaseStatsAssemblesAggregateAndCollections() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.getDbStats("myapp")).thenReturn(new Document("db", "myapp")
                .append("collections", 2)
                .append("views", 1)
                .append("objects", 150)
                .append("dataSize", 2048.0)
                .append("storageSize", 4096.0)
                .append("avgObjSize", 13.6)
                .append("indexes", 3)
                .append("indexSize", 1024.0));
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of("users", "orders"));
        when(mongoDatabaseRepository.getCollectionStats("myapp", "users")).thenReturn(new Document("ns", "myapp.users")
                .append("count", 100)
                .append("size", 1024)
                .append("storageSize", 2048)
                .append("avgObjSize", 10.2)
                .append("nindexes", 2)
                .append("totalIndexSize", 512));
        when(mongoDatabaseRepository.getCollectionStats("myapp", "orders")).thenReturn(new Document("ns", "myapp.orders")
                .append("count", 50)
                .append("size", 1024)
                .append("storageSize", 2048)
                .append("avgObjSize", 20.4)
                .append("nindexes", 1)
                .append("totalIndexSize", 512));

        var stats = service.getDatabaseStats("myapp");

        assertThat(stats.dbName()).isEqualTo("myapp");
        assertThat(stats.collectionCount()).isEqualTo(2);
        assertThat(stats.viewCount()).isEqualTo(1);
        assertThat(stats.totalDocuments()).isEqualTo(150);
        assertThat(stats.dataSizeBytes()).isEqualTo(2048);
        assertThat(stats.storageSizeBytes()).isEqualTo(4096);
        assertThat(stats.averageObjectSizeBytes()).isEqualTo(13);
        assertThat(stats.indexCount()).isEqualTo(3);
        assertThat(stats.indexSizeBytes()).isEqualTo(1024);
        assertThat(stats.collections()).hasSize(2);
        assertThat(stats.collections().get(0).name()).isEqualTo("users");
        assertThat(stats.collections().get(0).documentCount()).isEqualTo(100);
        assertThat(stats.collections().get(0).indexCount()).isEqualTo(2);
        assertThat(stats.collections().get(1).averageObjectSizeBytes()).isEqualTo(20);
    }

    @Test
    void getDatabaseStatsOfMissingDatabaseThrowsNotFound() {
        when(mongoDatabaseRepository.databaseExists("missing")).thenReturn(false);

        assertThatThrownBy(() -> service.getDatabaseStats("missing"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void getDatabaseStatsRejectsInvalidName() {
        assertThatThrownBy(() -> service.getDatabaseStats("bad name!"))
                .isInstanceOf(com.pkmprojects.mongodbserver.error.NameNotAllowedException.class);
    }

    @Test
    void getDatabaseStatsThrowsProvisioningExceptionWhenDbStatsFails() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        doThrow(mongoError(13, "Unauthorized")).when(mongoDatabaseRepository).getDbStats("myapp");

        assertThatThrownBy(() -> service.getDatabaseStats("myapp"))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void getDatabaseStatsThrowsProvisioningExceptionWhenCollStatsFails() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.getDbStats("myapp")).thenReturn(new Document("collections", 1));
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of("users"));
        doThrow(mongoError(13, "Unauthorized")).when(mongoDatabaseRepository).getCollectionStats("myapp", "users");

        assertThatThrownBy(() -> service.getDatabaseStats("myapp"))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void byteLabelsFormatHumanReadable() {
        assertThat(new DatabaseStats("d", 0, 0, 0, 512, 0, 0, 0, 0, List.of())
                .dataSizeLabel()).isEqualTo("512 B");
        assertThat(new DatabaseStats("d", 0, 0, 0, 2048, 0, 0, 0, 0, List.of())
                .dataSizeLabel()).isEqualTo("2.0 KB");
        assertThat(new DatabaseStats("d", 0, 0, 0, 5242880, 0, 0, 0, 0, List.of())
                .dataSizeLabel()).isEqualTo("5.0 MB");
        assertThat(new DatabaseStats("d", 0, 0, 0, 2147483648L, 0, 0, 0, 0, List.of())
                .dataSizeLabel()).isEqualTo("2.00 GB");
    }

    private MongoCommandException mongoError(int code, String message) {
        return new MongoCommandException(
                new BsonDocument("ok", new BsonInt32(0))
                        .append("code", new BsonInt32(code))
                        .append("errmsg", new BsonString(message)),
                new ServerAddress("localhost", 27017));
    }
}