package io.github.backaad.mobilemoney.idempotency;

import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.Transaction;
import java.time.Instant;
import java.util.Objects;

/**
 * What is stored under an idempotency key. Plain data, so that any store can serialize it.
 *
 * @param key the idempotency key
 * @param fingerprint digest of the request, to detect a key reused for a different request
 * @param state where the request stands
 * @param transaction for {@link State#COMPLETED}, the result; for the other states except
 *     {@link State#REJECTED}, a pending transaction without provider reference
 * @param rejection for {@link State#REJECTED}, why the provider refused; otherwise {@code null}
 * @param ownerToken secret token of the caller that reserved the key; only that caller can finish it
 * @param leaseUntil for {@link State#IN_PROGRESS}, after this instant the caller is presumed dead and the
 *     outcome is treated as unknown
 * @param expiresAt when the key can be used again (24 hours after the reservation)
 */
public record IdempotencyRecord(
        String key,
        String fingerprint,
        State state,
        Transaction transaction,
        Rejection rejection,
        String ownerToken,
        Instant leaseUntil,
        Instant expiresAt) {

    /** Where a request stands. */
    public enum State {
        /** Sent or about to be sent; the result is not known yet. */
        IN_PROGRESS,
        /** The provider accepted the request. */
        COMPLETED,
        /** The provider refused the request: nothing was debited or paid out. */
        REJECTED,
        /** The request may have reached the provider, but its answer was lost. It is never sent again. */
        OUTCOME_UNKNOWN
    }

    /**
     * A refusal from the provider, kept so that a replay gets the same answer.
     *
     * @param provider the provider that refused
     * @param code the provider's error code, or {@code null}
     * @param message the description, without any secret
     */
    public record Rejection(ProviderId provider, String code, String message) {

        /** Validates the fields. */
        public Rejection {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(message, "message");
        }
    }

    /** Validates the fields. */
    public IdempotencyRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(ownerToken, "ownerToken");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (state == State.REJECTED ? rejection == null : transaction == null) {
            throw new IllegalArgumentException("A " + state + " record needs a "
                    + (state == State.REJECTED ? "rejection" : "transaction"));
        }
    }

    /**
     * Returns a finished copy.
     *
     * @param newState the final state
     * @param newTransaction the transaction, or {@code null} for a rejection
     * @param newRejection the rejection, or {@code null}
     * @return the copy
     */
    public IdempotencyRecord finish(State newState, Transaction newTransaction, Rejection newRejection) {
        return new IdempotencyRecord(key, fingerprint, newState,
                newTransaction != null ? newTransaction : transaction, newRejection, ownerToken, leaseUntil,
                expiresAt);
    }

    /** Leaves out the owner token. */
    @Override
    public String toString() {
        return "IdempotencyRecord[key=" + key + ", state=" + state + ", expiresAt=" + expiresAt + "]";
    }
}
