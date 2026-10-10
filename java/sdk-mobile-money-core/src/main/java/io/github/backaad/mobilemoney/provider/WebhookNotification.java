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
 * @param claimedAmount the amount claimed by the webhook, or {@code null}
 * @param claimedPhoneNumber the phone number claimed by the webhook (E.164), or {@code null}
 */
public record WebhookNotification(
        String providerReference, String merchantReference, TransactionType type, String claimedRawStatus,
        Long claimedAmount, String claimedPhoneNumber) {

    /**
     * Creates a notification without claimed amount or number.
     *
     * @param providerReference the provider's reference of the transaction
     * @param merchantReference the merchant's reference claimed by the webhook, or {@code null}
     * @param type collection or payout, or {@code null} if unknown
     * @param claimedRawStatus the status claimed by the webhook, or {@code null}
     */
    public WebhookNotification(
            String providerReference, String merchantReference, TransactionType type, String claimedRawStatus) {
        this(providerReference, merchantReference, type, claimedRawStatus, null, null);
    }

    /** Validates the fields. */
    public WebhookNotification {
        Objects.requireNonNull(providerReference, "providerReference");
    }
}
