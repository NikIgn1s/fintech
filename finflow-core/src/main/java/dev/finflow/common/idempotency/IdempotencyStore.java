package dev.finflow.common.idempotency;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class IdempotencyStore {

    static final double STALE_IN_PROGRESS_SECONDS = 300;

    private static final String ACQUIRE = """
            INSERT INTO idempotency_keys (user_id, key, request_hash)
            VALUES (?, ?, ?)
            ON CONFLICT (user_id, key) DO UPDATE
                SET request_hash = EXCLUDED.request_hash, created_at = now()
                WHERE idempotency_keys.response_status IS NULL
                  AND idempotency_keys.created_at < now() - make_interval(secs => ?)
            """;

    private static final String FIND = """
            SELECT request_hash, response_status, response_body::text AS response_body
            FROM idempotency_keys
            WHERE user_id = ? AND key = ?
            """;

    private static final String COMPLETE = """
            UPDATE idempotency_keys
            SET response_status = ?, response_body = ?::jsonb
            WHERE user_id = ? AND key = ?
            """;

    private static final String RELEASE = """
            DELETE FROM idempotency_keys
            WHERE user_id = ? AND key = ? AND response_status IS NULL
            """;

    private static final String DELETE_OLDER = "DELETE FROM idempotency_keys WHERE created_at < ?";

    private final JdbcTemplate jdbc;

    public record StoredKey(String requestHash, Integer responseStatus, String responseBody) {
    }

    public boolean tryAcquire(UUID userId, String key, String requestHash) {
        return jdbc.update(ACQUIRE, userId, key, requestHash, STALE_IN_PROGRESS_SECONDS) == 1;
    }

    public Optional<StoredKey> find(UUID userId, String key) {
        return jdbc.query(FIND, (rs, row) -> new StoredKey(
                rs.getString("request_hash"),
                (Integer) rs.getObject("response_status"),
                rs.getString("response_body")), userId, key).stream().findFirst();
    }

    public void complete(UUID userId, String key, int status, String responseBody) {
        jdbc.update(COMPLETE, status, responseBody, userId, key);
    }

    public void release(UUID userId, String key) {
        jdbc.update(RELEASE, userId, key);
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.update(DELETE_OLDER, Timestamp.from(cutoff));
    }
}
