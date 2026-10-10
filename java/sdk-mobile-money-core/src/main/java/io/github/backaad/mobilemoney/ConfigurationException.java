package io.github.backaad.mobilemoney;

/** The configuration is incomplete or dangerous. Raised when building a client, so that the application does not start with it. */
public class ConfigurationException extends MobileMoneyException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message description, without any secret
     * @param provider provider involved, or {@code null}
     */
    public ConfigurationException(String message, ProviderId provider) {
        super(message, provider, null);
    }
}
