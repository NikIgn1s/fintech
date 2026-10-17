package dev.finflow.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class RefreshTokenFlowTest extends AbstractPostgresTest {

    private static final String PASSWORD = "correct-horse";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private JsonNode registerAndLogin() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Alice\"}"))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private ResultActions refresh(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + token + "\"}"));
    }

    private ResultActions logout(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + token + "\"}"));
    }

    @Test
    void loginReturnsRefreshToken() throws Exception {
        JsonNode tokens = registerAndLogin();

        assertThat(tokens.get("refreshToken").asText()).hasSizeGreaterThan(30);
    }

    @Test
    void refreshRotatesTokenAndIssuesWorkingAccessToken() throws Exception {
        JsonNode first = registerAndLogin();

        String body = refresh(first.get("refreshToken").asText())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode second = objectMapper.readTree(body);

        assertThat(second.get("refreshToken").asText()).isNotEqualTo(first.get("refreshToken").asText());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + second.get("accessToken").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void reusingRotatedTokenRevokesWholeFamily() throws Exception {
        JsonNode first = registerAndLogin();
        String body = refresh(first.get("refreshToken").asText()).andReturn().getResponse().getContentAsString();
        String latest = objectMapper.readTree(body).get("refreshToken").asText();

        refresh(first.get("refreshToken").asText())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/invalid-refresh-token"));

        refresh(latest).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesToken() throws Exception {
        JsonNode tokens = registerAndLogin();
        String refreshToken = tokens.get("refreshToken").asText();

        logout(refreshToken).andExpect(status().isNoContent());

        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutOfUnknownTokenIsIdempotent() throws Exception {
        logout("unknown-token").andExpect(status().isNoContent());
    }

    @Test
    void rejectsUnknownToken() throws Exception {
        refresh("unknown-token").andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        JsonNode tokens = registerAndLogin();
        jdbc.update("update refresh_tokens set expires_at = now() - interval '1 minute'");

        refresh(tokens.get("refreshToken").asText()).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsBlankToken() throws Exception {
        refresh("").andExpect(status().isBadRequest());
    }
}
