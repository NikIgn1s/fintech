package dev.finflow.common.idempotency;

import dev.finflow.auth.api.AuthenticatedUser;
import dev.finflow.common.error.ProblemResponseWriter;
import dev.finflow.common.error.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Slf4j
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Set<String> PROTECTED_PATHS = Set.of("/api/v1/transfers", "/api/v1/top-ups",
            "/api/v1/withdrawals");
    private static final Set<Integer> NOT_STORED_CLIENT_ERRORS = Set.of(400, 401, 403, 429);
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int ACQUIRE_ATTEMPTS = 2;

    private final IdempotencyStore store;
    private final ProblemResponseWriter problems;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PROTECTED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(KEY_HEADER);
        if (header == null || header.isBlank()) {
            reject(request, response, HttpStatus.BAD_REQUEST, "idempotency-key-required",
                    "Idempotency key required", "Header " + KEY_HEADER + " is required");
            return;
        }
        Optional<String> key = canonicalKey(header);
        if (key.isEmpty()) {
            reject(request, response, HttpStatus.BAD_REQUEST, "idempotency-key-invalid",
                    "Idempotency key invalid", "Header " + KEY_HEADER + " must be a UUID");
            return;
        }

        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(request, response, HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large",
                    "Payload too large", "Request body must not exceed " + MAX_BODY_BYTES + " bytes");
            return;
        }

        CachedBodyRequest cachedRequest = new CachedBodyRequest(request, body);
        String hash = fingerprint(request, body);
        UUID userId = user.userId();
        for (int attempt = 0; attempt < ACQUIRE_ATTEMPTS; attempt++) {
            if (store.tryAcquire(userId, key.get(), hash)) {
                execute(cachedRequest, response, chain, userId, key.get());
                return;
            }
            Optional<IdempotencyStore.StoredKey> stored = store.find(userId, key.get());
            if (stored.isEmpty()) {
                continue;
            }
            if (!stored.get().requestHash().equals(hash)) {
                reject(request, response, HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reused",
                        "Idempotency key reused", "The key was already used with a different request");
                return;
            }
            if (stored.get().responseStatus() == null) {
                rejectInProgress(request, response);
                return;
            }
            replay(stored.get(), response);
            return;
        }
        rejectInProgress(request, response);
    }

    private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain, UUID userId,
            String key) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapped);
        } catch (IOException | ServletException | RuntimeException e) {
            store.release(userId, key);
            throw e;
        }
        persistOutcome(userId, key, wrapped);
        wrapped.copyBodyToResponse();
    }

    private void persistOutcome(UUID userId, String key, ContentCachingResponseWrapper wrapped) {
        int status = wrapped.getStatus();
        try {
            if (isStorable(status)) {
                byte[] content = wrapped.getContentAsByteArray();
                String body = content.length == 0 ? null : new String(content, StandardCharsets.UTF_8);
                store.complete(userId, key, status, body);
            } else {
                store.release(userId, key);
            }
        } catch (RuntimeException e) {
            log.error("Could not persist idempotent response for key {}", key, e);
            store.release(userId, key);
        }
    }

    private boolean isStorable(int status) {
        return status >= 200 && status < 500 && !NOT_STORED_CLIENT_ERRORS.contains(status);
    }

    private void replay(IdempotencyStore.StoredKey stored, HttpServletResponse response) throws IOException {
        int status = stored.responseStatus();
        response.setStatus(status);
        response.setHeader(REPLAYED_HEADER, "true");
        if (stored.responseBody() != null) {
            response.setContentType(status >= 400 ? "application/problem+json" : "application/json");
            response.setCharacterEncoding("UTF-8");
            response.getOutputStream().write(stored.responseBody().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void rejectInProgress(HttpServletRequest request, HttpServletResponse response) throws IOException {
        reject(request, response, HttpStatus.CONFLICT, "request-in-progress", "Request in progress",
                "A request with this idempotency key is still being processed");
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
            String title, String detail) throws IOException {
        problems.write(request, response, Problems.of(status, code, title, detail));
    }

    private Optional<String> canonicalKey(String header) {
        try {
            return Optional.of(UUID.fromString(header.trim()).toString());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String fingerprint(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String target = request.getMethod() + " " + request.getRequestURI()
                    + (request.getQueryString() == null ? "" : "?" + request.getQueryString()) + "\n";
            digest.update(target.getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
