package dev.finflow.ledger.domain;

import dev.finflow.ledger.api.LedgerTransactionType;

import dev.finflow.ledger.api.Posting;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ledger_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LedgerTransactionType type;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private UUID referenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    private List<LedgerEntry> entries = new ArrayList<>();

    public static LedgerTransaction of(LedgerTransactionType type, UUID referenceId, List<Posting> postings,
            Instant now) {
        LedgerTransaction transaction = new LedgerTransaction();
        transaction.type = type;
        transaction.referenceId = referenceId;
        transaction.createdAt = now;
        postings.forEach(posting -> transaction.entries.add(LedgerEntry.of(transaction, posting, now)));
        return transaction;
    }
}
