package io.github.backaad.mobilemoney.provider;

import io.github.backaad.mobilemoney.TransactionType;
import java.util.Objects;

/**
 * What an authenticated webhook claims. Nothing here is trusted beyond the references: the status is read
 * again from the provider.
 *
 * @param providerReference the provider's reference of the transaction
 * @param merchantReference the merchant's reference claimed by the webhook, or {@code null}
 * @param type collection or payout, or {@code null} if unknown
 * @param claimedRawStatus the status claimed by the webhook, or {@code null}
 */
public record WebhookNotification(
        String providerReference, String merchantReference, TransactionType type, String claimedRawStatus) {

    /** Validates the fields. */
    public WebhookNotification {
        Objects.requireNonNull(providerReference, "providerReference");
    }
}
