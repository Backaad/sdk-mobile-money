package io.github.backaad.mobilemoney.callback;

import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.TransactionType;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

/**
 * The payment a callback URL was created for. A callback is only accepted if it matches it.
 *
 * @param provider the provider the URL was given to
 * @param type collection or payout
 * @param providerReference the reference sent to the provider, or {@code null} until it is known
 * @param merchantReference the merchant's reference
 * @param amount expected amount
 * @param currency expected currency
 * @param phoneNumber expected phone number (E.164)
 * @param expiresAt after this instant the URL is refused
 */
public record ExpectedCallback(
        ProviderId provider,
        TransactionType type,
        String providerReference,
        String merchantReference,
        long amount,
        Currency currency,
        String phoneNumber,
        Instant expiresAt) {

    /** Validates the fields. */
    public ExpectedCallback {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(merchantReference, "merchantReference");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(phoneNumber, "phoneNumber");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * Returns a copy with the provider reference.
     *
     * @param reference the reference
     * @return the copy
     */
    public ExpectedCallback withProviderReference(String reference) {
        return new ExpectedCallback(provider, type, reference, merchantReference, amount, currency, phoneNumber,
                expiresAt);
    }
}
