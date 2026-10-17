package dev.finflow.auth.service;

import dev.finflow.auth.domain.RefreshToken;
import dev.finflow.auth.repository.RefreshTokenRepository;
import dev.finflow.auth.security.JwtProperties;
import dev.finflow.common.error.ApiException;
import dev.finflow.common.id.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository tokens;
    private final JwtProperties properties;
    private final Clock clock;

    public record Rotation(UUID userId, String refreshToken) {
    }

    @Transactional
    public String issue(UUID userId) {
        return issueInFamily(userId, UuidV7.generate());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken) {
        RefreshToken current = tokens.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);
        Instant now = clock.instant();
        if (current.isRevoked()) {
            tokens.revokeFamily(current.getFamilyId(), now);
            throw new InvalidRefreshTokenException();
        }
        if (current.isExpiredAt(now)) {
            throw new InvalidRefreshTokenException();
        }
        current.revoke(now);
        String next = issueInFamily(current.getUserId(), current.getFamilyId());
        return new Rotation(current.getUserId(), next);
    }

    @Transactional
    public void revoke(String rawToken) {
        tokens.findByTokenHashForUpdate(hash(rawToken))
                .ifPresent(token -> tokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    private String issueInFamily(UUID userId, UUID familyId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        tokens.save(RefreshToken.issue(userId, familyId, hash(rawToken), now, now.plus(properties.refreshTtl())));
        return rawToken;
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
