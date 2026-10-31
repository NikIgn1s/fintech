package dev.finflow.auth.domain;

import dev.finflow.auth.api.Role;

import dev.finflow.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Entity;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static User register(String email, String passwordHash, String fullName) {
        User user = new User();
        user.id = UuidV7.generate();
        user.email = normalizeEmail(email);
        user.passwordHash = passwordHash;
        user.fullName = fullName.trim();
        user.role = Role.USER;
        user.createdAt = Instant.now();
        return user;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
