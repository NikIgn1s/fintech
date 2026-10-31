package dev.finflow.auth.security;

import dev.finflow.auth.api.AuthenticatedUser;

import dev.finflow.auth.api.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;
    private static final String ROLE_CLAIM = "role";

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey key;

    public JwtService(JwtProperties properties, Clock clock) {
        byte[] secret = properties.secret() == null ? new byte[0] : properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("finflow.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        this.properties = properties;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String generateAccessToken(UUID userId, Role role) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTtl())))
                .signWith(key)
                .compact();
    }

    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            UUID userId = UUID.fromString(claims.getSubject());
            Role role = Role.valueOf(claims.get(ROLE_CLAIM, String.class));
            return Optional.of(new AuthenticatedUser(userId, role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Duration refreshTtl() {
        return properties.refreshTtl();
    }

    public Duration accessTtl() {
        return properties.accessTtl();
    }
}
