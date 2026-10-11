package io.github.backaad.mobilemoney;

import java.util.Optional;

/**
 * The provider refused the operation, for example an invalid number for this operator, insufficient
 * balance or an amount out of limits. Nothing was debited or paid out.
 */
public class PaymentRejectedException extends MobileMoneyException {

    private static final long serialVersionUID = 1L;

    /** The provider's code for the refusal, or null. */
    private final String providerCode;

    /**
     * Creates the exception.
     *
     * @param message description from the provider, without any secret
     * @param provider provider that refused
     * @param providerCode the provider's error code, or {@code null}
     */
    public PaymentRejectedException(String message, ProviderId provider, String providerCode) {
        super(message != null ? message : "The provider refused the request", provider, null);
        this.providerCode = providerCode;
    }

    /**
     * Returns the provider's error code.
     *
     * @return the code, or empty if the provider gave none
     */
    public Optional<String> providerCode() {
        return Optional.ofNullable(providerCode);
    }
}
