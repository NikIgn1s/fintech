package dev.finflow.budget.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;
import org.hibernate.envers.Audited;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name="budgets")
@Audited
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class BudgetEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;
    @Column(name="user_id",nullable=false)
    private UUID userId;
    @Column(name="category_id",nullable=false)
    private short categoryId;
    @Column(nullable=false)
    private LocalDate month;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable=false,length=3)
    private String currency;
    @Column(name="amount_limit",nullable=false,precision=19,scale=4)
    private BigDecimal limit;

    public static BudgetEntity create(UUID user,short category,LocalDate month,String currency,BigDecimal limit) {
        BudgetEntity entity=new BudgetEntity();
        entity.userId=user;
        entity.categoryId=category;
        entity.month=month;
        entity.currency=currency;
        entity.limit=limit;
        return entity;
    }

    public void changeLimit(BigDecimal limit) {
        this.limit=limit;
    }
}
