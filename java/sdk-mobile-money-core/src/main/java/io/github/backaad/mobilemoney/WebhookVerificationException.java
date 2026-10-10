package io.github.backaad.mobilemoney;

/**
 * A webhook could not be verified: missing, invalid or expired signature, or content that does not
 * match the transaction it claims to describe. The webhook must be ignored.
 */
public class WebhookVerificationException extends MobileMoneyException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message reason, without the signature or any secret
     * @param provider provider whose webhook was rejected, or {@code null}
     */
    public WebhookVerificationException(String message, ProviderId provider) {
        super(message, provider, null);
    }
}
