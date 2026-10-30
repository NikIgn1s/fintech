package dev.finflow.categorization.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MlCategorizerClient {
    public record Item(String id, String description, BigDecimal amount, String mcc) {}
    public record Request(List<Item> items) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(String id, String category, BigDecimal confidence) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(List<Result> results) {}

    private final RestClient client;
    private final CircuitBreaker breaker = CircuitBreaker.of("categorizer",
            CircuitBreakerConfig.custom().minimumNumberOfCalls(5).slidingWindowSize(10)
                    .waitDurationInOpenState(Duration.ofSeconds(30)).build());
    private final Retry retry = Retry.of("categorizer",
            RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(100)).build());

    public MlCategorizerClient(@Value("${finflow.ml.url:http://localhost:8000}") String url,
            @Value("${finflow.ml.api-key:}") String apiKey) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        client = RestClient.builder().baseUrl(url).defaultHeader("X-API-Key", apiKey)
                .requestFactory(factory).build();
    }

    public List<Result> categorize(List<Item> items) {
        if (items.isEmpty()) return List.of();
        try {
            return breaker.executeSupplier(() -> retry.executeSupplier(() -> {
                Response response = client.post().uri("/categorize").body(new Request(items))
                        .retrieve().body(Response.class);
                if (response == null || response.results() == null) {
                    throw new IllegalStateException("Empty categorization response");
                }
                return response.results().stream().filter(java.util.Objects::nonNull).toList();
            }));
        } catch (RuntimeException error) {
            return List.of();
        }
    }
}
