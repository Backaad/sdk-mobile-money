package io.github.backaad.mobilemoney.provider;

import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.TransactionType;
import java.util.Currency;
import java.util.Objects;

/**
 * A transaction status read from a provider's API.
 *
 * @param providerReference the provider's reference, as returned by the provider
 * @param merchantReference the merchant's reference, or {@code null} if the provider did not return it
 * @param type collection or payout, or {@code null} if the provider did not say
 * @param status common status; an unknown provider status maps to PENDING
 * @param rawStatus the provider's own status, or {@code null}
 * @param amount amount reported, or {@code null}
 * @param currency currency reported, or {@code null}
 */
public record StatusReport(
        String providerReference,
        String merchantReference,
        TransactionType type,
        TransactionStatus status,
        String rawStatus,
        Long amount,
        Currency currency) {

    /** Validates the fields. */
    public StatusReport {
        Objects.requireNonNull(providerReference, "providerReference");
        Objects.requireNonNull(status, "status");
    }
}
