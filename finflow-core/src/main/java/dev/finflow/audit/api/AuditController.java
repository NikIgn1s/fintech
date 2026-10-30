package dev.finflow.audit.api;

import dev.finflow.audit.service.AuditQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/audit")
@PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
@RequiredArgsConstructor
public class AuditController {
    private final AuditQueryService service;

    @GetMapping
    public AuditResponse history(@RequestParam String entity,@RequestParam String id,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.history(entity,id,page,size);
    }
}
