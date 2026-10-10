package io.github.backaad.mobilemoney.idempotency;

import io.github.backaad.mobilemoney.Transaction;
import java.time.Duration;
import java.util.Optional;

/**
 * Stores idempotency records.
 *
 * <p>Contract, which every implementation must respect:
 * <ul>
 *   <li>{@link #reserve} is atomic: for a given key, exactly one caller gets {@link Acquired} until the
 *       record expires.</li>
 *   <li>{@link #finish} and {@link #release} only act if the record is still {@code IN_PROGRESS} and the
 *       owner token matches, so a late caller can never overwrite a newer record.</li>
 *   <li>An exception means the store could not be used. The SDK then fails closed: if {@link #reserve}
 *       fails, the provider is not called.</li>
 *   <li>Records are never evicted before they expire: evicting one would let the same request through
 *       twice.</li>
 * </ul>
 *
 * <p>{@link InMemoryIdempotencyStore} keeps records in the JVM: with several application instances, use a
 * shared store (Redis with the Spring Boot starter).
 */
public interface IdempotencyStore {

    /**
     * Reserves a key, unless a record that has not expired already exists.
     *
     * @param key the idempotency key
     * @param fingerprint digest of the request
     * @param pending transaction to return if the outcome turns out to be unknown
     * @param lease how long the caller may take before it is presumed dead
     * @param ttl how long the key stays reserved
     * @return {@link Acquired} with an owner token, or {@link Existing} with the current record
     */
    Reservation reserve(String key, String fingerprint, Transaction pending, Duration lease, Duration ttl);

    /**
     * Reads a record that has not expired.
     *
     * @param key the idempotency key
     * @return the record, or empty
     */
    Optional<IdempotencyRecord> find(String key);

    /**
     * Finishes an {@code IN_PROGRESS} record.
     *
     * @param key the idempotency key
     * @param ownerToken token returned by {@link #reserve}
     * @param state the final state
     * @param transaction the result, or {@code null} for a rejection
     * @param rejection the rejection, or {@code null}
     * @return {@code true} if the record was updated
     */
    boolean finish(String key, String ownerToken, IdempotencyRecord.State state, Transaction transaction,
            IdempotencyRecord.Rejection rejection);

    /**
     * Deletes an {@code IN_PROGRESS} record, when nothing reached the provider.
     *
     * @param key the idempotency key
     * @param ownerToken token returned by {@link #reserve}
     * @return {@code true} if the record was deleted
     */
    boolean release(String key, String ownerToken);

    /** Result of {@link #reserve}. */
    sealed interface Reservation permits Acquired, Existing {
    }

    /**
     * The caller now owns the key.
     *
     * @param ownerToken token to pass to {@link #finish} or {@link #release}
     */
    record Acquired(String ownerToken) implements Reservation {

        /** Leaves out the token. */
        @Override
        public String toString() {
            return "Acquired[]";
        }
    }

    /**
     * The key is already used.
     *
     * @param record the current record
     */
    record Existing(IdempotencyRecord record) implements Reservation {
    }
}
