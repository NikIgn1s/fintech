package dev.finflow.ledger.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.support.TestUsers;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class CashApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long openAccount(String token, String currency) throws Exception {
        String response = mvc.perform(post("/api/v1/accounts").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"USER_WALLET\",\"currency\":\"" + currency + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private ResultActions cash(String path, String token, long accountId, String amount) throws Exception {
        return mvc.perform(post(path).header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":" + accountId + ",\"amount\":" + amount + "}"));
    }

    @Test
    void topUpIncreasesBalance() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token, "RUB");

        cash("/api/v1/top-ups", token, account, "1500.50")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TOP_UP"))
                .andExpect(jsonPath("$.balance").value(1500.5))
                .andExpect(jsonPath("$.id").exists());

        mvc.perform(get("/api/v1/accounts/" + account).header("Authorization", token))
                .andExpect(jsonPath("$.balance").value(1500.5));
    }

    @Test
    void withdrawalDecreasesBalance() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token, "RUB");
        cash("/api/v1/top-ups", token, account, "1000").andExpect(status().isCreated());

        cash("/api/v1/withdrawals", token, account, "300.25")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.balance").value(699.75));
    }

    @Test
    void withdrawalBeyondBalanceIsRejectedAndBalanceUntouched() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token, "RUB");
        cash("/api/v1/top-ups", token, account, "100").andExpect(status().isCreated());

        cash("/api/v1/withdrawals", token, account, "100.01")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/insufficient-funds"));

        mvc.perform(get("/api/v1/accounts/" + account).header("Authorization", token))
                .andExpect(jsonPath("$.balance").value(100));
    }

    @Test
    void worksForForeignCurrencyAccounts() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token, "USD");

        cash("/api/v1/top-ups", token, account, "25")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void cannotTouchForeignAccount() throws Exception {
        String alice = TestUsers.registerAndLogin(mvc, objectMapper);
        String bob = TestUsers.registerAndLogin(mvc, objectMapper);
        long aliceAccount = openAccount(alice, "RUB");

        cash("/api/v1/top-ups", bob, aliceAccount, "10").andExpect(status().isNotFound());
        cash("/api/v1/withdrawals", bob, aliceAccount, "10").andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidAmounts() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token, "RUB");

        cash("/api/v1/top-ups", token, account, "0").andExpect(status().isBadRequest());
        cash("/api/v1/top-ups", token, account, "-5").andExpect(status().isBadRequest());
        cash("/api/v1/top-ups", token, account, "1.234").andExpect(status().isBadRequest());
    }

    @Test
    void accountListShowsBalances() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long first = openAccount(token, "RUB");
        openAccount(token, "RUB");
        cash("/api/v1/top-ups", token, first, "42").andExpect(status().isCreated());

        mvc.perform(get("/api/v1/accounts").header("Authorization", token))
                .andExpect(jsonPath("$[0].balance").value(42))
                .andExpect(jsonPath("$[1].balance").value(0));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/top-ups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1,\"amount\":10}"))
                .andExpect(status().isUnauthorized());
    }
}
