package dev.finflow.categorization.repository;

import dev.finflow.categorization.domain.CategoryRule;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule,Long> {
    Optional<CategoryRule> findByIdAndUserId(long id,UUID userId);
}
