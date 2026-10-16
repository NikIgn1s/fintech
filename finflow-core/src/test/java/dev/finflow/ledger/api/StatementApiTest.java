package dev.finflow.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.support.TestUsers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class StatementApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long openAccount(String token) throws Exception {
        String response = mvc.perform(post("/api/v1/accounts").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"USER_WALLET\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private void cash(String path, String token, long accountId, String amount) throws Exception {
        mvc.perform(post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + accountId + ",\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    private JsonNode statement(String token, long accountId, String query) throws Exception {
        String body = mvc.perform(get("/api/v1/accounts/" + accountId + "/statement" + query)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void listsEntriesNewestFirstWithBalance() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);
        cash("/api/v1/top-ups", token, account, "100");
        cash("/api/v1/withdrawals", token, account, "30");

        JsonNode page = statement(token, account, "");

        assertThat(page.get("balance").decimalValue()).isEqualByComparingTo("70");
        assertThat(page.get("currency").asText()).isEqualTo("RUB");
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("type").asText()).isEqualTo("WITHDRAWAL");
        assertThat(page.get("items").get(0).get("amount").decimalValue()).isEqualByComparingTo("-30");
        assertThat(page.get("items").get(1).get("type").asText()).isEqualTo("TOP_UP");
        assertThat(page.hasNonNull("nextCursor")).isFalse();
    }

    @Test
    void pagesThroughHistoryWithCursor() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);
        for (int i = 1; i <= 5; i++) {
            cash("/api/v1/top-ups", token, account, String.valueOf(i));
        }

        List<Integer> amounts = new ArrayList<>();
        String query = "?limit=2";
        int pages = 0;
        while (true) {
            JsonNode page = statement(token, account, query);
            page.get("items").forEach(item -> amounts.add(item.get("amount").decimalValue().intValue()));
            pages++;
            if (!page.hasNonNull("nextCursor")) {
                break;
            }
            query = "?limit=2&after=" + page.get("nextCursor").asLong();
        }

        assertThat(pages).isEqualTo(3);
        assertThat(amounts).containsExactly(5, 4, 3, 2, 1);
    }

    @Test
    void filtersByTimeRange() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);
        cash("/api/v1/top-ups", token, account, "10");

        Instant future = Instant.now().plusSeconds(3600);

        assertThat(statement(token, account, "?from=" + future).get("items")).isEmpty();
        assertThat(statement(token, account, "?to=" + Instant.EPOCH).get("items")).isEmpty();
        assertThat(statement(token, account, "?from=" + Instant.EPOCH + "&to=" + future).get("items")).hasSize(1);
    }

    @Test
    void emptyAccountHasEmptyStatement() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);

        JsonNode page = statement(token, account, "");

        assertThat(page.get("items")).isEmpty();
        assertThat(page.hasNonNull("nextCursor")).isFalse();
    }

    @Test
    void foreignAccountStatementIsNotFound() throws Exception {
        String alice = TestUsers.registerAndLogin(mvc, objectMapper);
        String bob = TestUsers.registerAndLogin(mvc, objectMapper);
        long aliceAccount = openAccount(alice);

        mvc.perform(get("/api/v1/accounts/" + aliceAccount + "/statement").header("Authorization", bob))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsOutOfRangeLimit() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        long account = openAccount(token);

        mvc.perform(get("/api/v1/accounts/" + account + "/statement?limit=0").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/" + account + "/statement?limit=201").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/accounts/1/statement")).andExpect(status().isUnauthorized());
    }
}
