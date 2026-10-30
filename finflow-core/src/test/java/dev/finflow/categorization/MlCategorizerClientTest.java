package dev.finflow.categorization;

import static org.assertj.core.api.Assertions.assertThat;
import com.sun.net.httpserver.HttpServer;
import dev.finflow.categorization.client.MlCategorizerClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MlCategorizerClientTest {
    @Test
    void retriesTemporaryFailureAndSendsApiKey() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        var attempts = new AtomicInteger();
        server.createContext("/categorize", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-API-Key")).isEqualTo("secret");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("description");
            int status = attempts.incrementAndGet() < 3 ? 503 : 200;
            byte[] body = (status == 200
                    ? "{\"modelVersion\":\"v1\",\"results\":[{\"id\":\"1\",\"category\":\"CAFE\",\"confidence\":0.9,\"top3\":[]}]}"
                    : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new MlCategorizerClient("http://localhost:" + server.getAddress().getPort(), "secret");
            var result = client.categorize(List.of(new MlCategorizerClient.Item("1", "coffee", BigDecimal.TEN, null)));
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().category()).isEqualTo("CAFE");
            assertThat(attempts).hasValue(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unavailableServiceLeavesRowsUncategorized() {
        var client = new MlCategorizerClient("http://127.0.0.1:1", "secret");
        assertThat(client.categorize(List.of(new MlCategorizerClient.Item("1", "coffee", BigDecimal.TEN, null))))
                .isEmpty();
    }
}
