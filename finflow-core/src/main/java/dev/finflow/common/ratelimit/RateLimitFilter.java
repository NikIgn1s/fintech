package dev.finflow.common.ratelimit;

import dev.finflow.auth.security.AuthenticatedUser;
import dev.finflow.common.error.ProblemResponseWriter;
import dev.finflow.common.error.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, RateLimitGroup> GROUPS = Map.of(
            "/api/v1/transfers", RateLimitGroup.TRANSFERS,
            "/api/v1/top-ups", RateLimitGroup.CASH,
            "/api/v1/withdrawals", RateLimitGroup.CASH);

    private final RateLimiter rateLimiter;
    private final ProblemResponseWriter problems;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !GROUPS.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            chain.doFilter(request, response);
            return;
        }
        RateLimitDecision decision = rateLimiter.check(user.userId(), GROUPS.get(request.getRequestURI()));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        problems.write(request, response, Problems.of(HttpStatus.TOO_MANY_REQUESTS, "rate-limit-exceeded",
                "Too many requests", "Rate limit exceeded, retry in " + decision.retryAfterSeconds() + " seconds"));
    }
}
