package dev.finflow.ledger.api;

import dev.finflow.common.error.NotFoundException;
import dev.finflow.ledger.domain.ReconciliationReport;
import dev.finflow.ledger.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    @GetMapping
    public ReconciliationReport latest() {
        return reconciliation.latest()
                .orElseThrow(() -> new NotFoundException("Reconciliation has not been run yet"));
    }

    @PostMapping
    public ReconciliationReport run() {
        return reconciliation.run();
    }
}
