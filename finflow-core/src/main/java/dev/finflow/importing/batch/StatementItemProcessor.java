package dev.finflow.importing.batch;

import dev.finflow.importing.parser.DescriptionNormalizer;
import dev.finflow.importing.parser.ParsedTransaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.item.ItemProcessor;

public class StatementItemProcessor implements ItemProcessor<ParsedLine, BankTransactionRow> {

    private final UUID userId;
    private final UUID importId;
    private final DescriptionNormalizer normalizer;
    private final Map<String, Integer> occurrences = new HashMap<>();

    public StatementItemProcessor(UUID userId, UUID importId, DescriptionNormalizer normalizer) {
        this.userId = userId;
        this.importId = importId;
        this.normalizer = normalizer;
    }

    @Override
    public BankTransactionRow process(ParsedLine line) {
        ParsedTransaction transaction = line.transaction();
        if (transaction == null) {
            return null;
        }
        String signature = transaction.operationDate() + "|" + transaction.amount().toPlainString() + "|"
                + collapse(transaction.description());
        int ordinal = occurrences.merge(signature, 1, Integer::sum) - 1;
        return new BankTransactionRow(userId, importId, transaction.operationDate(), transaction.amount(),
                transaction.currency(), transaction.description(), normalizer.merchant(transaction.description()),
                transaction.mcc(), sha256(userId + "|" + signature + "|" + ordinal));
    }

    static String collapse(String description) {
        return description.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
