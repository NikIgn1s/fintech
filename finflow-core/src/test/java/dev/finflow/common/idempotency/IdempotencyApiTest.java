package dev.finflow.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private long openAccount(String token) throws Exception {
        String response = mvc.perform(post("/api/v1/accounts").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"USER_WALLET\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private ResultActions request(String path, String token, String key, long accountId, String amount)
            throws Exception {
        var builder = post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":" + accountId + ",\"amount\":" + amount + "}");
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return mvc.perform(builder);
    }

    private ResultActions topUp(String token, String key, long accountId, String amount) throws Exception {
        return request("/api/v1/top-ups", token, key, accountId, amount);
    }

    private double balance(String token, long accountId) throws Exception {
        String body = mvc.perform(get("/api/v1/accounts/" + accountId).header("Authorization", token))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("balance").asDouble();
    }

    private String newKey() {
        return UUID.randomUUID().toString();
    }

    @Test
    void requiresIdempotencyKeyOnProtectedEndpoints() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);

        topUp(token, null, account, "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/idempotency-key-required"));
        request("/api/v1/withdrawals", token, null, account, "10").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsKeyThatIsNotUuid() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);

        topUp(token, "not-a-uuid", account, "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/idempotency-key-invalid"));
    }

    @Test
    void replaysStoredResponseWithoutRepeatingTheOperation() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);
        String key = newKey();

        MvcResult first = topUp(token, key, account, "100").andExpect(status().isCreated()).andReturn();
        MvcResult second = topUp(token, key, account, "100")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(objectMapper.readTree(second.getResponse().getContentAsString()))
                .isEqualTo(objectMapper.readTree(first.getResponse().getContentAsString()));
        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(balance(token, account)).isEqualTo(100.0);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);
        String key = newKey();
        topUp(token, key, account, "100").andExpect(status().isCreated());

        topUp(token, key, account, "200")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/idempotency-key-reused"));

        assertThat(balance(token, account)).isEqualTo(100.0);
    }

    @Test
    void sameKeyOnDifferentEndpointIsRejected() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);
        String key = newKey();
        topUp(token, key, account, "100").andExpect(status().isCreated());

        request("/api/v1/withdrawals", token, key, account, "100")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void keysAreScopedPerUser() throws Exception {
        String alice = TestUsers.registerAndLogin(mvc, objectMapper);
        String bob = TestUsers.registerAndLogin(mvc, objectMapper);
        long aliceAccount = openAccount(alice);
        long bobAccount = openAccount(bob);
        String key = newKey();

        topUp(alice, key, aliceAccount, "10").andExpect(status().isCreated());
        topUp(bob, key, bobAccount, "10")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        assertThat(balance(bob, bobAccount)).isEqualTo(10.0);
    }

    @Test
    void businessErrorIsStoredAndReplayed() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);
        String key = newKey();

        request("/api/v1/withdrawals", token, key, account, "100").andExpect(status().isUnprocessableEntity());
        topUp(token, newKey(), account, "500").andExpect(status().isCreated());

        request("/api/v1/withdrawals", token, key, account, "100")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/insufficient-funds"));
        assertThat(balance(token, account)).isEqualTo(500.0);
    }

    @Test
    void validationErrorReleasesTheKeyForCorrectedRetry() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);
        String key = newKey();

        topUp(token, key, account, "0").andExpect(status().isBadRequest());

        topUp(token, key, account, "25")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(balance(token, account)).isEqualTo(25.0);
    }

    @Test
    void notFoundIsStoredAsBusinessOutcome() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        String key = newKey();

        topUp(token, key, 987654321L, "10").andExpect(status().isNotFound());

        topUp(token, key, 987654321L, "10")
                .andExpect(status().isNotFound())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void freshInProgressRecordBlocksDuplicate() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);
        String key = newKey();
        MvcResult probe = topUp(token, key, account, "10").andReturn();
        jdbc.update("update idempotency_keys set response_status = null, response_body = null where key = ?", key);

        topUp(token, key, account, "10")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/request-in-progress"));
        assertThat(probe.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void staleInProgressRecordIsReclaimed() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);
        String key = newKey();
        topUp(token, key, account, "10").andExpect(status().isCreated());
        jdbc.update("update idempotency_keys set response_status = null, response_body = null, "
                + "created_at = now() - interval '10 minutes' where key = ?", key);

        topUp(token, key, account, "10")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    void parallelDuplicatesExecuteOnlyOnce() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);
        String key = newKey();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return topUp(token, key, account, "100").andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdownNow();

        assertThat(statuses).allMatch(status -> status == 201 || status == 409);
        assertThat(statuses).contains(201);
        assertThat(balance(token, account)).isEqualTo(100.0);
        Integer stored = jdbc.queryForObject("select count(*) from idempotency_keys where key = ?", Integer.class, key);
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void unprotectedRequestsNeedNoKey() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);

        mvc.perform(get("/api/v1/accounts").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/accounts").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"USER_WALLET\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void cleanupRemovesOnlyExpiredKeys() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);
        String oldKey = newKey();
        String freshKey = newKey();
        topUp(token, oldKey, account, "1").andExpect(status().isCreated());
        topUp(token, freshKey, account, "2").andExpect(status().isCreated());
        jdbc.update("update idempotency_keys set created_at = now() - interval '25 hours' where key = ?", oldKey);

        new IdempotencyCleanupJob(new IdempotencyStore(jdbc), java.time.Clock.systemUTC()).cleanup();

        Integer oldCount = jdbc.queryForObject("select count(*) from idempotency_keys where key = ?", Integer.class,
                oldKey);
        Integer freshCount = jdbc.queryForObject("select count(*) from idempotency_keys where key = ?", Integer.class,
                freshKey);
        assertThat(oldCount).isZero();
        assertThat(freshCount).isEqualTo(1);
    }
}
