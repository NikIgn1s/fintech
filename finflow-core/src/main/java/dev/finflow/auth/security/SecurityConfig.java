package dev.finflow.auth.security;

import dev.finflow.common.error.ProblemResponseWriter;
import dev.finflow.common.idempotency.IdempotencyFilter;
import dev.finflow.common.idempotency.IdempotencyStore;
import dev.finflow.common.ratelimit.RateLimitFilter;
import dev.finflow.common.ratelimit.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/**",
            "/actuator/health/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
            ProblemAuthenticationEntryPoint entryPoint, ProblemAccessDeniedHandler accessDeniedHandler,
            IdempotencyStore idempotencyStore, RateLimiter rateLimiter,
            ProblemResponseWriter problemResponseWriter,
            @org.springframework.beans.factory.annotation.Value("${finflow.monitoring.token:}") String monitoringToken) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/actuator/prometheus").hasAnyRole("ADMIN", "METRICS")
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new dev.finflow.observability.api.MonitoringTokenFilter(monitoringToken),
                        JwtAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiter, problemResponseWriter),
                        JwtAuthenticationFilter.class)
                .addFilterAfter(new IdempotencyFilter(idempotencyStore, problemResponseWriter),
                        RateLimitFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
