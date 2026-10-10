package io.github.backaad.mobilemoney;

import java.util.Currency;
import java.util.Objects;

/**
 * A verified webhook. {@link MobileMoney#handleWebhook} only returns an event once it has been verified;
 * anything else raises an exception.
 *
 * <p>Every field except {@code claimedRawStatus} comes from the provider's API, not from the webhook
 * itself. Before delivering an order, look it up by {@code providerReference} or
 * {@code merchantReference} and compare the amount and currency with what was ordered.
 *
 * <p>Webhooks can arrive several times: handle the same (provider, providerReference, status) only once.
 *
 * @param provider the provider that sent the webhook and processed the transaction
 * @param type collection or payout, as returned by the provider's API, or {@code null} when it does not
 *     say (Campay): take it from your own record of the transaction
 * @param providerReference the provider's reference
 * @param merchantReference the merchant's reference, or {@code null} when the provider did not return one
 * @param amount amount reported by the provider, or {@code null} if it did not report it
 * @param currency currency reported by the provider, or {@code null} if it did not report it
 * @param status verified status
 * @param rawStatus the provider's own status, or {@code null}
 * @param verification how the event was verified
 * @param claimedRawStatus the status the webhook itself claimed, for diagnostics only; never act on it
 */
public record WebhookEvent(
        ProviderId provider,
        TransactionType type,
        String providerReference,
        String merchantReference,
        Long amount,
        Currency currency,
        TransactionStatus status,
        String rawStatus,
        Verification verification,
        String claimedRawStatus) {

    /** Validates the fields. */
    public WebhookEvent {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(providerReference, "providerReference");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(verification, "verification");
    }

    /**
     * Tells whether the transaction reached a final status.
     *
     * @return {@code true} for {@link TransactionStatus#SUCCESSFUL} or {@link TransactionStatus#FAILED}
     */
    public boolean isFinal() {
        return status != TransactionStatus.PENDING;
    }
}
