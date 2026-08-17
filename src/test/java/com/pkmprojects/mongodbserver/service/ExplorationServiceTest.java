package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.CollectionInfo;
import com.pkmprojects.mongodbserver.dto.DocumentPage;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import org.bson.BsonType;
import org.bson.Document;
import org.bson.json.JsonReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the read-only explorer: collection listing and paginated
 * document reads.
 */
@ExtendWith(MockitoExtension.class)
class ExplorationServiceTest {

    @Mock
    private MongoDatabaseRepository mongoDatabaseRepository;

    private ExplorationService service;

    @BeforeEach
    void setUp() {
        service = new ExplorationService(mongoDatabaseRepository, new MongoNameValidator());
    }

    @Test
    void listCollectionsWithCounts() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.listCollectionNames("myapp")).thenReturn(List.of("users", "orders"));
        when(mongoDatabaseRepository.countDocuments("myapp", "users")).thenReturn(3L);
        when(mongoDatabaseRepository.countDocuments("myapp", "orders")).thenReturn(7L);

        List<CollectionInfo> collections = service.listCollections("myapp");

        assertThat(collections).extracting(CollectionInfo::name).containsExactly("users", "orders");
        assertThat(collections).extracting(CollectionInfo::documentCount).containsExactly(3L, 7L);
    }

    @Test
    void listCollectionsOnMissingDatabaseThrows() {
        when(mongoDatabaseRepository.databaseExists("nope")).thenReturn(false);

        assertThatThrownBy(() -> service.listCollections("nope"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void getDocumentsFirstPage() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(true);
        when(mongoDatabaseRepository.countDocuments("myapp", "items")).thenReturn(120L);
        when(mongoDatabaseRepository.findDocuments("myapp", "items", 0, 50))
                .thenReturn(documents(50));

        DocumentPage page = service.getDocuments("myapp", "items", 1);

        assertThat(page.totalCount()).isEqualTo(120L);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.documents()).hasSize(50);
        assertThat(page.hasPrev()).isFalse();
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void getDocumentsLastPage() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(true);
        when(mongoDatabaseRepository.countDocuments("myapp", "items")).thenReturn(120L);
        when(mongoDatabaseRepository.findDocuments("myapp", "items", 100, 50))
                .thenReturn(documents(20));

        DocumentPage page = service.getDocuments("myapp", "items", 3);

        assertThat(page.page()).isEqualTo(3);
        assertThat(page.documents()).hasSize(20);
        assertThat(page.hasPrev()).isTrue();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void getDocumentsClampsInvalidPageToOne() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(true);
        when(mongoDatabaseRepository.countDocuments("myapp", "items")).thenReturn(5L);
        when(mongoDatabaseRepository.findDocuments("myapp", "items", 0, 50)).thenReturn(documents(5));

        DocumentPage page = service.getDocuments("myapp", "items", 0);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.documents()).hasSize(5);
    }

    @Test
    void getDocumentsClampsExcessPageToLastPage() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(true);
        when(mongoDatabaseRepository.countDocuments("myapp", "items")).thenReturn(120L);
        // page 9999 exceeds totalPages (3) → clamped to page 3, skip=100
        when(mongoDatabaseRepository.findDocuments("myapp", "items", 100, 50))
                .thenReturn(documents(20));

        DocumentPage page = service.getDocuments("myapp", "items", 9999);

        assertThat(page.page()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.documents()).hasSize(20);
        assertThat(page.hasPrev()).isTrue();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void getDocumentsClampsExcessPageOnEmptyCollection() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(true);
        when(mongoDatabaseRepository.countDocuments("myapp", "items")).thenReturn(0L);
        when(mongoDatabaseRepository.findDocuments("myapp", "items", 0, 50))
                .thenReturn(List.of());

        DocumentPage page = service.getDocuments("myapp", "items", 5);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.totalPages()).isZero();
        assertThat(page.documents()).isEmpty();
        assertThat(page.hasPrev()).isFalse();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void getDocumentsOnMissingCollectionThrows() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "nope")).thenReturn(false);

        assertThatThrownBy(() -> service.getDocuments("myapp", "nope", 1))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void exportDocumentsAsJsonReturnsWellFormedJsonArray() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "items")).thenReturn(true);
        when(mongoDatabaseRepository.countDocuments("myapp", "items")).thenReturn(120L);
        when(mongoDatabaseRepository.findDocuments("myapp", "items", 0, 50)).thenReturn(documents(2));

        String json = service.exportDocumentsAsJson("myapp", "items", 1);

        // parse, not substring-match, so a malformed join (e.g. a missing comma)
        // would fail this test
        List<Document> parsed = parseJsonArray(json);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).getInteger("_id")).isZero();
        assertThat(parsed.get(0).getString("value")).isEqualTo("v0");
        assertThat(parsed.get(1).getString("value")).isEqualTo("v1");
    }

    /**
     * Fully consumes a JSON array via the driver's BSON reader, so any malformed
     * JSON (or a broken join in the exporter) throws instead of passing.
     */
    private List<Document> parseJsonArray(String json) {
        JsonReader reader = new JsonReader(json);
        reader.readStartArray();
        List<Document> result = new ArrayList<>();
        while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
            reader.readStartDocument();
            Document document = new Document();
            while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
                String key = reader.readName();
                Object value = switch (reader.getCurrentBsonType()) {
                    case INT32 -> reader.readInt32();
                    case INT64 -> reader.readInt64();
                    case DOUBLE -> reader.readDouble();
                    case BOOLEAN -> reader.readBoolean();
                    case STRING -> reader.readString();
                    case NULL -> {
                        reader.readNull();
                        yield null;
                    }
                    default -> throw new AssertionError("unexpected BSON type in export test data");
                };
                document.append(key, value);
            }
            reader.readEndDocument();
            result.add(document);
        }
        reader.readEndArray();
        return result;
    }

    @Test
    void exportDocumentsAsJsonOnMissingCollectionThrows() {
        when(mongoDatabaseRepository.databaseExists("myapp")).thenReturn(true);
        when(mongoDatabaseRepository.collectionExists("myapp", "nope")).thenReturn(false);

        assertThatThrownBy(() -> service.exportDocumentsAsJson("myapp", "nope", 1))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    private List<Document> documents(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new Document("_id", i).append("value", "v" + i))
                .toList();
    }
}
