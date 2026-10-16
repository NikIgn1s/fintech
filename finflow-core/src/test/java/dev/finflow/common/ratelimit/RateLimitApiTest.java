package dev.finflow.common.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.support.TestApi;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "finflow.rate-limit.transfers-per-minute=3",
        "finflow.rate-limit.cash-per-minute=5"})
class RateLimitApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, objectMapper);
    }

    private ResultActions transferToNowhere(String token) throws Exception {
        return api.transfer(token, UUID.randomUUID().toString(), 987654321L, 987654322L, "10");
    }

    private ResultActions topUpToNowhere(String token) throws Exception {
        return mvc.perform(post("/api/v1/top-ups").header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":987654321,\"amount\":10}"));
    }

    @Test
    void blocksTransfersAfterTheLimitWithRetryAfter() throws Exception {
        String alice = api.newUserToken();

        for (int i = 0; i < 3; i++) {
            transferToNowhere(alice).andExpect(status().isNotFound());
        }

        transferToNowhere(alice)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/rate-limit-exceeded"));
    }

    @Test
    void limitIsPerUser() throws Exception {
        String alice = api.newUserToken();
        String bob = api.newUserToken();
        for (int i = 0; i < 4; i++) {
            transferToNowhere(alice);
        }

        transferToNowhere(alice).andExpect(status().isTooManyRequests());
        transferToNowhere(bob).andExpect(status().isNotFound());
    }

    @Test
    void cashEndpointsHaveTheirOwnBudgetSharedBetweenTopUpsAndWithdrawals() throws Exception {
        String alice = api.newUserToken();
        for (int i = 0; i < 3; i++) {
            transferToNowhere(alice).andExpect(status().isNotFound());
        }
        transferToNowhere(alice).andExpect(status().isTooManyRequests());

        for (int i = 0; i < 5; i++) {
            topUpToNowhere(alice).andExpect(status().isNotFound());
        }
        topUpToNowhere(alice).andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/v1/withdrawals").header("Authorization", alice)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":1,\"amount\":10}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void requestsWithoutIdempotencyKeyStillConsumeTheBudget() throws Exception {
        String alice = api.newUserToken();
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/transfers").header("Authorization", alice)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }

        transferToNowhere(alice).andExpect(status().isTooManyRequests());
    }

    @Test
    void readEndpointsAreNeverLimited() throws Exception {
        String alice = api.newUserToken();
        for (int i = 0; i < 5; i++) {
            transferToNowhere(alice);
        }

        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/v1/transfers").header("Authorization", alice)).andExpect(status().isOk());
        }
    }

    @Test
    void unauthenticatedRequestsAreRejectedByAuthenticationNotLimiter() throws Exception {
        for (int i = 0; i < 6; i++) {
            mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
