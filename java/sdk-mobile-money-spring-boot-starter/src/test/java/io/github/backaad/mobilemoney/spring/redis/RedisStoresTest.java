package io.github.backaad.mobilemoney.spring.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.Currencies;
import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.callback.ExpectedCallback;
import io.github.backaad.mobilemoney.idempotency.IdempotencyRecord;
import io.github.backaad.mobilemoney.idempotency.IdempotencyStore;
import io.github.backaad.mobilemoney.spring.MobileMoneyAutoConfiguration;
import io.github.backaad.mobilemoney.spring.MobileMoneyRedisAutoConfiguration;
import io.github.backaad.mobilemoney.spring.MobileMoneyRedisAutoConfigurationAccess;
import io.github.backaad.mobilemoney.spring.TestProvider;
import io.github.backaad.mobilemoney.spring.pending.PendingTransactionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The Redis stores against a real Redis server (rule 9: nothing debited twice for the same key, even with
 * several application instances sharing Redis).
 */
class RedisStoresTest {

    private static RedisServer server;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-10T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private String prefix;

    @BeforeAll
    static void connect() {
        server = RedisServer.get();
        factory = server.connectionFactory();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void isolate() {
        prefix = "test-" + UUID.randomUUID() + ":";
    }

    private static Transaction pending(String reference) {
        return new Transaction(ProviderId.CAMPAY, TransactionType.COLLECTION, reference, "order-1", 100,
                Currencies.XAF, TransactionStatus.PENDING, reference == null ? null : "PENDING");
    }

    @Test
    void reservationIsExclusiveAndRecordsRoundTrip() {
        RedisIdempotencyStore store = new RedisIdempotencyStore(redis, prefix, clock);

        IdempotencyStore.Reservation first = store.reserve("k1", "fp", pending(null), Duration.ofMinutes(3),
                Duration.ofHours(24));
        IdempotencyStore.Reservation second = store.reserve("k1", "fp", pending(null), Duration.ofMinutes(3),
                Duration.ofHours(24));

        assertThat(first).isInstanceOf(IdempotencyStore.Acquired.class);
        assertThat(second).isInstanceOfSatisfying(IdempotencyStore.Existing.class, e -> {
            assertThat(e.record().state()).isEqualTo(IdempotencyRecord.State.IN_PROGRESS);
            assertThat(e.record().fingerprint()).isEqualTo("fp");
        });
        String owner = ((IdempotencyStore.Acquired) first).ownerToken();
        assertThat(store.finish("k1", "someone-else", IdempotencyRecord.State.COMPLETED, pending("r"), null)).isFalse();
        assertThat(store.finish("k1", owner, IdempotencyRecord.State.COMPLETED, pending("ref-1"), null)).isTrue();
        assertThat(store.finish("k1", owner, IdempotencyRecord.State.REJECTED, null,
                new IdempotencyRecord.Rejection(ProviderId.CAMPAY, "c", "m"))).as("already finished").isFalse();

        IdempotencyRecord record = store.find("k1").orElseThrow();
        assertThat(record.state()).isEqualTo(IdempotencyRecord.State.COMPLETED);
        assertThat(record.transaction()).isEqualTo(pending("ref-1"));
        Long ttl = redis.getExpire(prefix + "idempotency:k1");
        assertThat(ttl).isBetween(Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
    }

    @Test
    void rejectionIsStoredForReplays() {
        RedisIdempotencyStore store = new RedisIdempotencyStore(redis, prefix, clock);
        String owner = ((IdempotencyStore.Acquired) store.reserve("k1", "fp", pending(null), Duration.ofMinutes(3),
                Duration.ofHours(24))).ownerToken();

        store.finish("k1", owner, IdempotencyRecord.State.REJECTED, null,
                new IdempotencyRecord.Rejection(ProviderId.CAMPAY, null, "Insufficient balance"));

        assertThat(store.find("k1").orElseThrow().rejection())
                .isEqualTo(new IdempotencyRecord.Rejection(ProviderId.CAMPAY, null, "Insufficient balance"));
    }

    @Test
    void releaseFreesTheKeyOnlyWithinTheLease() {
        RedisIdempotencyStore store = new RedisIdempotencyStore(redis, prefix, clock);
        String owner = ((IdempotencyStore.Acquired) store.reserve("k1", "fp", pending(null), Duration.ofMinutes(3),
                Duration.ofHours(24))).ownerToken();
        assertThat(store.release("k1", owner)).isTrue();
        assertThat(store.find("k1")).isEmpty();

        String late = ((IdempotencyStore.Acquired) store.reserve("k2", "fp", pending(null), Duration.ofMinutes(3),
                Duration.ofHours(24))).ownerToken();
        now.set(now.get().plus(Duration.ofMinutes(4)));
        assertThat(store.release("k2", late)).isFalse();
        assertThat(store.find("k2").orElseThrow().state()).isEqualTo(IdempotencyRecord.State.OUTCOME_UNKNOWN);
    }

    @Test
    void onlyOneOfManyConcurrentInstancesGetsTheKey() throws Exception {
        List<RedisIdempotencyStore> instances = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            instances.add(new RedisIdempotencyStore(redis, prefix, clock));
        }
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<IdempotencyStore.Reservation>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                RedisIdempotencyStore store = instances.get(i % instances.size());
                results.add(pool.submit((Callable<IdempotencyStore.Reservation>) () -> store.reserve("same", "fp",
                        pending(null), Duration.ofMinutes(3), Duration.ofHours(24))));
            }
            int acquired = 0;
            for (Future<IdempotencyStore.Reservation> result : results) {
                if (result.get() instanceof IdempotencyStore.Acquired) {
                    acquired++;
                }
            }
            assertThat(acquired).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoInstancesSharingRedisNeverCollectTwice() {
        TestProvider provider = new TestProvider();
        MobileMoney first = MobileMoney.builder().provider(provider)
                .idempotencyStore(new RedisIdempotencyStore(redis, prefix, Clock.systemUTC())).build();
        MobileMoney second = MobileMoney.builder().provider(provider)
                .idempotencyStore(new RedisIdempotencyStore(redis, prefix, Clock.systemUTC())).build();
        PaymentRequest request = PaymentRequest.builder().idempotencyKey("double-tap").amount(100)
                .phoneNumber("670123456").merchantReference("order-1").description("d").build();

        Transaction a = first.collect(request);
        Transaction b = second.collect(request);

        assertThat(b).isEqualTo(a);
        assertThat(provider.collects()).isEqualTo(1);
    }

    @Test
    void reservationWhoseAnswerIsLostIsRemoved() {
        java.util.concurrent.atomic.AtomicBoolean lose = new java.util.concurrent.atomic.AtomicBoolean(true);
        StringRedisTemplate flaky = new StringRedisTemplate(factory) {
            @Override
            public <T> T execute(org.springframework.data.redis.core.script.RedisScript<T> script, List<String> keys,
                    Object... args) {
                T result = super.execute(script, keys, args);
                if (lose.getAndSet(false)) {
                    throw new org.springframework.dao.QueryTimeoutException("answer lost");
                }
                return result;
            }
        };
        flaky.afterPropertiesSet();
        RedisIdempotencyStore store = new RedisIdempotencyStore(flaky, prefix, clock);

        assertThatThrownBy(() -> store.reserve("k1", "fp", pending(null), Duration.ofMinutes(3), Duration.ofHours(24)))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);

        assertThat(store.find("k1")).isEmpty();
        assertThat(store.reserve("k1", "fp", pending(null), Duration.ofMinutes(3), Duration.ofHours(24)))
                .isInstanceOf(IdempotencyStore.Acquired.class);
    }

