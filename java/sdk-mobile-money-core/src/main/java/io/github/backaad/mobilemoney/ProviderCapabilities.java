package io.github.backaad.mobilemoney;

import java.util.Currency;
import java.util.Objects;
import java.util.Set;

/**
 * What a provider supports, as configured.
 *
 * @param collection can collect money from a customer
 * @param payout can pay money out
 * @param statusQuery the status of a transaction can be read from the provider's API
 * @param webhookSignature how its webhooks are authenticated
 * @param currencies currencies it accepts; a request in another currency is refused before anything
 *     is sent
 */
public record ProviderCapabilities(
        boolean collection,
        boolean payout,
        boolean statusQuery,
        WebhookSignature webhookSignature,
        Set<Currency> currencies) {

    /** Validates the arguments and makes the sets immutable. */
    public ProviderCapabilities {
        Objects.requireNonNull(webhookSignature, "webhookSignature");
        currencies = Set.copyOf(Objects.requireNonNull(currencies, "currencies"));
    }
}
