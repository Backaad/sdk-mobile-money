package io.github.backaad.mobilemoney.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.Currencies;
import io.github.backaad.mobilemoney.IdempotencyStoreException;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.testing.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyStoreTest {

    private static final Duration LEASE = Duration.ofMinutes(3);
    private static final Duration TTL = Duration.ofHours(24);
    private static final Transaction PENDING = new Transaction(ProviderId.CAMPAY, TransactionType.COLLECTION, null,
            "order-1", 100, Currencies.XAF, TransactionStatus.PENDING, null);

    private final MutableClock clock = MutableClock.at("2026-10-10T12:00:00Z");
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock, 10_000);

    @Test
    void onlyOneOf16ConcurrentCallersAcquiresTheKey() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<IdempotencyStore.Reservation> results = new ArrayList<>();
        try {
            List<Callable<IdempotencyStore.Reservation>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                calls.add(() -> store.reserve("key", "fp", PENDING, LEASE, TTL));
            }
            for (Future<IdempotencyStore.Reservation> f : pool.invokeAll(calls)) {
                results.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).filteredOn(r -> r instanceof IdempotencyStore.Acquired).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof IdempotencyStore.Existing).hasSize(15);
    }

    @Test
    void onlyTheOwnerCanFinishOrRelease() {
        String owner = ((IdempotencyStore.Acquired) store.reserve("key", "fp", PENDING, LEASE, TTL)).ownerToken();

        assertThat(store.release("key", "someone-else")).isFalse();
        assertThat(store.finish("key", "someone-else", IdempotencyRecord.State.COMPLETED, PENDING, null)).isFalse();
        assertThat(store.find("key")).get().extracting(IdempotencyRecord::state)
                .isEqualTo(IdempotencyRecord.State.IN_PROGRESS);

        assertThat(store.finish("key", owner, IdempotencyRecord.State.OUTCOME_UNKNOWN, PENDING, null)).isTrue();
        // A finished record can no longer be released, even by its owner.
        assertThat(store.release("key", owner)).isFalse();
        assertThat(store.find("key")).isPresent();
    }

    @Test
    void recordsExpireAfterTheirTtl() {
        store.reserve("key", "fp", PENDING, LEASE, TTL);

        clock.advance(TTL.minusSeconds(1));
        assertThat(store.find("key")).isPresent();
        clock.advance(Duration.ofSeconds(1));
        assertThat(store.find("key")).isEmpty();
        assertThat(store.reserve("key", "fp", PENDING, LEASE, TTL)).isInstanceOf(IdempotencyStore.Acquired.class);
    }

    @Test
    void expiredRecordsAreRemoved() {
        for (int i = 0; i < 1_000; i++) {
            store.reserve("key-" + i, "fp", PENDING, LEASE, TTL);
        }
        clock.advance(TTL.plusSeconds(1));

        store.reserve("new-key", "fp", PENDING, LEASE, TTL);

        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void fullStoreRefusesNewKeysInsteadOfEvictingLiveOnes() {
        InMemoryIdempotencyStore small = new InMemoryIdempotencyStore(clock, 2);
        small.reserve("a", "fp", PENDING, LEASE, TTL);
        small.reserve("b", "fp", PENDING, LEASE, TTL);

        assertThatThrownBy(() -> small.reserve("c", "fp", PENDING, LEASE, TTL))
                .isInstanceOf(IdempotencyStoreException.class);
        assertThat(small.find("a")).isPresent();
        assertThat(small.reserve("a", "fp", PENDING, LEASE, TTL)).isInstanceOf(IdempotencyStore.Existing.class);
    }

    @Test
    void toStringLeavesOutTheOwnerToken() {
        String owner = ((IdempotencyStore.Acquired) store.reserve("key", "fp", PENDING, LEASE, TTL)).ownerToken();

        assertThat(store.find("key").orElseThrow().toString()).doesNotContain(owner);
        assertThat(new IdempotencyStore.Acquired(owner).toString()).doesNotContain(owner);
    }
}
