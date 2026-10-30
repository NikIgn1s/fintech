package dev.finflow.audit.api;

import dev.finflow.auth.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class AuditContext {
    private AuditContext() {}

    public static UUID userId() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        return authentication!=null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser user ? user.userId() : null;
    }

    public static String ip() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request
                ? request.getRequest().getRemoteAddr() : null;
    }
}
