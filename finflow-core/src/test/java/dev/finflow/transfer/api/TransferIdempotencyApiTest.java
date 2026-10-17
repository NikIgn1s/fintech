package dev.finflow.transfer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.support.TestApi;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TransferIdempotencyApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, objectMapper);
    }

    @Test
    void parallelIdenticalRequestsCreateExactlyOneTransfer() throws Exception {
        String alice = api.newUserToken();
        String bob = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(bob);
        api.topUp(alice, from, "1000");
        String key = UUID.randomUUID().toString();
        int requests = 8;

        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return api.transfer(alice, key, from, to, "100").andReturn().getResponse().getStatus();
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
        assertThat(api.balance(alice, from)).isEqualTo(900.0);
        Integer transfers = jdbc.queryForObject("select count(*) from transfers where from_account_id = ?",
                Integer.class, from);
        Integer reserves = jdbc.queryForObject("""
                select count(*) from ledger_transactions lt join transfers t on t.id = lt.reference_id
                where lt.type = 'TRANSFER_RESERVE' and t.from_account_id = ?
                """, Integer.class, from);
        assertThat(transfers).isEqualTo(1);
        assertThat(reserves).isEqualTo(1);
    }

    @Test
    void sequentialRetryReturnsTheOriginalTransfer() throws Exception {
        String alice = api.newUserToken();
        String bob = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(bob);
        api.topUp(alice, from, "1000");
        String key = UUID.randomUUID().toString();

        String original = api.transfer(alice, key, from, to, "250").andReturn().getResponse().getContentAsString();
        String retried = api.transfer(alice, key, from, to, "250").andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(retried).get("id")).isEqualTo(objectMapper.readTree(original).get("id"));
        assertThat(api.balance(alice, from)).isEqualTo(750.0);
    }

    @Test
    void sameKeyWithDifferentAmountIsRejectedAndNothingIsReserved() throws Exception {
        String alice = api.newUserToken();
        String bob = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(bob);
        api.topUp(alice, from, "1000");
        String key = UUID.randomUUID().toString();
        api.transfer(alice, key, from, to, "100");

        int status = api.transfer(alice, key, from, to, "900").andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(422);
        assertThat(api.balance(alice, from)).isEqualTo(900.0);
    }

    @Test
    void distinctKeysCreateDistinctTransfers() throws Exception {
        String alice = api.newUserToken();
        String bob = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(bob);
        api.topUp(alice, from, "1000");

        api.transfer(alice, from, to, "100");
        api.transfer(alice, from, to, "100");

        assertThat(api.balance(alice, from)).isEqualTo(800.0);
        assertThat(api.getJson(alice, "/api/v1/transfers?direction=out").get("items")).hasSize(2);
    }
}
