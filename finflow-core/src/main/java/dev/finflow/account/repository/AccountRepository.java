package dev.finflow.account.repository;

import dev.finflow.account.domain.Account;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByUserIdOrderById(UUID userId);

    Optional<Account> findByIdAndUserId(Long id, UUID userId);

    long countByUserId(UUID userId);
}
