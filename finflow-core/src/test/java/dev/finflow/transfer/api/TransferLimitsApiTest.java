package dev.finflow.transfer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.support.TestApi;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"finflow.transfer.max-amount=1000", "finflow.transfer.daily-limit=1500"})
class TransferLimitsApiTest extends AbstractPostgresTest {

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
    void rejectsTransferAboveSingleTransferLimit() throws Exception {
        String alice = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(alice);
        api.topUp(alice, from, "5000");

        api.transfer(alice, from, to, "1000.01")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/transfer-limit-exceeded"));
        api.transfer(alice, from, to, "1000").andExpect(status().isCreated());
    }

    @Test
    void enforcesDailyLimitUpToExactlyTheLimit() throws Exception {
        String alice = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(alice);
        api.topUp(alice, from, "10000");

        api.transfer(alice, from, to, "1000").andExpect(status().isCreated());
        api.transfer(alice, from, to, "600")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/daily-limit-exceeded"));
        api.transfer(alice, from, to, "500").andExpect(status().isCreated());
        api.transfer(alice, from, to, "0.01").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rejectedAndFailedTransfersDoNotCountTowardsTheLimit() throws Exception {
        String alice = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(alice);
        api.topUp(alice, from, "10000");
        api.transfer(alice, from, to, "1000").andExpect(status().isCreated());
        api.transfer(alice, from, to, "500").andExpect(status().isCreated());
        api.transfer(alice, from, to, "100").andExpect(status().isUnprocessableEntity());

        jdbc.update("update transfers set status = 'FAILED' where from_account_id = ? and amount = 1000", from);

        api.transfer(alice, from, to, "1000").andExpect(status().isCreated());
    }

    @Test
    void limitIsPerUser() throws Exception {
        String alice = api.newUserToken();
        String bob = api.newUserToken();
        long aliceFrom = api.openAccount(alice);
        long aliceTo = api.openAccount(alice);
        long bobFrom = api.openAccount(bob);
        long bobTo = api.openAccount(bob);
        api.topUp(alice, aliceFrom, "5000");
        api.topUp(bob, bobFrom, "5000");

        api.transfer(alice, aliceFrom, aliceTo, "1000").andExpect(status().isCreated());
        api.transfer(alice, aliceFrom, aliceTo, "600").andExpect(status().isUnprocessableEntity());

        api.transfer(bob, bobFrom, bobTo, "1000").andExpect(status().isCreated());
    }

    @Test
    void parallelTransfersCannotSlipPastTheDailyLimit() throws Exception {
        String alice = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(alice);
        api.topUp(alice, from, "10000");
        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return api.transfer(alice, from, to, "400").andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdownNow();

        assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(3);
        assertThat(statuses.stream().filter(code -> code == 422).count()).isEqualTo(requests - 3);
        assertThat(api.balance(alice, from)).isEqualTo(10000.0 - 3 * 400);
    }
}
