package dev.finflow.analytics;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.finflow.analytics.repository.AnalyticsRepository;
import dev.finflow.analytics.service.AnalyticsService;
import dev.finflow.analytics.service.AnalyticsRefreshService;
import dev.finflow.common.error.BusinessRuleException;
import dev.finflow.support.AbstractPostgresTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsTest extends AbstractPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalyticsRepository repository;
    @Autowired AnalyticsService service;
    @Autowired AnalyticsRefreshService refresh;
    @Autowired MockMvc mvc;

    private UUID user() {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into users(id,email,password_hash,full_name) values (?,?,'hash','Test')",id,id+"@test.dev");
        return id;
    }

    private short category(String code) {
        return jdbc.queryForObject("select id from categories where code=?",Short.class,code);
    }

    private void row(UUID user,String date,String amount,String currency,Short category,String merchant) {
        jdbc.update("insert into bank_transactions(user_id,operation_date,amount,currency,category_id,merchant,"
                + "raw_description,dedup_hash) values (?,?,?,?,?,?,'test',?)",
                user,LocalDate.parse(date),new BigDecimal(amount),currency,category,merchant,UUID.randomUUID().toString());
    }

    @Test
    void rollupSeparatesUncategorizedExpensesFromTotalsAndIsolatesUsersAndCurrencies() {
        UUID user=user();
        row(user,"2026-09-10","-20","RUB",category("CAFE"),"Cafe");
        row(user,"2026-09-11","-30","RUB",null,"Unknown");
        row(user,"2026-09-12","200","RUB",category("CAFE"),"Income");
        row(user,"2026-10-01","-100","RUB",null,"Outside range");
        row(user,"2026-09-12","-900","USD",null,"Foreign");
        row(user(),"2026-09-12","-500","RUB",null,"Other user");
        var rows=service.spending(user,LocalDate.of(2026,9,1),LocalDate.of(2026,10,1),"RUB","category");
        assertThat(rows).hasSize(4);
        assertThat(rows.stream().filter(r -> r.level().equals("CATEGORY") && r.categoryId()==null).findFirst().orElseThrow().spent())
                .isEqualByComparingTo("30");
        assertThat(rows.stream().filter(r -> r.level().equals("TOTAL")).findFirst().orElseThrow().spent())
                .isEqualByComparingTo("50");
        assertThat(service.spending(user,LocalDate.of(2026,9,1),LocalDate.of(2026,10,1),"RUB","month"))
                .hasSize(2);
    }

    @Test
    void trendsFillMissingCalendarMonthsAndAvoidDivisionByZero() {
        UUID user=user();
        short cafe=category("CAFE");
        row(user,"2026-07-01","-60","RUB",cafe,"Cafe");
        row(user,"2026-09-01","-120","RUB",cafe,"Cafe");
        var trends=repository.trends(user,LocalDate.of(2026,8,1),LocalDate.of(2026,11,1),"RUB");
        assertThat(trends).hasSize(3);
        assertThat(trends.get(0).spent()).isEqualByComparingTo("0");
        assertThat(trends.get(0).previousMonth()).isEqualByComparingTo("60");
        assertThat(trends.get(0).changePercent()).isEqualByComparingTo("-100");
        assertThat(trends.get(1).changePercent()).isNull();
        assertThat(trends.get(1).movingAverage3m()).isEqualByComparingTo("60");
        assertThat(trends.get(2).previousMonth()).isEqualByComparingTo("120");
    }

    @Test
    void topMerchantsAreRankedWithinCategoriesAndKeepTies() {
        UUID user=user();
        short cafe=category("CAFE");
        for (int i=1;i<=6;i++) row(user,"2026-10-01",Integer.toString(-i*10),"RUB",cafe,"Cafe "+i);
        row(user,"2026-10-01","-20","RUB",cafe,"Tied");
        row(user,"2026-10-01","-1","RUB",category("TAXI"),"Taxi");
        var rows=repository.merchants(user,LocalDate.of(2026,10,1),LocalDate.of(2026,11,1),"RUB");
        assertThat(rows.stream().filter(r -> r.categoryId()==cafe).toList()).hasSize(6);
        assertThat(rows.stream().filter(r -> r.merchant().equals("Taxi")).findFirst().orElseThrow().rank()).isEqualTo(1);
        assertThat(rows.stream().filter(r -> r.merchant().equals("Cafe 1")).toList()).isEmpty();
    }

    @Test
    void concurrentRefreshSupportsUncategorizedRowsAndCurrentMonthIsAlwaysLive() {
        UUID user=user();
        var previous=LocalDate.of(2026,9,1);
        var current=LocalDate.of(2026,10,1);
        row(user,"2026-09-10","-10","RUB",null,"Old");
        refresh.refresh();
        row(user,"2026-09-11","-20","RUB",null,"Old");
        row(user,"2026-10-10","-40","RUB",null,"Live");
        var rows=repository.monthly(user,previous,current,"RUB");
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().spent()).isEqualByComparingTo("10");
        assertThat(rows.getLast().spent()).isEqualByComparingTo("40");
        assertThat(rows.getFirst().categoryId()).isNull();
        refresh.refresh();
        refresh.refresh();
        rows=repository.monthly(user,previous,current,"RUB");
        assertThat(rows.getFirst().spent()).isEqualByComparingTo("30");
        assertThat(rows.getFirst().transactionCount()).isEqualTo(2);
        assertThat(rows.getLast().spent()).isEqualByComparingTo("40");
        assertThat(repository.monthly(user(),previous,current,"RUB")).isEmpty();
    }


    @Test
    void apiUsesPrincipalAndValidatesQueryParameters() throws Exception {
        UUID owner=user();
        UUID stranger=user();
        row(owner,"2026-10-10","-25","RUB",null,"Mine");
        row(stranger,"2026-10-10","-900","RUB",null,"Foreign");
        var authentication=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new dev.finflow.auth.security.AuthenticatedUser(owner,dev.finflow.auth.domain.Role.USER),
                null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        var principal=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(authentication);
        mvc.perform(get("/api/v1/analytics/spending").with(principal)
                        .param("from","2026-10-01").param("to","2026-11-01")
                        .param("groupBy","month").param("userId",stranger.toString()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].spent").value(25));
        mvc.perform(get("/api/v1/analytics/trends").with(principal).param("months","0"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/analytics/spending").with(principal)
                        .param("from","bad").param("to","2026-11-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/analytics/monthly-spending").with(principal)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/analytics/top-merchants").with(principal)
                        .param("from","2026-10-01").param("to","2026-11-01")).andExpect(status().isOk());
        var empty=service.spending(user(),LocalDate.of(2026,10,1),LocalDate.of(2026,11,1),"RUB","category");
        assertThat(empty).hasSize(1);
        assertThat(empty.getFirst().level()).isEqualTo("TOTAL");
        assertThat(empty.getFirst().spent()).isEqualByComparingTo("0");
    }

    @Test
    void validatesPeriodsAndRequiresAuthentication() throws Exception {
        UUID user=user();
        assertThatThrownBy(() -> service.trends(user,0,"RUB")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.monthly(user,121,"RUB")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.trends(user,6,"JPY")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.spending(user,LocalDate.of(2026,10,1),LocalDate.of(2026,9,1),"RUB","month"))
                .isInstanceOf(BusinessRuleException.class);
        mvc.perform(get("/api/v1/analytics/trends")).andExpect(status().isUnauthorized());
    }
}
