package dev.finflow.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.auth.domain.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";

    private JwtService service(String secret, Clock clock) {
        return new JwtService(new JwtProperties(secret, Duration.ofMinutes(15), Duration.ofDays(7), "finflow"), clock);
    }

    @Test
    void roundTripsUserIdAndRole() {
        JwtService service = service(SECRET, Clock.systemUTC());
        UUID userId = UUID.randomUUID();

        String token = service.generateAccessToken(userId, Role.ANALYST);

        assertThat(service.parse(token)).contains(new AuthenticatedUser(userId, Role.ANALYST));
    }

    @Test
    void rejectsTamperedToken() {
        JwtService service = service(SECRET, Clock.systemUTC());
        String token = service.generateAccessToken(UUID.randomUUID(), Role.USER);

        assertThat(service.parse(token.substring(0, token.length() - 2) + "xx")).isEmpty();
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        String token = service(SECRET, Clock.systemUTC()).generateAccessToken(UUID.randomUUID(), Role.USER);
        JwtService other = service("another-secret-another-secret-another-secret-1234", Clock.systemUTC());

        assertThat(other.parse(token)).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String token = service(SECRET, past).generateAccessToken(UUID.randomUUID(), Role.USER);

        assertThat(service(SECRET, Clock.systemUTC()).parse(token)).isEmpty();
    }

    @Test
    void rejectsGarbage() {
        assertThat(service(SECRET, Clock.systemUTC()).parse("not-a-jwt")).isEmpty();
    }

    @Test
    void refusesShortSecret() {
        assertThatThrownBy(() -> service("short", Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
    }
}
