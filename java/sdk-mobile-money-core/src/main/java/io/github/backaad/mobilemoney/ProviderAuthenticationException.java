package io.github.backaad.mobilemoney;

/** The provider refused the credentials (wrong key, disabled account, or a token rejected even after renewal). Check the configuration. */
public class ProviderAuthenticationException extends MobileMoneyException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message description, without any secret
     * @param provider provider involved, or {@code null}
     */
    public ProviderAuthenticationException(String message, ProviderId provider) {
        super(message, provider, null);
    }
}
