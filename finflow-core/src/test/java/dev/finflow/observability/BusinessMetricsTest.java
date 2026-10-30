package dev.finflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.account.service.AccountService;
import dev.finflow.auth.repository.UserRepository;
import dev.finflow.ledger.service.CashService;
import dev.finflow.transfer.service.*;
import dev.finflow.transfer.messaging.FraudFlowFixtures;
import dev.finflow.transfer.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.ApplicationEventPublisher;

@SpringBootTest
class BusinessMetricsTest extends AbstractPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired AccountService accounts;
    @Autowired CashService cash;
    @Autowired TransferService transfers;
    @Autowired FraudDecisionService decisions;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEventPublisher events;
    @Autowired MeterRegistry metrics;

    @Test
    void exportsCommittedDatabaseBacklogsAndPendingAge() {
        var fixtures=new FraudFlowFixtures(users,accounts,cash,transfers,jdbc,mapper);
        var pending=fixtures.pendingTransfer("10");
        jdbc.update("update transfers set created_at=now()-interval '3 minutes' where id=?",pending.transfer().id());
        var registry=new SimpleMeterRegistry();
        new BusinessMetrics(jdbc).bindTo(registry);
        assertThat(registry.get("pending_check_oldest_seconds").gauge().value()).isGreaterThanOrEqualTo(180);
        assertThat(registry.get("outbox_unpublished_events").gauge().value()).isGreaterThan(0);
        assertThat(registry.get("transfers").tag("status","PENDING_CHECK").gauge().value()).isGreaterThan(0);
        decisions.apply(new FraudVerdict(pending.transfer().id(),FraudDecision.HOLD,FraudSource.ML,
                new BigDecimal("0.8"),"test",List.of("test"),null));
        var refreshed=new SimpleMeterRegistry();
        new BusinessMetrics(jdbc).bindTo(refreshed);
        assertThat(refreshed.get("transfers_hold_ratio").gauge().value()).isBetween(0.0,1.0);
        assertThat(refreshed.get("transfers").tag("status","ON_HOLD").gauge().value()).isGreaterThan(0);
    }

    @Test
    void countersObserveOnlyCommittedTransactions() {
        var counter=metrics.counter("transfers.created","status","PENDING_CHECK");
        double before=counter.count();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            events.publishEvent(new TransferMetrics.Created());
            status.setRollbackOnly();
        });
        assertThat(counter.count()).isEqualTo(before);
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(new TransferMetrics.Created()));
        assertThat(counter.count()).isEqualTo(before+1);
    }
}
