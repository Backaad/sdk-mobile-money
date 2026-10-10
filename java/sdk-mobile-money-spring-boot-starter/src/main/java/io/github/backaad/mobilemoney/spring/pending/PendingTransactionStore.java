package io.github.backaad.mobilemoney.spring.pending;

import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.Transaction;
import java.time.Instant;
import java.util.List;

/**
 * Pending transactions followed by the pending-payment task. Only transactions with a provider reference
 * can be followed: their status can be read.
 */
public interface PendingTransactionStore {

    /**
     * A followed transaction.
     *
     * @param transaction the transaction as first returned
     * @param addedAt when it started to be followed
     */
    record Entry(Transaction transaction, Instant addedAt) {
    }

    /**
     * Starts following a transaction, unless it is already followed.
     *
     * @param transaction a pending transaction with a provider reference
     * @param now the current time
     * @param firstCheckAt when to read its status for the first time
     */
    void add(Transaction transaction, Instant now, Instant firstCheckAt);

    /**
     * Takes the transactions due for a status read, and schedules their next read. Two instances never take
     * the same transaction in the same round.
     *
     * @param now the current time
     * @param limit the maximum number of transactions
     * @param nextCheckAt when the taken transactions are due again
     * @return the transactions to read
     */
    List<Entry> claimDue(Instant now, int limit, Instant nextCheckAt);

    /**
     * Stops following a transaction.
     *
     * @param provider the provider
     * @param providerReference the provider's reference
     */
    void remove(ProviderId provider, String providerReference);
}
