package io.github.backaad.mobilemoney.spring.redis;

import io.github.backaad.mobilemoney.callback.CallbackTokenStore;
import io.github.backaad.mobilemoney.callback.ExpectedCallback;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Single-use callback tokens (Tara) in Redis. Only the token's SHA-256 digest is used as key; a token is
 * consumed atomically ({@code GETDEL}, Redis 6.2 or later), so two deliveries of the same callback can never
 * both use it.
 */
public final class RedisCallbackTokenStore implements CallbackTokenStore {

    private static final int ATTACH_ATTEMPTS = 3;

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
    public RedisCallbackTokenStore(StringRedisTemplate redis, String keyPrefix, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.prefix = Objects.requireNonNull(keyPrefix, "keyPrefix") + "callback:";
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void register(String tokenDigest, ExpectedCallback expected) {
        long ttl = Duration.between(clock.instant(), expected.expiresAt()).toMillis();
        if (ttl <= 0) {
            return;
        }
        redis.opsForValue().set(prefix + tokenDigest, RedisCodec.write(expected), ttl, TimeUnit.MILLISECONDS);
    }

    @Override
    public void attachProviderReference(String tokenDigest, String providerReference) {
        for (int attempt = 0; attempt < ATTACH_ATTEMPTS; attempt++) {
            String stored = redis.opsForValue().get(prefix + tokenDigest);
            if (stored == null) {
                return;
            }
            String updated = RedisCodec.write(RedisCodec.readExpectedCallback(stored)
                    .withProviderReference(providerReference));
            Long replaced = redis.execute(RedisIdempotencyStore.REPLACE_IF_SAME, List.of(prefix + tokenDigest),
                    stored, updated);
            if (Long.valueOf(1).equals(replaced)) {
                return;
            }
        }
    }

    @Override
    public Optional<ExpectedCallback> find(String tokenDigest) {
        return unexpired(redis.opsForValue().get(prefix + tokenDigest));
    }

    @Override
    public Optional<ExpectedCallback> consume(String tokenDigest) {
        return unexpired(redis.opsForValue().getAndDelete(prefix + tokenDigest));
    }

    private Optional<ExpectedCallback> unexpired(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        ExpectedCallback expected = RedisCodec.readExpectedCallback(stored);
        return clock.instant().isBefore(expected.expiresAt()) ? Optional.of(expected) : Optional.empty();
    }
}