    @Test
    void unreadableRecordFailsClosed() {
        RedisIdempotencyStore store = new RedisIdempotencyStore(redis, prefix, clock);
        redis.opsForValue().set(prefix + "idempotency:k1", "{not json");

        assertThatThrownBy(() -> store.find("k1")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.reserve("k1", "fp", pending(null), Duration.ofMinutes(3), Duration.ofHours(24)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void callbackTokenIsConsumedOnce() throws Exception {
        RedisCallbackTokenStore store = new RedisCallbackTokenStore(redis, prefix, clock);
        ExpectedCallback expected = new ExpectedCallback(ProviderId.TARA, TransactionType.COLLECTION, null,
                "order-1", 2000, Currencies.XAF, "+237655251245", now.get().plus(Duration.ofHours(24)));
        store.register("digest", expected);
        store.attachProviderReference("digest", "order-1");

        assertThat(store.find("digest")).contains(expected.withProviderReference("order-1"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Optional<ExpectedCallback>>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                results.add(pool.submit(() -> store.consume("digest")));
            }
            int winners = 0;
            for (Future<Optional<ExpectedCallback>> result : results) {
                winners += result.get().isPresent() ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.find("digest")).isEmpty();
    }

    @Test
    void expiredCallbackTokenIsNotFound() {
        RedisCallbackTokenStore store = new RedisCallbackTokenStore(redis, prefix, clock);
        store.register("digest", new ExpectedCallback(ProviderId.TARA, TransactionType.COLLECTION, null, "order-1",
                2000, Currencies.XAF, "+237655251245", now.get().plus(Duration.ofHours(1))));
        now.set(now.get().plus(Duration.ofHours(2)));

        assertThat(store.find("digest")).isEmpty();
        assertThat(store.consume("digest")).isEmpty();
    }

    @Test
    void pendingTransactionsAreClaimedOnceAndRemoved() {
        RedisPendingTransactionStore first = new RedisPendingTransactionStore(redis, prefix);
        RedisPendingTransactionStore second = new RedisPendingTransactionStore(redis, prefix);
        Instant t0 = now.get();
        first.add(pending("ref-1"), t0, t0.plusSeconds(60));
        first.add(pending("ref-1"), t0, t0.plusSeconds(60));
        first.add(pending("ref-2"), t0, t0.plusSeconds(120));

        assertThat(first.claimDue(t0, 10, t0.plusSeconds(60))).isEmpty();
        List<PendingTransactionStore.Entry> claimed = first.claimDue(t0.plusSeconds(60), 10, t0.plusSeconds(120));
        assertThat(claimed).extracting(e -> e.transaction().providerReference()).containsExactly("ref-1");
        assertThat(claimed.get(0).addedAt()).isEqualTo(t0);
        assertThat(second.claimDue(t0.plusSeconds(60), 10, t0.plusSeconds(120))).as("already claimed").isEmpty();

        second.remove(ProviderId.CAMPAY, "ref-1");
        assertThat(second.claimDue(t0.plusSeconds(120), 10, t0.plusSeconds(180)))
                .extracting(e -> e.transaction().providerReference()).containsExactly("ref-2");
    }

    @Test
    void unreadablePendingEntryIsDroppedWithoutBlockingTheOthers() {
        RedisPendingTransactionStore store = new RedisPendingTransactionStore(redis, prefix);
        Instant t0 = now.get();
        store.add(pending("ref-1"), t0, t0);
        store.add(pending("ref-2"), t0, t0);
        redis.opsForHash().put(prefix + "{pending}:data", "campay:ref-1", "{garbage");

        List<PendingTransactionStore.Entry> claimed = store.claimDue(t0, 10, t0.plusSeconds(60));

        assertThat(claimed).extracting(e -> e.transaction().providerReference()).containsExactly("ref-2");
        assertThat(redis.opsForHash().hasKey(prefix + "{pending}:data", "campay:ref-1")).isFalse();
    }

    @Test
    void autoConfigurationUsesRedisWhenItIsPresent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class,
                        MobileMoneyRedisAutoConfiguration.class, MobileMoneyAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.host=" + server.host, "spring.data.redis.port=" + server.port,
                        "mobile-money.simulation.enabled=true", "mobile-money.redis.key-prefix=" + prefix,
                        "mobile-money.pending-check.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(RedisIdempotencyStore.class);
                    assertThat(context.getBean(io.github.backaad.mobilemoney.callback.CallbackTokenStore.class))
                            .isInstanceOf(RedisCallbackTokenStore.class);
                    assertThat(context.getBean(PendingTransactionStore.class))
                            .isInstanceOf(RedisPendingTransactionStore.class);
                });
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class,
                        MobileMoneyRedisAutoConfiguration.class, MobileMoneyAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.host=" + server.host, "spring.data.redis.port=" + server.port,
                        "mobile-money.simulation.enabled=true", "mobile-money.store=memory")
                .run(context -> assertThat(context.getBean(IdempotencyStore.class))
                        .isNotInstanceOf(RedisIdempotencyStore.class));
    }

    @Test
    void unreachableRedisPreventsStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class,
                        MobileMoneyRedisAutoConfiguration.class, MobileMoneyAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
                        "spring.data.redis.connect-timeout=500ms", "mobile-money.simulation.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(ConfigurationException.class)
                            .hasMessageContaining("Redis cannot be reached");
                });
    }

    @Test
    void evictingRedisPreventsStartup() {
        String previous = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
                connection.serverCommands().getConfig("maxmemory-policy").getProperty("maxmemory-policy"));
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                connection.serverCommands().setConfig("maxmemory-policy", "allkeys-lru");
                return null;
            });
            assertThatThrownBy(() -> MobileMoneyRedisAutoConfigurationAccess.check(factory))
                    .isInstanceOf(ConfigurationException.class).hasMessageContaining("allkeys-lru");
        } finally {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                connection.serverCommands().setConfig("maxmemory-policy", previous);
                return null;
            });
        }
    }
}
