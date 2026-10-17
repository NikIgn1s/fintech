package dev.finflow.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "fraud_checks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FraudCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Column(precision = 5, scale = 4, updatable = false)
    private BigDecimal score;

    @Column(name = "model_version", length = 50, updatable = false)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private FraudDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private FraudSource source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private String features;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static FraudCheck record(UUID transferId, BigDecimal score, String modelVersion, FraudDecision decision,
            FraudSource source, String features, Instant now) {
        FraudCheck check = new FraudCheck();
        check.transferId = transferId;
        check.score = score;
        check.modelVersion = modelVersion;
        check.decision = decision;
        check.source = source;
        check.features = features;
        check.createdAt = now;
        return check;
    }
}
