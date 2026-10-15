package dev.finflow.auth.api;

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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private String registerBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"fullName\":\"Alice Smith\"}";
    }

    private String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private void register(String email, String password) throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, password)))
                .andExpect(status().isCreated());
    }

    @Test
    void registersUserWithoutExposingPassword() throws Exception {
        String email = uniqueEmail();

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email.toUpperCase(), "correct-horse")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void rejectsDuplicateEmail() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse");

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, "correct-horse")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/email-taken"));
    }

    @Test
    void rejectsInvalidRegistration() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("not-an-email", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/validation-failed"))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void loginIssuesTokenThatOpensProtectedEndpoint() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse");

        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "correct-horse")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);

        mvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + body.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse");

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/invalid-credentials"));
    }

    @Test
    void loginForUnknownUserLooksTheSame() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(uniqueEmail(), "whatever-123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/invalid-credentials"));
    }

    @Test
    void profileRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }
}
