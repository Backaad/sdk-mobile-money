package io.github.backaad.mobilemoney.spring.redis;

import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.idempotency.IdempotencyRecord;
import io.github.backaad.mobilemoney.idempotency.IdempotencyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Idempotency records in Redis, shared by every instance of the application. Each record is a JSON value
 * that expires with the key; reservations and updates are atomic (Lua scripts, compare-and-set).
 *
 * <p>The Redis server must not evict keys ({@code maxmemory-policy noeviction}): an evicted record would
 * let the same request through twice. The starter checks it at startup when it can.
 */
public final class RedisIdempotencyStore implements IdempotencyStore {

    private static final RedisScript<String> RESERVE = RedisScript.of("""
            local current = redis.call('GET', KEYS[1])
            if current then return current end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return false
            """, String.class);
    static final RedisScript<Long> REPLACE_IF_SAME = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[2], 'KEEPTTL')
              return 1
            end
            return 0
            """, Long.class);
    private static final RedisScript<Long> DELETE_IF_SAME = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;
    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param redis the Redis client
     * @param keyPrefix prefix of the keys, for example {@code mobile-money:}
     * @param clock the clock
     */
    public RedisIdempotencyStore(StringRedisTemplate redis, String keyPrefix, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.prefix = Objects.requireNonNull(keyPrefix, "keyPrefix") + "idempotency:";
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Reservation reserve(String key, String fingerprint, Transaction pending, Duration lease, Duration ttl) {
        Instant now = clock.instant();
        String owner = UUID.randomUUID().toString();
        IdempotencyRecord record = new IdempotencyRecord(key, fingerprint, IdempotencyRecord.State.IN_PROGRESS,
                pending, null, owner, now.plus(lease), now.plus(ttl));
        String json = RedisCodec.write(record);
        String existing;
        try {
            existing = redis.execute(RESERVE, List.of(prefix + key), json, Long.toString(ttl.toMillis()));
        } catch (RuntimeException e) {
            // The script may have run although its answer was lost. Nothing has been sent to a provider yet, so
            // our own reservation (identified by its unique owner token) can be removed: otherwise a retry would
            // wait for it, then report an unknown outcome for 24 hours.
            try {
                redis.execute(DELETE_IF_SAME, List.of(prefix + key), json);
            } catch (RuntimeException ignored) {
                // Redis is still unreachable: the reservation expires with its lease.
            }
            throw e;
        }
        return existing == null ? new Acquired(owner) : new Existing(RedisCodec.readIdempotencyRecord(existing));
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        String value = redis.opsForValue().get(prefix + key);
        return value == null ? Optional.empty() : Optional.of(RedisCodec.readIdempotencyRecord(value));
    }

    @Override
    public boolean finish(String key, String ownerToken, IdempotencyRecord.State state, Transaction transaction,
            IdempotencyRecord.Rejection rejection) {
        String stored = redis.opsForValue().get(prefix + key);
        if (stored == null) {
            return false;
        }
        IdempotencyRecord current = RedisCodec.readIdempotencyRecord(stored);
        if (!isOwnedInProgress(current, ownerToken)) {
            return false;
        }
        String finished = RedisCodec.write(current.finish(state, transaction, rejection));
        return Long.valueOf(1).equals(redis.execute(REPLACE_IF_SAME, List.of(prefix + key), stored, finished));
    }

    @Override
    public boolean release(String key, String ownerToken) {
        String stored = redis.opsForValue().get(prefix + key);
        if (stored == null) {
            return false;
        }
        IdempotencyRecord current = RedisCodec.readIdempotencyRecord(stored);
        if (!isOwnedInProgress(current, ownerToken)) {
            return false;
        }
        if (!clock.instant().isBefore(current.leaseUntil())) {
            // Other callers may already have been told that the outcome is unknown: keep it that way.
            String unknown = RedisCodec.write(current.finish(IdempotencyRecord.State.OUTCOME_UNKNOWN,
                    current.transaction(), null));
            redis.execute(REPLACE_IF_SAME, List.of(prefix + key), stored, unknown);
            return false;
        }
        return Long.valueOf(1).equals(redis.execute(DELETE_IF_SAME, List.of(prefix + key), stored));
    }

    private static boolean isOwnedInProgress(IdempotencyRecord record, String ownerToken) {
        return record.state() == IdempotencyRecord.State.IN_PROGRESS && record.ownerToken().equals(ownerToken);
    }
}
