package dev.finflow.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.auth.api.Role;
import dev.finflow.auth.domain.User;
import dev.finflow.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest extends AbstractPostgresTest {

    @Autowired
    private UserRepository users;

    @Test
    void savesAndFindsByNormalizedEmail() {
        users.saveAndFlush(User.register("  Alice@Example.COM ", "hash", " Alice "));

        User found = users.findByEmail("alice@example.com").orElseThrow();

        assertThat(found.getFullName()).isEqualTo("Alice");
        assertThat(found.getRole()).isEqualTo(Role.USER);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(users.existsByEmail("alice@example.com")).isTrue();
    }

    @Test
    void rejectsDuplicateEmail() {
        users.saveAndFlush(User.register("bob@example.com", "hash", "Bob"));

        assertThatThrownBy(() -> users.saveAndFlush(User.register("bob@example.com", "hash", "Bob Two")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
