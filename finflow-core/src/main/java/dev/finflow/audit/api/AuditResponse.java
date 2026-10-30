package dev.finflow.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AuditResponse(List<Revision> revisions,List<Action> actions,
        long totalRevisions,long totalActions,int page,int size) {
    public record Revision(long revision,Instant occurredAt,UUID userId,String ip,String type,JsonNode state) {}
    public record Action(long id,UUID userId,String ip,String action,String reason,Instant occurredAt) {}
}
