package io.github.backaad.mobilemoney.idempotency;

import io.github.backaad.mobilemoney.IdempotencyStoreException;
import io.github.backaad.mobilemoney.Transaction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps idempotency records in memory.
 *
 * <p>Suitable for a single application instance only: with several instances behind a load balancer,
 * each would accept the same key once. Expired records are removed at most once a minute, during a
 * reservation. Records that have not expired are never evicted: past {@code maxEntries}, new reservations
 * are refused instead.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    /** Default maximum number of records. */
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> nextSweep = new AtomicReference<>(Instant.MIN);
    private final Clock clock;
    private final int maxEntries;

    /** Creates a store with the system clock and {@link #DEFAULT_MAX_ENTRIES}. */
    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC(), DEFAULT_MAX_ENTRIES);
    }

    /**
     * Creates a store.
     *
     * @param clock clock used for expiry
     * @param maxEntries maximum number of records kept at the same time
     */
    public InMemoryIdempotencyStore(Clock clock, int maxEntries) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    @Override
    public Reservation reserve(String key, String fingerprint, Transaction pending, Duration lease, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Instant now = clock.instant();
        Instant due = nextSweep.get();
        if ((!now.isBefore(due) && nextSweep.compareAndSet(due, now.plus(SWEEP_INTERVAL)))
                || records.size() >= maxEntries) {
            // Never inside compute(): the map must not be modified from within its own remapping function.
            sweep();
        }
        String ownerToken = UUID.randomUUID().toString();
        IdempotencyRecord[] existing = new IdempotencyRecord[1];
        records.compute(key, (k, current) -> {
            if (current != null && !isExpired(current, now)) {
                existing[0] = current;
                return current;
            }
            if (current == null && records.size() >= maxEntries) {
                throw new IdempotencyStoreException(
                        "In-memory idempotency store is full (" + maxEntries + " records)", null);
            }
            return new IdempotencyRecord(k, fingerprint, IdempotencyRecord.State.IN_PROGRESS, pending, null,
                    ownerToken, now.plus(lease), now.plus(ttl));
        });
        return existing[0] != null ? new Existing(existing[0]) : new Acquired(ownerToken);
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        IdempotencyRecord record = records.get(key);
        if (record == null || isExpired(record, clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    @Override
    public boolean finish(String key, String ownerToken, IdempotencyRecord.State state, Transaction transaction,
            IdempotencyRecord.Rejection rejection) {
        boolean[] updated = new boolean[1];
        records.computeIfPresent(key, (k, current) -> {
            if (isOwnedInProgress(current, ownerToken)) {
                updated[0] = true;
                return current.finish(state, transaction, rejection);
            }
            return current;
        });
        return updated[0];
    }

    @Override
    public boolean release(String key, String ownerToken) {
        boolean[] removed = new boolean[1];
        records.computeIfPresent(key, (k, current) -> {
            if (isOwnedInProgress(current, ownerToken)) {
                removed[0] = true;
                return null;
            }
            return current;
        });
        return removed[0];
    }

    /**
     * Returns the number of records currently held, expired or not.
     *
     * @return the number of records
     */
    public int size() {
        return records.size();
    }

    private void sweep() {
        Instant now = clock.instant();
        records.values().removeIf(record -> isExpired(record, now));
    }

    private static boolean isOwnedInProgress(IdempotencyRecord record, String ownerToken) {
        return record.state() == IdempotencyRecord.State.IN_PROGRESS && record.ownerToken().equals(ownerToken);
    }

    private static boolean isExpired(IdempotencyRecord record, Instant now) {
        return !now.isBefore(record.expiresAt());
    }
}
