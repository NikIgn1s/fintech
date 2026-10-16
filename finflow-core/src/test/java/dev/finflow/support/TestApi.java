package dev.finflow.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

public final class TestApi {

    private final MockMvc mvc;
    private final ObjectMapper objectMapper;

    public TestApi(MockMvc mvc, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.objectMapper = objectMapper;
    }

    public String newUserToken() throws Exception {
        return TestUsers.registerAndLogin(mvc, objectMapper);
    }

    public long openAccount(String token) throws Exception {
        return openAccount(token, "RUB");
    }

    public long openAccount(String token, String currency) throws Exception {
        String body = mvc.perform(post("/api/v1/accounts").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"USER_WALLET\",\"currency\":\"" + currency + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    public void topUp(String token, long accountId, String amount) throws Exception {
        mvc.perform(post("/api/v1/top-ups").header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + accountId + ",\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    public ResultActions transfer(String token, String key, long from, long to, String amount) throws Exception {
        return mvc.perform(post("/api/v1/transfers").header("Authorization", token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":" + from + ",\"toAccountId\":" + to + ",\"amount\":" + amount
                        + ",\"description\":\"test\"}"));
    }

    public ResultActions transfer(String token, long from, long to, String amount) throws Exception {
        return transfer(token, UUID.randomUUID().toString(), from, to, amount);
    }

    public JsonNode createTransfer(String token, long from, long to, String amount) throws Exception {
        String body = transfer(token, from, to, amount).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    public double balance(String token, long accountId) throws Exception {
        String body = mvc.perform(get("/api/v1/accounts/" + accountId).header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("balance").asDouble();
    }

    public JsonNode getJson(String token, String path) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
