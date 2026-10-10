package io.github.backaad.mobilemoney.spring;

/**
 * A listener of a {@link io.github.backaad.mobilemoney.spring.event.PaymentOutcomeEvent} failed while a
 * verified webhook was being handled. The webhook itself was authentic: answer with an error, so that the
 * provider sends it again.
 */
public final class WebhookListenerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    WebhookListenerException(RuntimeException cause) {
        super("A listener of the payment outcome failed (" + cause.getClass().getSimpleName() + ")", cause);
    }
}
