package dev.finflow.budget.repository;

import dev.finflow.budget.domain.BudgetEntity;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<BudgetEntity,Long> {
    Optional<BudgetEntity> findByUserIdAndCategoryIdAndMonthAndCurrency(
            UUID userId,short categoryId,LocalDate month,String currency);
}
