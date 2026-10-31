package dev.finflow.audit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.account.service.AccountService;
import dev.finflow.audit.service.AuditQueryService;
import dev.finflow.auth.api.Role;
import dev.finflow.auth.repository.UserRepository;
import dev.finflow.auth.api.AuthenticatedUser;
import dev.finflow.ledger.service.CashService;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.transfer.domain.FraudDecision;
import dev.finflow.transfer.domain.FraudSource;
import dev.finflow.transfer.messaging.FraudFlowFixtures;
import dev.finflow.transfer.service.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class AuditTest extends AbstractPostgresTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditQueryService history;
    @Autowired UserRepository users;
    @Autowired AccountService accounts;
    @Autowired CashService cash;
    @Autowired TransferService transfers;
    @Autowired FraudDecisionService decisions;
    @Autowired TransferReviewService reviews;
    @Autowired PlatformTransactionManager transactions;

    private String json(String source) {
        return source.replace("'",Character.toString(34));
    }

    private UUID user() {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into users(id,email,password_hash,full_name) values (?,?,'hash','Test')",id,id+"@test.dev");
        return id;
    }

    private RequestPostProcessor principal(UUID user,Role role) {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(user,role),null,
                List.of(new SimpleGrantedAuthority("ROLE_"+role.name()))));
    }

    private FraudFlowFixtures.Pending held() {
        var fixtures=new FraudFlowFixtures(users,accounts,cash,transfers,jdbc,mapper);
        var pending=fixtures.pendingTransfer("10");
        decisions.apply(new FraudVerdict(pending.transfer().id(),FraudDecision.HOLD,FraudSource.ML,
                new BigDecimal("0.8"),"test",List.of("suspicious"),null));
        return pending;
    }

    @Test
    void reviewerDecisionHasRevisionActorIpAndAtomicBusinessLog() throws Exception {
        UUID analyst=user();
        var pending=held();
        UUID id=pending.transfer().id();
        mvc.perform(post("/api/v1/admin/transfers/"+id+"/reject").with(principal(analyst,Role.ANALYST))
                        .with(request -> { request.setRemoteAddr("192.0.2.10"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':'confirmed fraud'}")))
                .andExpect(status().isOk());
        var result=history.history("transfer",id.toString(),0,20);
        assertThat(result.revisions()).hasSize(3);
        var revision=result.revisions().getFirst();
        assertThat(revision.userId()).isEqualTo(analyst);
        assertThat(revision.ip()).isEqualTo("192.0.2.10");
        assertThat(revision.type()).isEqualTo("MOD");
        assertThat(revision.state().get("status").asText()).isEqualTo("REJECTED");
        assertThat(result.revisions().getLast().userId()).isNull();
        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().action()).isEqualTo("REJECT");
        assertThat(result.actions().getFirst().reason()).isEqualTo("confirmed fraud");
        assertThat(result.actions().getFirst().userId()).isEqualTo(analyst);
        assertThat(result.actions().getFirst().ip()).isEqualTo("192.0.2.10");
        mvc.perform(post("/api/v1/admin/transfers/"+id+"/reject").with(principal(analyst,Role.ANALYST))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':'again'}")))
                .andExpect(status().isConflict());
        assertThat(history.history("transfer",id.toString(),0,20).totalActions()).isEqualTo(1);
    }

    @Test
    void rollbackDoesNotLeaveAuditOrStatusChanges() {
        UUID analyst=user();
        UUID id=held().transfer().id();
        long revisions=history.history("transfer",id.toString(),0,20).totalRevisions();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            reviews.approve(analyst,id);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        var result=history.history("transfer",id.toString(),0,20);
        assertThat(result.totalRevisions()).isEqualTo(revisions);
        assertThat(result.totalActions()).isZero();
        assertThat(jdbc.queryForObject("select status from transfers where id=?",String.class,id)).isEqualTo("ON_HOLD");
    }

    @Test
    void budgetsAndRuleDeletionsAreAuditedWithSnapshots() throws Exception {
        UUID owner=user();
        short category=jdbc.queryForObject("select id from categories where code='CAFE'",Short.class);
        var auth=principal(owner,Role.USER);
        String body=mvc.perform(post("/api/v1/category-rules").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'pattern':'coffee','categoryId':"+category+",'priority':1}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long rule=mapper.readTree(body).get("id").asLong();
        mvc.perform(delete("/api/v1/category-rules/"+rule).with(auth)).andExpect(status().isNoContent());
        var ruleHistory=history.history("category-rule",Long.toString(rule),0,20);
        assertThat(ruleHistory.revisions()).hasSize(2);
        assertThat(ruleHistory.revisions().getFirst().type()).isEqualTo("DEL");
        assertThat(ruleHistory.revisions().getFirst().state().get("pattern").asText()).isEqualTo("coffee");
        assertThat(ruleHistory.revisions().getFirst().userId()).isEqualTo(owner);
        body=mvc.perform(put("/api/v1/budgets").param("month","2026-10").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'categoryId':"+category+",'currency':'RUB','limit':100}")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long budget=mapper.readTree(body).get("id").asLong();
        mvc.perform(put("/api/v1/budgets").param("month","2026-10").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'categoryId':"+category+",'currency':'RUB','limit':200}")))
                .andExpect(status().isOk());
        var budgetHistory=history.history("budget",Long.toString(budget),0,20);
        assertThat(budgetHistory.revisions()).hasSize(2);
        assertThat(budgetHistory.revisions().getFirst().state().get("amount_limit").decimalValue())
                .isEqualByComparingTo("200");
    }

    @Test
    void accountCreationAndHistoryAccessAreAuditedAndAuthorized() throws Exception {
        UUID owner=user();
        String body=mvc.perform(post("/api/v1/accounts").with(principal(owner,Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'type':'USER_WALLET','currency':'RUB'}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(body).get("id").asText();
        assertThat(history.history("account",id,0,20).revisions().getFirst().userId()).isEqualTo(owner);
        mvc.perform(get("/api/v1/admin/audit").param("entity","account").param("id",id))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audit").param("entity","account").param("id",id)
                        .with(principal(owner,Role.USER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit").param("entity","account").param("id",id)
                        .with(principal(owner,Role.ADMIN))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevisions").value(1));
        mvc.perform(get("/api/v1/admin/audit").param("entity","transfers;drop table users").param("id",id)
                        .with(principal(owner,Role.ANALYST))).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/admin/audit").param("entity","transfer").param("id","invalid")
                        .with(principal(owner,Role.ANALYST))).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/admin/audit").param("entity","account").param("id",id).param("size","101")
                        .with(principal(owner,Role.ANALYST))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void businessAuditIsAppendOnly() {
        UUID analyst=user();
        UUID id=held().transfer().id();
        reviews.approve(analyst,id);
        assertThatThrownBy(() -> jdbc.update("update audit_log set reason='tamper' where entity_id=?",id.toString()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from audit_log where entity_id=?",id.toString()))
                .isInstanceOf(DataAccessException.class);
        assertThat(history.history("transfer",id.toString(),0,20).totalActions()).isEqualTo(1);
    }
}
