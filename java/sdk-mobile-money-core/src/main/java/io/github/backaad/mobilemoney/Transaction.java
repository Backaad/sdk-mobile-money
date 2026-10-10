package io.github.backaad.mobilemoney;

import java.util.Currency;
import java.util.Objects;

/**
 * A collection or payout, as returned by {@link MobileMoney}. Keep it (or at least its provider, type
 * and provider reference) to read its status later.
 *
 * @param provider the provider that really processed the transaction
 * @param type collection or payout
 * @param providerReference the provider's reference, or {@code null} when the provider's answer was lost
 *     before any reference was known (see {@link #outcomeUnknown()})
 * @param merchantReference the merchant's reference
 * @param amount amount requested, in whole units
 * @param currency currency
 * @param status common status
 * @param rawStatus the provider's own status for diagnostics, or {@code null} if it has none
 */
public record Transaction(
        ProviderId provider,
        TransactionType type,
        String providerReference,
        String merchantReference,
        long amount,
        Currency currency,
        TransactionStatus status,
        String rawStatus) {

    /** Validates the fields. */
    public Transaction {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(merchantReference, "merchantReference");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");
    }

    /**
     * Tells whether the request may have reached the provider without its answer coming back (timeout,
     * error after sending). The transaction is then {@link TransactionStatus#PENDING} with no provider
     * reference.
     *
     * <p>Never retry it with a new idempotency key or a new merchant reference: the customer could be
     * charged twice. Wait for the provider's webhook, which carries the merchant reference.
     *
     * @return {@code true} when the outcome of the request is unknown
     */
    public boolean outcomeUnknown() {
        return providerReference == null;
    }

    /**
     * Returns a copy processed by another provider, without provider reference.
     *
     * @param newProvider the provider
     * @return the copy
     */
    public Transaction withProvider(ProviderId newProvider) {
        return new Transaction(newProvider, type, null, merchantReference, amount, currency, status, rawStatus);
    }

    /**
     * Returns a copy with a provider reference.
     *
     * @param newReference the provider's reference, or {@code null}
     * @return the copy
     */
    public Transaction withProviderReference(String newReference) {
        return new Transaction(provider, type, newReference, merchantReference, amount, currency, status, rawStatus);
    }

    /**
     * Returns a copy with another status.
     *
     * @param newStatus the status
     * @param newRawStatus the provider's status, or {@code null}
     * @return the copy
     */
    public Transaction withStatus(TransactionStatus newStatus, String newRawStatus) {
        return new Transaction(provider, type, providerReference, merchantReference, amount, currency, newStatus,
                newRawStatus);
    }
}
