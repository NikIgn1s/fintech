package dev.finflow.importing.repository;

import dev.finflow.importing.domain.StatementImport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementImportRepository extends JpaRepository<StatementImport, UUID> {

    Optional<StatementImport> findByIdAndUserId(UUID id, UUID userId);
}
