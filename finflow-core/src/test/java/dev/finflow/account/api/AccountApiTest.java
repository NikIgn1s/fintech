package dev.finflow.account.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class AccountApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private ResultActions open(String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/accounts").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long openWallet(String token) throws Exception {
        String response = open(token, "{\"type\":\"USER_WALLET\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    @Test
    void opensWalletWithDefaultCurrency() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);

        open(token, "{\"type\":\"USER_WALLET\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("USER_WALLET"))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    void opensSavingsInChosenCurrency() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);

        open(token, "{\"type\":\"USER_SAVINGS\",\"currency\":\"USD\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void rejectsSystemAccountType() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);

        open(token, "{\"type\":\"SYSTEM_GATEWAY\"}").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedCurrency() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);

        open(token, "{\"type\":\"USER_WALLET\",\"currency\":\"rub\"}").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnsupportedCurrency() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);

        open(token, "{\"type\":\"USER_WALLET\",\"currency\":\"JPY\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/unsupported-currency"));
    }

    @Test
    void listsOnlyOwnAccounts() throws Exception {
        String alice = TestUsers.registerAndLogin(mvc, objectMapper);
        String bob = TestUsers.registerAndLogin(mvc, objectMapper);
        openWallet(alice);
        openWallet(alice);
        openWallet(bob);

        mvc.perform(get("/api/v1/accounts").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void foreignAccountLooksNonexistent() throws Exception {
        String alice = TestUsers.registerAndLogin(mvc, objectMapper);
        String bob = TestUsers.registerAndLogin(mvc, objectMapper);
        long aliceAccount = openWallet(alice);

        mvc.perform(get("/api/v1/accounts/" + aliceAccount).header("Authorization", alice))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/accounts/" + aliceAccount).header("Authorization", bob))
                .andExpect(status().isNotFound());
    }

    @Test
    void enforcesAccountLimit() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        for (int i = 0; i < 10; i++) {
            openWallet(token);
        }

        open(token, "{\"type\":\"USER_WALLET\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/account-limit-reached"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
    }
}
