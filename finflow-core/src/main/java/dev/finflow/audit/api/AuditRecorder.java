package dev.finflow.audit.api;

import java.util.UUID;

public interface AuditRecorder {
    void record(UUID actor,String entity,String entityId,String action,String reason);
}
