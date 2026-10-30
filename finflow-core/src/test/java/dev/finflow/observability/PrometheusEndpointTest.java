package dev.finflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.finflow.auth.domain.Role;
import dev.finflow.auth.security.AuthenticatedUser;
import dev.finflow.support.AbstractPostgresTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties="finflow.monitoring.token=test-monitoring-token")
@AutoConfigureMockMvc
@AutoConfigureObservability
class PrometheusEndpointTest extends AbstractPostgresTest {
    @Autowired
    private MockMvc mvc;

    private RequestPostProcessor principal(Role role) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(UUID.randomUUID(),role),null,
                List.of(new SimpleGrantedAuthority("ROLE_"+role.name()))));
    }

    @Test
    void adminCanScrapeJvmAndExistingBusinessMetricsWithApplicationLabel() throws Exception {
        var response=mvc.perform(get("/actuator/prometheus").with(principal(Role.ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getContentAsString())
                .contains("jvm_memory_used_bytes")
                .contains("ledger_reconciliation_mismatch")
                .contains("application="+Character.toString(34)+"finflow-core"+Character.toString(34));
    }

    @Test
    void metricsRequireAdministratorAccess() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(principal(Role.USER))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(principal(Role.ANALYST))).andExpect(status().isForbidden());
    }


    @Test
    void monitoringTokenCanOnlyReadPrometheus() throws Exception {
        mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer test-monitoring-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer wrong-monitoring-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/accounts").header("Authorization","Bearer test-monitoring-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherSensitiveActuatorEndpointsStayUnexposed() throws Exception {
        mvc.perform(get("/actuator/env").with(principal(Role.ADMIN))).andExpect(status().isNotFound());
    }
}
