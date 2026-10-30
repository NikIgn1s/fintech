package dev.finflow.audit.service;

import dev.finflow.audit.api.AuditContext;
import dev.finflow.audit.api.AuditRecorder;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogService implements AuditRecorder {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(UUID actor,String entity,String entityId,String action,String reason) {
        jdbc.update("insert into audit_log(user_id,ip,entity_type,entity_id,action,reason,created_at) values (?,?,?,?,?,?,?)",
                actor,AuditContext.ip(),entity,entityId,action,reason,Timestamp.from(clock.instant()));
    }
}
