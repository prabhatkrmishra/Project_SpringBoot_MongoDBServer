package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import com.pkmprojects.mongodbserver.dto.MonitorSnapshot;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for live monitor snapshot assembly and its hand-built JSON.
 */
@ExtendWith(MockitoExtension.class)
class MonitorServiceTest {

    private static final Instant START = Instant.parse("2026-08-18T10:00:00Z");

    @Mock
    private MongoDatabaseRepository mongoDatabaseRepository;

    private final MutableClock clock = new MutableClock(START);
    private MonitorService service;

    @BeforeEach
    void setUp() {
        service = new MonitorService(mongoDatabaseRepository, clock);
    }

    private Document fullStatus() {
        return new Document("version", "8.0.39")
                .append("uptime", 3600L)
                .append("connections", new Document("current", 5))
                .append("opcounters", new Document("insert", 1000)
                        .append("query", 2000)
                        .append("update", 300)
                        .append("delete", 50)
                        .append("command", 5000))
                .append("network", new Document("bytesIn", 1000).append("bytesOut", 2000))
                .append("mem", new Document("resident", 300).append("virtual", 900))
                .append("globalLock", new Document("currentQueue", new Document("total", 1)
                                .append("readers", 0).append("writers", 1))
                        .append("activeClients", new Document("total", 2)
                                .append("readers", 1).append("writers", 0)));
    }

    private Document statusAfter5s() {
        return new Document("version", "8.0.39")
                .append("uptime", 3605L)
                .append("connections", new Document("current", 6))
                .append("opcounters", new Document("insert", 1100)
                        .append("query", 2200)
                        .append("update", 305)
                        .append("delete", 50)
                        .append("command", 5050))
                .append("network", new Document("bytesIn", 1500).append("bytesOut", 2500))
                .append("mem", new Document("resident", 310).append("virtual", 910))
                .append("globalLock", new Document("currentQueue", new Document("total", 0)
                                .append("readers", 0).append("writers", 0))
                        .append("activeClients", new Document("total", 2)
                                .append("readers", 1).append("writers", 0)));
    }

    private MongoCommandException mongoError() {
        return new MongoCommandException(
                new BsonDocument("ok", new BsonInt32(0))
                        .append("code", new BsonInt32(13))
                        .append("errmsg", new BsonString("Unauthorized")),
                new ServerAddress("localhost", 27017));
    }

    private void stubSizes() {
        when(mongoDatabaseRepository.getDatabaseSizes()).thenReturn(Map.of("a", 1024L, "b", 2048L));
    }

    // ── Snapshot assembly ───────────────────────────────────────────────

    @Test
    void firstSnapshotParsesServerStatusWithZeroRates() {
        when(mongoDatabaseRepository.getServerStatus()).thenReturn(fullStatus());
        stubSizes();

        MonitorSnapshot snapshot = service.getSnapshot();

        assertThat(snapshot.reachable()).isTrue();
        assertThat(snapshot.version()).isEqualTo("8.0.39");
        assertThat(snapshot.uptimeSeconds()).isEqualTo(3600);
        assertThat(snapshot.connectionCount()).isEqualTo(5);
        assertThat(snapshot.databaseCount()).isEqualTo(2);
        assertThat(snapshot.totalStorageBytes()).isEqualTo(3072);
        assertThat(snapshot.mem().residentMb()).isEqualTo(300);
        assertThat(snapshot.mem().virtualMb()).isEqualTo(900);
        assertThat(snapshot.lock().queueTotal()).isEqualTo(1);
        assertThat(snapshot.lock().queueWriters()).isEqualTo(1);
        assertThat(snapshot.lock().activeClientsTotal()).isEqualTo(2);
        // No previous snapshot yet, so rates are unknown.
        assertThat(snapshot.ops()).isNull();
        assertThat(snapshot.network()).isNull();
    }

    @Test
    void secondSnapshotComputesPerSecondRates() {
        when(mongoDatabaseRepository.getServerStatus()).thenReturn(fullStatus(), statusAfter5s());
        stubSizes();

        service.getSnapshot();
        clock.advance(Duration.ofSeconds(5));
        MonitorSnapshot snapshot = service.getSnapshot();

        assertThat(snapshot.ops().insert()).isEqualTo(20);
        assertThat(snapshot.ops().query()).isEqualTo(40);
        assertThat(snapshot.ops().update()).isEqualTo(1);
        assertThat(snapshot.ops().delete()).isEqualTo(0);
        assertThat(snapshot.ops().command()).isEqualTo(10);
        assertThat(snapshot.network().bytesInPerSecond()).isEqualTo(100);
        assertThat(snapshot.network().bytesOutPerSecond()).isEqualTo(100);
    }

