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

@Entity
@Table(name = "transfer_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Column(name = "reviewer_id", nullable = false, updatable = false)
    private UUID reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ReviewDecision decision;

    @Column(length = 255, updatable = false)
    private String reason;

    @Column(name = "fraud_score", precision = 5, scale = 4, updatable = false)
    private BigDecimal fraudScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static TransferReview of(UUID transferId, UUID reviewerId, ReviewDecision decision, String reason,
            BigDecimal fraudScore, Instant now) {
        TransferReview review = new TransferReview();
        review.transferId = transferId;
        review.reviewerId = reviewerId;
        review.decision = decision;
        review.reason = reason;
        review.fraudScore = fraudScore;
        review.createdAt = now;
        return review;
    }
}
