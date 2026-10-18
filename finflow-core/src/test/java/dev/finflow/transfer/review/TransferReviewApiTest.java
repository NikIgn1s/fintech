package dev.finflow.transfer.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.account.service.AccountService;
import dev.finflow.auth.repository.UserRepository;
import dev.finflow.ledger.service.CashService;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.transfer.domain.FraudDecision;
import dev.finflow.transfer.domain.FraudSource;
import dev.finflow.transfer.messaging.FraudFlowFixtures;
import dev.finflow.transfer.service.FraudDecisionService;
import dev.finflow.transfer.service.FraudVerdict;
import dev.finflow.transfer.service.TransferService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class TransferReviewApiTest extends AbstractPostgresTest {

    private static final String PASSWORD = "correct-horse";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FraudDecisionService decisions;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccountService accounts;

    @Autowired
    private CashService cash;

    @Autowired
    private TransferService transfers;

    private FraudFlowFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new FraudFlowFixtures(users, accounts, cash, transfers, jdbc, objectMapper);
    }

    private String tokenWithRole(String role) throws Exception {
        String email = "staff-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"S\"}"))
                .andExpect(status().isCreated());
        jdbc.update("update users set role = ? where email = ?", role, email);
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).get("accessToken").asText();
    }

    private FraudFlowFixtures.Pending heldTransfer(String amount, String score) {
        var pending = fixtures.pendingTransfer(amount);
        decisions.apply(new FraudVerdict(pending.transfer().id(), FraudDecision.HOLD, FraudSource.ML,
                new BigDecimal(score), "m-1", List.of("suspicious pattern"), null));
        return pending;
    }

    private ResultActions approve(String token, UUID id) throws Exception {
        return mvc.perform(post("/api/v1/admin/transfers/" + id + "/approve").header("Authorization", token));
    }

    private ResultActions reject(String token, UUID id, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/transfers/" + id + "/reject").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void queueShowsHeldTransfersHighestScoreFirst() throws Exception {
        String analyst = tokenWithRole("ANALYST");
        UUID low = heldTransfer("10", "0.7100").transfer().id();
        UUID high = heldTransfer("10", "0.9900").transfer().id();
        UUID pending = fixtures.pendingTransfer("10").transfer().id();

        String body = mvc.perform(get("/api/v1/admin/transfers?size=100").header("Authorization", analyst))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode items = objectMapper.readTree(body).get("items");
        List<String> ids = new ArrayList<>();
        items.forEach(item -> {
            assertThat(item.get("status").asText()).isEqualTo("ON_HOLD");
            ids.add(item.get("id").asText());
        });

        assertThat(ids).contains(low.toString(), high.toString()).doesNotContain(pending.toString());
        assertThat(ids.indexOf(high.toString())).isLessThan(ids.indexOf(low.toString()));
        JsonNode first = items.get(ids.indexOf(high.toString()));
        assertThat(first.get("holdReason").asText()).isEqualTo("suspicious pattern");
        assertThat(first.get("fraudScore").decimalValue()).isEqualByComparingTo("0.99");
    }

    @Test
    void onlyStaffCanUseTheQueue() throws Exception {
        UUID held = heldTransfer("10", "0.8").transfer().id();
        String user = tokenWithRole("USER");

        mvc.perform(get("/api/v1/admin/transfers").header("Authorization", user)).andExpect(status().isForbidden());
        approve(user, held).andExpect(status().isForbidden());
        reject(user, held, "{\"reason\":\"no\"}").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/transfers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/transfers").header("Authorization", tokenWithRole("ADMIN")))
                .andExpect(status().isOk());
        assertThat(fixtures.status(held)).isEqualTo("ON_HOLD");
    }

    @Test
    void approvalReleasesMoneyToTheRecipient() throws Exception {
        String analyst = tokenWithRole("ANALYST");
        var held = heldTransfer("250", "0.8800");
        UUID id = held.transfer().id();

        approve(analyst, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(fixtures.balance(held.to().id())).isEqualByComparingTo("250");
        assertThat(fixtures.settleCount(id)).isEqualTo(1);
        Map<String, Object> review = jdbc.queryForMap("select * from transfer_reviews where transfer_id = ?", id);
        assertThat(review.get("decision")).isEqualTo("APPROVE");
        assertThat((BigDecimal) review.get("fraud_score")).isEqualByComparingTo("0.88");
        String event = jdbc.queryForObject("select payload::text from outbox_events where aggregate_id = ? "
                + "and topic = 'transfers.status-changed' order by created_at desc, id desc limit 1",
                String.class, id.toString());
        assertThat(objectMapper.readTree(event).get("payload").get("decidedBy").asText()).isEqualTo("ANALYST");
    }

    @Test
    void rejectionReturnsEverythingToTheSender() throws Exception {
        String analyst = tokenWithRole("ANALYST");
        var held = heldTransfer("400", "0.9500");
        UUID id = held.transfer().id();
        BigDecimal senderBefore = fixtures.balance(held.from().id());

        reject(analyst, id, "{\"reason\":\"confirmed fraud\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(fixtures.balance(held.from().id())).isEqualByComparingTo(senderBefore.add(new BigDecimal("400")));
        assertThat(fixtures.balance(held.to().id())).isEqualByComparingTo("0");
        assertThat(fixtures.count("select count(*) from ledger_transactions where type = 'TRANSFER_REFUND' "
                + "and reference_id = ?", id)).isEqualTo(1);
        Map<String, Object> review = jdbc.queryForMap("select * from transfer_reviews where transfer_id = ?", id);
        assertThat(review.get("decision")).isEqualTo("REJECT");
        assertThat(review.get("reason")).isEqualTo("confirmed fraud");
        assertThat(jdbc.queryForObject("select hold_reason from transfers where id = ?", String.class, id))
                .isEqualTo("suspicious pattern");
    }

    @Test
    void rejectionNeedsAReason() throws Exception {
        String analyst = tokenWithRole("ANALYST");
        UUID id = heldTransfer("10", "0.8").transfer().id();

        reject(analyst, id, "{\"reason\":\"  \"}").andExpect(status().isBadRequest());
        reject(analyst, id, "{}").andExpect(status().isBadRequest());
        assertThat(fixtures.status(id)).isEqualTo("ON_HOLD");
    }

    @Test
    void onlyHeldTransfersCanBeReviewedAndOnlyOnce() throws Exception {
        String analyst = tokenWithRole("ANALYST");
        UUID pending = fixtures.pendingTransfer("10").transfer().id();
        UUID held = heldTransfer("10", "0.8").transfer().id();

        approve(analyst, pending).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/illegal-transfer-state"));
        approve(analyst, held).andExpect(status().isOk());
        approve(analyst, held).andExpect(status().isConflict());
        reject(analyst, held, "{\"reason\":\"late\"}").andExpect(status().isConflict());
        approve(analyst, UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(fixtures.settleCount(held)).isEqualTo(1);
    }

    @Test
    void simultaneousDecisionsApplyExactlyOnce() throws Exception {
        String analyst = tokenWithRole("ANALYST");
        var held = heldTransfer("100", "0.9");
        UUID id = held.transfer().id();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        results.add(pool.submit(() -> {
            start.await();
            return approve(analyst, id).andReturn().getResponse().getStatus();
        }));
        results.add(pool.submit(() -> {
            start.await();
            return reject(analyst, id, "{\"reason\":\"no\"}").andReturn().getResponse().getStatus();
        }));
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdownNow();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(fixtures.count("select count(*) from transfer_reviews where transfer_id = ?", id)).isEqualTo(1);
        boolean approveWon = statuses.get(0) == 200;
        assertThat(fixtures.balance(held.to().id())).isEqualByComparingTo(approveWon ? "100" : "0");
        assertThat(fixtures.status(id)).isEqualTo(approveWon ? "COMPLETED" : "REJECTED");
    }
}
