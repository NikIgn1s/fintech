package dev.finflow.importing.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.common.error.NotFoundException;
import io.minio.MinioClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class StatementStorageTest {

    private static final String USER = "finflow";
    private static final String PASSWORD = "finflow-secret";
    private static final GenericContainer<?> MINIO = new GenericContainer<>("bitnamilegacy/minio:2025.7.23-debian-12-r3")
            .withEnv("MINIO_ROOT_USER", USER)
            .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    private static StatementStorage storage;

    @BeforeAll
    static void start() {
        MINIO.start();
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        StorageProperties properties = new StorageProperties(endpoint, USER, PASSWORD, "test-statements");
        MinioClient client = MinioClient.builder().endpoint(endpoint).credentials(USER, PASSWORD).build();
        storage = new StatementStorage(client, properties);
    }

    @AfterAll
    static void stop() {
        MINIO.stop();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void storesFileAndReadsItBack() throws IOException {
        byte[] content = bytes("date,description\n2026-10-14,Shop\n");
        UUID user = UUID.randomUUID();
        UUID importId = UUID.randomUUID();

        String key = storage.store(user, importId, "october.csv", content, "text/csv");

        assertThat(key).isEqualTo("statements/" + user + "/" + importId + "/october.csv");
        try (var stream = storage.open(key)) {
            assertThat(stream.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void neverLetsTheFileNameEscapeItsFolder() {
        UUID user = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        byte[] content = bytes("x");

        String key = storage.store(user, importId, "../../etc/passwd", content, null);
        String prefix = "statements/" + user + "/" + importId + "/";

        assertThat(key).isEqualTo(prefix + "passwd");
        assertThat(StatementStorage.safeName("..\\..\\win dows\\ev il?.csv")).isEqualTo("ev_il_.csv");
        assertThat(StatementStorage.safeName("...hidden")).isEqualTo("_hidden");
        assertThat(StatementStorage.safeName("   ")).isEqualTo("statement.csv");
        assertThat(StatementStorage.safeName(null)).isEqualTo("statement.csv");
        assertThat(StatementStorage.safeName("a".repeat(300) + ".csv")).hasSize(100).endsWith(".csv");
    }

    @Test
    void deletesFilesAndReportsMissingOnes() {
        byte[] content = bytes("temporary");
        String key = storage.store(UUID.randomUUID(), UUID.randomUUID(), "t.csv", content, null);

        storage.delete(key);

        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> storage.open("statements/none/none/none.csv")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void reportsUnavailableStorage() {
        MinioClient unreachable = MinioClient.builder().endpoint("http://127.0.0.1:1")
                .credentials(USER, PASSWORD).build();
        StatementStorage broken = new StatementStorage(unreachable,
                new StorageProperties("http://127.0.0.1:1", USER, PASSWORD, "x"));

        assertThatThrownBy(() -> broken.store(UUID.randomUUID(), UUID.randomUUID(), "a.csv", bytes("x"), null)).isInstanceOf(StorageUnavailableException.class);
        assertThatThrownBy(() -> broken.open("k")).isInstanceOf(StorageUnavailableException.class);
    }
}
