package io.github.backaad.mobilemoney;

/**
 * The provider cannot do what was asked, for example a payout with a provider that only collects, or a
 * currency it does not accept. Nothing was sent.
 */
public class UnsupportedCapabilityException extends MobileMoneyException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is not supported
     * @param provider the provider
     */
    public UnsupportedCapabilityException(String message, ProviderId provider) {
        super(message, provider, null);
    }
}
