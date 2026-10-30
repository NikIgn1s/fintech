package dev.finflow.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.audit.api.AuditResponse;
import dev.finflow.common.error.BusinessRuleException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditQueryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional(readOnly=true)
    public AuditResponse history(String entity,String id,int page,int size) {
        if (page<0 || size<1 || size>100) throw invalid("Invalid pagination");
        String table=switch(entity) {
            case "transfer" -> "transfers_aud";
            case "account" -> "accounts_aud";
            case "budget" -> "budgets_aud";
            case "category-rule" -> "category_rules_aud";
            default -> throw invalid("Unknown entity type");
        };
        Object key;
        try {
            key=entity.equals("transfer") ? UUID.fromString(id) : Long.valueOf(id);
            if (key instanceof Long number && number<=0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw invalid("Invalid entity identifier");
        }
        long offset=(long)page*size;
        var revisions=jdbc.query("select a.rev,a.revtype,r.revtstmp,r.user_id,r.ip,"
                + "(to_jsonb(a)-'rev'-'revtype')::text state from "+table+" a join revinfo r on r.rev=a.rev "
                + "where a.id=? order by a.rev desc limit ? offset ?",
                (rs,n) -> new AuditResponse.Revision(rs.getLong("rev"),
                        Instant.ofEpochMilli(rs.getLong("revtstmp")),rs.getObject("user_id",UUID.class),
                        rs.getString("ip"),switch(rs.getShort("revtype")) {
                            case 0 -> "ADD"; case 1 -> "MOD"; case 2 -> "DEL";
                            default -> "UNKNOWN";
                        },parse(rs.getString("state"))),key,size,offset);
        String canonicalId=key.toString();
        var actions=jdbc.query("select * from audit_log where entity_type=? and entity_id=? "
                + "order by id desc limit ? offset ?",
                (rs,n) -> new AuditResponse.Action(rs.getLong("id"),rs.getObject("user_id",UUID.class),
                        rs.getString("ip"),rs.getString("action"),rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()),entity,canonicalId,size,offset);
        long totalRevisions=jdbc.queryForObject("select count(*) from "+table+" where id=?",Long.class,key);
        long totalActions=jdbc.queryForObject("select count(*) from audit_log where entity_type=? and entity_id=?",
                Long.class,entity,canonicalId);
        return new AuditResponse(revisions,actions,totalRevisions,totalActions,page,size);
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read audit snapshot",error);
        }
    }

    private BusinessRuleException invalid(String detail) {
        return new BusinessRuleException("invalid-audit-query","Invalid audit query",detail);
    }
}
