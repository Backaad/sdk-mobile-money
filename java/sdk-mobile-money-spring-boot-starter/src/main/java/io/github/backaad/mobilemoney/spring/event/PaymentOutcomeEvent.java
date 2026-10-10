package io.github.backaad.mobilemoney.spring.event;

import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.Verification;
import io.github.backaad.mobilemoney.WebhookEvent;
import java.util.Currency;

/**
 * Published as a Spring event when a collection or payout reaches its outcome, as verified by the SDK: a
 * webhook whose authenticity and status were checked, or a status read by the pending-payment task.
 *
 * <p>Delivery is <strong>at least once</strong>: a provider may send the same webhook several times, so a
 * listener must be idempotent, for example by recording the outcome of each merchant reference once.
 */
public sealed interface PaymentOutcomeEvent permits PaymentConfirmedEvent, PaymentFailedEvent {

    /** Where the outcome was learned. */
    enum Origin {
        /** A verified webhook or callback. */
        WEBHOOK,
        /** A status read by the pending-payment task. */
        STATUS_CHECK
    }

    /**
     * Returns the provider that processed the transaction.
     *
     * @return the provider
     */
    ProviderId provider();

    /**
     * Returns collection or payout.
     *
     * @return the type, or {@code null} if the provider did not say
     */
    TransactionType type();

    /**
     * Returns the provider's reference.
     *
     * @return the reference
     */
    String providerReference();

    /**
     * Returns the merchant's reference.
     *
     * @return the reference, or {@code null} if the provider did not return it
     */
    String merchantReference();

    /**
     * Returns the amount reported by the provider.
     *
     * @return the amount, or {@code null}
     */
    Long amount();

    /**
     * Returns the currency reported by the provider.
     *
     * @return the currency, or {@code null}
     */
    Currency currency();

    /**
     * Returns the provider's own status, for diagnostics.
     *
     * @return the raw status, or {@code null}
     */
    String rawStatus();

    /**
     * Returns how the outcome was verified.
     *
     * @return the verification
     */
    Verification verification();

    /**
     * Returns where the outcome was learned.
     *
     * @return the origin
     */
    Origin origin();

    /**
     * Builds the event for a verified webhook with a final status.
     *
     * @param event the webhook event
     * @return the outcome event
     * @throws IllegalArgumentException if the status is still pending
     */
    static PaymentOutcomeEvent of(WebhookEvent event) {
        return switch (event.status()) {
            case SUCCESSFUL -> new PaymentConfirmedEvent(event.provider(), event.type(), event.providerReference(),
                    event.merchantReference(), event.amount(), event.currency(), event.rawStatus(),
                    event.verification(), Origin.WEBHOOK);
            case FAILED -> new PaymentFailedEvent(event.provider(), event.type(), event.providerReference(),
                    event.merchantReference(), event.amount(), event.currency(), event.rawStatus(),
                    event.verification(), Origin.WEBHOOK);
            case PENDING -> throw new IllegalArgumentException("The transaction is still pending");
        };
    }

    /**
     * Builds the event for a transaction whose final status was read from the provider.
     *
     * @param transaction the transaction, with its status read again
     * @return the outcome event
     * @throws IllegalArgumentException if the status is still pending
     */
    static PaymentOutcomeEvent of(Transaction transaction) {
        if (transaction.status() == TransactionStatus.PENDING) {
            throw new IllegalArgumentException("The transaction is still pending");
        }
        return transaction.status() == TransactionStatus.SUCCESSFUL
                ? new PaymentConfirmedEvent(transaction.provider(), transaction.type(),
                        transaction.providerReference(), transaction.merchantReference(), transaction.amount(),
                        transaction.currency(), transaction.rawStatus(), Verification.STATUS_REREAD,
                        Origin.STATUS_CHECK)
                : new PaymentFailedEvent(transaction.provider(), transaction.type(), transaction.providerReference(),
                        transaction.merchantReference(), transaction.amount(), transaction.currency(),
                        transaction.rawStatus(), Verification.STATUS_REREAD, Origin.STATUS_CHECK);
    }
}
