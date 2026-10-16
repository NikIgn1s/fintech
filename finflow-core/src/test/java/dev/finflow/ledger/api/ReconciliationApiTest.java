package dev.finflow.ledger.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.support.AbstractPostgresTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationApiTest extends AbstractPostgresTest {

    private static final String PASSWORD = "correct-horse";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private String tokenFor(String role) throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Test\"}"))
                .andExpect(status().isCreated());
        jdbc.update("update users set role = ? where email = ?", role, email);
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).get("accessToken").asText();
    }

    @Test
    void adminRunsReconciliationAndReadsLatest() throws Exception {
        String admin = tokenFor("ADMIN");

        String run = mvc.perform(post("/api/v1/admin/reconciliation").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mismatchCount").isNumber())
                .andExpect(jsonPath("$.mismatches").isArray())
                .andExpect(jsonPath("$.startedAt").exists())
                .andReturn().getResponse().getContentAsString();
        long runId = objectMapper.readTree(run).get("id").asLong();

        mvc.perform(get("/api/v1/admin/reconciliation").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId));
    }

    @Test
    void regularUserAndAnalystAreForbidden() throws Exception {
        for (String role : new String[] {"USER", "ANALYST"}) {
            String token = tokenFor(role);

            mvc.perform(get("/api/v1/admin/reconciliation").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/reconciliation").header("Authorization", token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/admin/reconciliation")).andExpect(status().isUnauthorized());
    }
}
