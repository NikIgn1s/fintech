package dev.finflow.ledger.repository;

import dev.finflow.ledger.domain.AccountBalance;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AccountBalance b where b.accountId in :ids order by b.accountId")
    List<AccountBalance> lockAllByIdsOrdered(@Param("ids") Collection<Long> ids);

    @Query("select b.accountId as accountId, b.balance as balance from AccountBalance b where b.accountId in :ids")
    List<BalanceView> findViewsByAccountIdIn(@Param("ids") Collection<Long> ids);
}
