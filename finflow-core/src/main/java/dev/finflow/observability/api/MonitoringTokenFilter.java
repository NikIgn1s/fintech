package dev.finflow.observability.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class MonitoringTokenFilter extends OncePerRequestFilter {
    private final byte[] token;

    public MonitoringTokenFilter(String token) {
        this.token=token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String authorization=request.getHeader("Authorization");
        if (request.getRequestURI().equals(request.getContextPath()+"/actuator/prometheus") && token.length>0
                && authorization!=null && authorization.startsWith("Bearer ")
                && MessageDigest.isEqual(token,authorization.substring(7).getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "prometheus",null,List.of(new SimpleGrantedAuthority("ROLE_METRICS"))));
        }
        chain.doFilter(request,response);
    }
}
