package dev.finflow.auth.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.finflow.auth.domain.Role;
import dev.finflow.common.config.ClockConfig;
import dev.finflow.common.error.GlobalExceptionHandler;
import dev.finflow.common.error.ProblemResponseWriter;
import dev.finflow.common.idempotency.IdempotencyStore;
import dev.finflow.common.ratelimit.RateLimiter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(SecurityConfigTest.ProbeController.class)
@EnableConfigurationProperties(JwtProperties.class)
@Import({SecurityConfig.class, ProblemAuthenticationEntryPoint.class, ProblemAccessDeniedHandler.class,
        ProblemResponseWriter.class, GlobalExceptionHandler.class, JwtService.class, ClockConfig.class,
        SecurityConfigTest.ProbeController.class})
@TestPropertySource(properties = "finflow.jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef")
class SecurityConfigTest {

    @MockBean
    private IdempotencyStore idempotencyStore;

    @MockBean
    private RateLimiter rateLimiter;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtService jwtService;

    private String bearer(Role role) {
        return "Bearer " + jwtService.generateAccessToken(UUID.randomUUID(), role);
    }

    @Test
    void publicPathsNeedNoToken() throws Exception {
        mvc.perform(get("/api/v1/auth/open")).andExpect(status().isOk());
    }

    @Test
    void protectedPathWithoutTokenReturnsProblem401() throws Exception {
        mvc.perform(get("/api/v1/secured"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/unauthorized"))
                .andExpect(jsonPath("$.instance").value("/api/v1/secured"));
    }

    @Test
    void invalidTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/secured").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenOpensProtectedPath() throws Exception {
        mvc.perform(get("/api/v1/secured").header("Authorization", bearer(Role.USER)))
                .andExpect(status().isOk());
    }

    @Test
    void userCannotReachAdminEndpoint() throws Exception {
        mvc.perform(get("/api/v1/admin-only").header("Authorization", bearer(Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/forbidden"));
    }

    @Test
    void adminReachesAdminEndpoint() throws Exception {
        mvc.perform(get("/api/v1/admin-only").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/auth/open")
        String open() {
            return "open";
        }

        @GetMapping("/api/v1/secured")
        String secured() {
            return "secured";
        }

        @GetMapping("/api/v1/admin-only")
        @PreAuthorize("hasRole('ADMIN')")
        String adminOnly() {
            return "admin";
        }
    }
}
