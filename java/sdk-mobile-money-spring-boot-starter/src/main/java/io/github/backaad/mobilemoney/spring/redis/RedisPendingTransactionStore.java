package io.github.backaad.mobilemoney.spring.redis;

import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.spring.pending.PendingTransactionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Pending transactions in Redis: a sorted set by next check time, and a hash of the transactions. Due
 * transactions are claimed atomically, so two instances never read the same one in the same round.
 */
public final class RedisPendingTransactionStore implements PendingTransactionStore {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CLAIM = RedisScript.of("""
            local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
            local result = {}
            for _, id in ipairs(ids) do
              redis.call('ZADD', KEYS[1], ARGV[3], id)
              local value = redis.call('HGET', KEYS[2], id)
              if value then
                table.insert(result, value)
              else
                redis.call('ZREM', KEYS[1], id)
              end
            end
            return result
            """, List.class);
    private static final RedisScript<Long> ADD = RedisScript.of("""
            if redis.call('HSETNX', KEYS[2], ARGV[1], ARGV[2]) == 1 then
              redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String schedule;
    private final String data;

    /**
     * Creates the store.
     *
     * @param redis the Redis client
     * @param keyPrefix prefix of the keys, for example {@code mobile-money:}
     */
    public RedisPendingTransactionStore(StringRedisTemplate redis, String keyPrefix) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.schedule = Objects.requireNonNull(keyPrefix, "keyPrefix") + "pending:schedule";
        this.data = keyPrefix + "pending:data";
    }

    @Override
    public void add(Transaction transaction, Instant now, Instant firstCheckAt) {
        Objects.requireNonNull(transaction.providerReference(), "providerReference");
        redis.execute(ADD, List.of(schedule, data), id(transaction.provider(), transaction.providerReference()),
                RedisCodec.write(new Entry(transaction, now)), Long.toString(firstCheckAt.toEpochMilli()));
    }

    @Override
    public List<Entry> claimDue(Instant now, int limit, Instant nextCheckAt) {
        List<?> values = redis.execute(CLAIM, List.of(schedule, data), Long.toString(now.toEpochMilli()),
                Integer.toString(limit), Long.toString(nextCheckAt.toEpochMilli()));
        List<Entry> entries = new ArrayList<>();
        if (values != null) {
            for (Object value : values) {
                entries.add(RedisCodec.readPendingEntry(String.valueOf(value)));
            }
        }
        return entries;
    }

    @Override
    public void remove(ProviderId provider, String providerReference) {
        String id = id(provider, providerReference);
        redis.opsForZSet().remove(schedule, id);
        redis.opsForHash().delete(data, id);
    }

    private static String id(ProviderId provider, String providerReference) {
        return provider.value() + ":" + providerReference;
    }
}
