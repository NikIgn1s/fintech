package dev.finflow.transfer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finflow.common.money.Money;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.transfer.domain.Transfer;
import dev.finflow.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransferRepositoryTest extends AbstractPostgresTest {

    @Autowired
    private TransferRepository transfers;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private long from;
    private long to;

    private void fixtures() {
        user = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, full_name) values (?, ?, 'hash', 'Test')",
                user, user + "@example.com");
        from = account();
        to = account();
    }

    private long account() {
        return jdbc.queryForObject(
                "insert into accounts (user_id, type, currency) values (?, 'USER_WALLET', 'RUB') returning id",
                Long.class, user);
    }

    private Transfer saved() {
        fixtures();
        return transfers.save(Transfer.create(from, to, Money.of("250.75", "RUB"), "gift", user, Instant.now()));
    }

    @Test
    void persistsAndReloadsTransfer() {
        Transfer saved = saved();

        Transfer loaded = transfers.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.PENDING_CHECK);
        assertThat(loaded.getAmount()).isEqualByComparingTo("250.75");
        assertThat(loaded.getCurrency()).isEqualTo("RUB");
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getDescription()).isEqualTo("gift");
    }

    @Test
    void statusChangeBumpsVersion() {
        Transfer saved = saved();
        Transfer loaded = transfers.findById(saved.getId()).orElseThrow();

        loaded.hold("review", new BigDecimal("0.9100"), Instant.now());
        transfers.saveAndFlush(loaded);

        Transfer reloaded = transfers.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransferStatus.ON_HOLD);
        assertThat(reloaded.getFraudScore()).isEqualByComparingTo("0.91");
        assertThat(reloaded.getVersion()).isEqualTo(1);
    }

    @Test
    void concurrentStatusChangeLosesWithOptimisticLockFailure() {
        Transfer saved = saved();
        Transfer first = transfers.findById(saved.getId()).orElseThrow();
        Transfer second = transfers.findById(saved.getId()).orElseThrow();

        first.complete(new BigDecimal("0.1"), Instant.now());
        transfers.saveAndFlush(first);
        second.hold("late fraud result", new BigDecimal("0.9"), Instant.now());

        assertThatThrownBy(() -> transfers.saveAndFlush(second)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(transfers.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(TransferStatus.COMPLETED);
    }
}
