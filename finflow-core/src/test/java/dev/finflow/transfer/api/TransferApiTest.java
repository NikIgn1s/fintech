package dev.finflow.transfer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.support.TestApi;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TransferApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, objectMapper);
    }

    @Test
    void createsPendingTransferAndReservesFunds() throws Exception {
        String alice = api.newUserToken();
        String bob = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(bob);
        api.topUp(alice, from, "1000");

        api.transfer(alice, from, to, "250.50")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CHECK"))
                .andExpect(jsonPath("$.amount").value(250.5))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.fromAccountId").value(from))
                .andExpect(jsonPath("$.toAccountId").value(to))
                .andExpect(jsonPath("$.fraudScore").doesNotExist())
                .andExpect(jsonPath("$.holdReason").doesNotExist());

        assertThat(api.balance(alice, from)).isEqualTo(749.5);
        assertThat(api.balance(bob, to)).isZero();
    }

    @Test
    void sameIdempotencyKeyReservesOnlyOnce() throws Exception {
        String alice = api.newUserToken();
        String bob = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(bob);
        api.topUp(alice, from, "1000");
        String key = UUID.randomUUID().toString();

        String first = api.transfer(alice, key, from, to, "100").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = api.transfer(alice, key, from, to, "100").andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("id")).isEqualTo(objectMapper.readTree(first).get("id"));
        assertThat(api.balance(alice, from)).isEqualTo(900.0);
    }

    @Test
    void requiresIdempotencyKey() throws Exception {
        String alice = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(alice);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/transfers")
                        .header("Authorization", alice).contentType("application/json")
                        .content("{\"fromAccountId\":" + from + ",\"toAccountId\":" + to + ",\"amount\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/idempotency-key-required"));
    }

    @Test
    void rejectsInvalidPayloads() throws Exception {
        String alice = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(alice);

        api.transfer(alice, from, to, "0").andExpect(status().isBadRequest());
        api.transfer(alice, from, to, "-5").andExpect(status().isBadRequest());
        api.transfer(alice, from, to, "1.234").andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/transfers")
                        .header("Authorization", alice).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"fromAccountId\":" + from + ",\"amount\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/validation-failed"));
    }

    @Test
    void reportsInsufficientFunds() throws Exception {
        String alice = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(alice);
        api.topUp(alice, from, "50");

        api.transfer(alice, from, to, "50.01")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/insufficient-funds"));
        assertThat(api.balance(alice, from)).isEqualTo(50.0);
    }

    @Test
    void cannotSpendFromForeignAccount() throws Exception {
        String alice = api.newUserToken();
        String mallory = api.newUserToken();
        long aliceAccount = api.openAccount(alice);
        long malloryAccount = api.openAccount(mallory);
        api.topUp(alice, aliceAccount, "100");

        api.transfer(mallory, aliceAccount, malloryAccount, "100").andExpect(status().isNotFound());
        assertThat(api.balance(alice, aliceAccount)).isEqualTo(100.0);
    }

    @Test
    void senderAndRecipientCanReadTransferButOthersCannot() throws Exception {
        String alice = api.newUserToken();
        String bob = api.newUserToken();
        String carol = api.newUserToken();
        long from = api.openAccount(alice);
        long to = api.openAccount(bob);
        api.topUp(alice, from, "100");
        String id = api.createTransfer(alice, from, to, "10").get("id").asText();

        mvc.perform(get("/api/v1/transfers/" + id).header("Authorization", alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/v1/transfers/" + id).header("Authorization", bob))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/transfers/" + id).header("Authorization", carol))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/transfers/" + UUID.randomUUID()).header("Authorization", alice))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/transfers/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void historyIsSplitByDirection() throws Exception {
        String alice = api.newUserToken();
        String bob = api.newUserToken();
        long aliceAccount = api.openAccount(alice);
        long bobAccount = api.openAccount(bob);
        api.topUp(alice, aliceAccount, "1000");
        api.createTransfer(alice, aliceAccount, bobAccount, "10");
        api.createTransfer(alice, aliceAccount, bobAccount, "20");

        assertThat(api.getJson(alice, "/api/v1/transfers?direction=out").get("items")).hasSize(2);
        assertThat(api.getJson(alice, "/api/v1/transfers?direction=in").get("items")).isEmpty();
        assertThat(api.getJson(bob, "/api/v1/transfers?direction=in").get("items")).hasSize(2);
        assertThat(api.getJson(bob, "/api/v1/transfers?direction=out").get("items")).isEmpty();
        assertThat(api.getJson(bob, "/api/v1/transfers").get("items")).hasSize(2);
        assertThat(api.getJson(alice, "/api/v1/transfers?status=PENDING_CHECK").get("items")).hasSize(2);
        assertThat(api.getJson(alice, "/api/v1/transfers?status=COMPLETED").get("items")).isEmpty();
    }

    @Test
    void historyPagesWithKeysetCursorNewestFirst() throws Exception {
        String alice = api.newUserToken();
        String bob = api.newUserToken();
        long aliceAccount = api.openAccount(alice);
        long bobAccount = api.openAccount(bob);
        api.topUp(alice, aliceAccount, "1000");
        for (int i = 1; i <= 5; i++) {
            api.createTransfer(alice, aliceAccount, bobAccount, String.valueOf(i));
        }

        List<Integer> amounts = new ArrayList<>();
        String query = "/api/v1/transfers?direction=out&limit=2";
        int pages = 0;
        while (true) {
            JsonNode page = api.getJson(alice, query);
            page.get("items").forEach(item -> amounts.add(item.get("amount").decimalValue().intValue()));
            pages++;
            if (!page.hasNonNull("nextCursor")) {
                break;
            }
            query = "/api/v1/transfers?direction=out&limit=2&after=" + page.get("nextCursor").asText();
        }

        assertThat(pages).isEqualTo(3);
        assertThat(amounts).containsExactly(5, 4, 3, 2, 1);
    }

    @Test
    void historyRejectsBadParameters() throws Exception {
        String alice = api.newUserToken();

        mvc.perform(get("/api/v1/transfers?direction=sideways").header("Authorization", alice))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/transfers?limit=0").header("Authorization", alice))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/transfers?after=not-a-uuid").header("Authorization", alice))
                .andExpect(status().isBadRequest());
    }
}
