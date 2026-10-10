package io.github.backaad.mobilemoney;

/**
 * Told about each new collection or payout that reached a provider: accepted (usually {@code PENDING}) or
 * with an unknown outcome. Not called for replays of an idempotency key, refusals, or requests that were
 * not sent.
 *
 * <p>Called on the caller's thread, after the result is recorded. An exception thrown by the listener is
 * logged and ignored: it never changes the result of the payment.
 */
@FunctionalInterface
public interface TransactionListener {

    /**
     * Receives a new transaction.
     *
     * @param transaction the transaction, as returned to the caller
     */
    void onTransaction(Transaction transaction);
}
