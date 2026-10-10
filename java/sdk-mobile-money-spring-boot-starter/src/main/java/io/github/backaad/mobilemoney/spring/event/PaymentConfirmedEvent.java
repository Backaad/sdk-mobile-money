package io.github.backaad.mobilemoney.spring.event;

import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.Verification;
import java.util.Currency;

/**
 * The provider confirmed the collection or payout: money was received or paid out.
 *
 * @param provider the provider that processed the transaction
 * @param type collection or payout, or {@code null} if the provider did not say
 * @param providerReference the provider's reference
 * @param merchantReference the merchant's reference, or {@code null}
 * @param amount the amount reported by the provider, or {@code null}
 * @param currency the currency reported by the provider, or {@code null}
 * @param rawStatus the provider's own status
 * @param verification how the outcome was verified
 * @param origin where the outcome was learned
 */
public record PaymentConfirmedEvent(
        ProviderId provider,
        TransactionType type,
        String providerReference,
        String merchantReference,
        Long amount,
        Currency currency,
        String rawStatus,
        Verification verification,
        Origin origin) implements PaymentOutcomeEvent {
}
