package io.github.backaad.mobilemoney.spring;

import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.WebhookEvent;
import io.github.backaad.mobilemoney.WebhookRequest;
import io.github.backaad.mobilemoney.spring.event.PaymentOutcomeEvent;
import io.github.backaad.mobilemoney.spring.pending.PendingTransactionStore;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Handles a webhook as received (raw body, headers, query): {@link MobileMoney#handleWebhook} checks its
 * signature or single-use token and reads the status again from the provider when it can, then a
 * {@link PaymentOutcomeEvent} is published when the payment succeeded or failed.
 *
 * <p>The endpoint registered by the starter uses it; an application with its own endpoint can call it too.
 */
public final class MobileMoneyWebhooks {

    private final MobileMoney mobileMoney;
    private final ApplicationEventPublisher events;
    private final PendingTransactionStore pending;

    /**
     * Creates the handler.
     *
     * @param mobileMoney the facade
     * @param events publishes the outcome events
     * @param pending the followed pending transactions, or {@code null}
     */
    public MobileMoneyWebhooks(MobileMoney mobileMoney, ApplicationEventPublisher events,
            PendingTransactionStore pending) {
        this.mobileMoney = Objects.requireNonNull(mobileMoney, "mobileMoney");
        this.events = Objects.requireNonNull(events, "events");
        this.pending = pending;
    }

    /**
     * Verifies a webhook and publishes the outcome. A Tara single-use callback token is consumed only after
     * the listeners have run, so that the provider's next delivery is accepted if one of them fails.
     *
     * @param provider the provider the webhook claims to come from (from the endpoint path)
     * @param request the webhook as received
     * @return the verified event
     * @throws io.github.backaad.mobilemoney.WebhookVerificationException if the webhook is not authentic
     * @throws io.github.backaad.mobilemoney.ProviderUnavailableException if the status could not be read;
     *     answer with an error so that the provider may send the webhook again
     * @throws WebhookListenerException if a listener of the outcome event failed; answer with an error so
     *     that the provider may send the webhook again
     */
    public WebhookEvent handle(ProviderId provider, WebhookRequest request) {
        WebhookEvent event = mobileMoney.handleWebhook(provider, request, verified -> {
            if (verified.status() != TransactionStatus.PENDING) {
                try {
                    events.publishEvent(PaymentOutcomeEvent.of(verified));
                } catch (RuntimeException e) {
                    throw new WebhookListenerException(e);
                }
            }
        });
        if (event.status() != TransactionStatus.PENDING && pending != null && event.providerReference() != null) {
            pending.remove(event.provider(), event.providerReference());
        }
        return event;
    }
}