    @Test
    void counterResetYieldsZeroRatesAndRebaselines() {
        when(mongoDatabaseRepository.getServerStatus())
                .thenReturn(fullStatus(),
                        new Document("version", "8.0.39").append("uptime", 1L)
                                .append("connections", new Document("current", 5))
                                .append("opcounters", new Document("insert", 900)
                                        .append("query", 10).append("update", 1)
                                        .append("delete", 1).append("command", 100))
                                .append("network", new Document("bytesIn", 500).append("bytesOut", 500)));
        stubSizes();

        service.getSnapshot();
        clock.advance(Duration.ofSeconds(5));
        MonitorSnapshot snapshot = service.getSnapshot();

        assertThat(snapshot.ops().insert()).isZero();
        assertThat(snapshot.network().bytesInPerSecond()).isZero();

        // Re-baselined: a third tick should show real rates again.
        when(mongoDatabaseRepository.getServerStatus())
                .thenReturn(new Document("version", "8.0.39").append("uptime", 6L)
                        .append("connections", new Document("current", 5))
                        .append("opcounters", new Document("insert", 1000)
                                .append("query", 20).append("update", 2)
                                .append("delete", 2).append("command", 200))
                        .append("network", new Document("bytesIn", 600).append("bytesOut", 700)));
        clock.advance(Duration.ofSeconds(5));
        MonitorSnapshot after = service.getSnapshot();

        assertThat(after.ops().insert()).isEqualTo(20);
        assertThat(after.network().bytesInPerSecond()).isEqualTo(20);
    }

    @Test
    void degradedWhenServerStatusUnavailable() {
        doThrow(mongoError()).when(mongoDatabaseRepository).getServerStatus();
        stubSizes();

        MonitorSnapshot snapshot = service.getSnapshot();

        assertThat(snapshot.reachable()).isTrue();
        assertThat(snapshot.version()).isNull();
        assertThat(snapshot.connectionCount()).isNull();
        assertThat(snapshot.ops()).isNull();
        assertThat(snapshot.lock()).isNull();
        assertThat(snapshot.mem()).isNull();
        // Reachability-based metrics still work.
        assertThat(snapshot.databaseCount()).isEqualTo(2);
        assertThat(snapshot.totalStorageBytes()).isEqualTo(3072);
    }

    @Test
    void unreachableReturnsDegradedSnapshot() {
        doThrow(mongoError()).when(mongoDatabaseRepository).ping();

        MonitorSnapshot snapshot = service.getSnapshot();

        assertThat(snapshot.reachable()).isFalse();
        assertThat(snapshot.version()).isNull();
        assertThat(snapshot.databaseCount()).isZero();
        assertThat(snapshot.totalStorageBytes()).isNull();
        assertThat(snapshot.ops()).isNull();
        verify(mongoDatabaseRepository, never()).getDatabaseSizes();
    }

    // ── JSON serialization ──────────────────────────────────────────────

    @Test
    void serializeEmitsAllFields() {
        MonitorSnapshot snapshot = new MonitorSnapshot(true, START, "8.0.39", 3600L, 5, 2, 3072L,
                new MonitorSnapshot.OpsRate(20, 40, 1, 0, 10),
                new MonitorSnapshot.NetworkRate(100, 200),
                new MonitorSnapshot.MemUsage(300L, 900L),
                new MonitorSnapshot.LockQueue(1, 0, 1, 2, 1, 0));

        String json = service.serialize(snapshot);

        assertThat(json).contains("\"reachable\":true")
                .contains("\"version\":\"8.0.39\"")
                .contains("\"uptimeSeconds\":3600")
                .contains("\"connectionCount\":5")
                .contains("\"databaseCount\":2")
                .contains("\"totalStorageBytes\":3072")
                .contains("\"ops\":{\"insert\":20,\"query\":40,\"update\":1,\"delete\":0,\"command\":10}")
                .contains("\"network\":{\"bytesInPerSecond\":100,\"bytesOutPerSecond\":200}")
                .contains("\"mem\":{\"residentMb\":300,\"virtualMb\":900}")
                .contains("\"lock\":{\"queueTotal\":1,\"queueReaders\":0,\"queueWriters\":1")
                .contains("\"activeClientsTotal\":2");
    }

    @Test
    void serializeDegradedSnapshotUsesNulls() {
        MonitorSnapshot snapshot = new MonitorSnapshot(false, START, null, null, null, 0, null,
                null, null, null, null);

        String json = service.serialize(snapshot);

        assertThat(json).contains("\"reachable\":false")
                .contains("\"version\":null")
                .contains("\"uptimeSeconds\":null")
                .contains("\"ops\":null")
                .contains("\"network\":null")
                .contains("\"mem\":null")
                .contains("\"lock\":null");
    }

    /**
     * A {@link Clock} the test can advance to drive rate computation.
     */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}