package dev.finflow.audit.domain;

import dev.finflow.audit.api.AuditContext;
import org.hibernate.envers.RevisionListener;

public class AuditRevisionListener implements RevisionListener {
    @Override
    public void newRevision(Object entity) {
        AuditRevision revision=(AuditRevision)entity;
        revision.setUserId(AuditContext.userId());
        revision.setIp(AuditContext.ip());
    }
}
