package io.github.backaad.mobilemoney;

/**
 * The provider does not know the requested transaction.
 *
 * <p>This is not a final answer about a payment: a provider may not know a transaction yet. Never mark a
 * payment as failed because of this exception; keep it pending.
 */
public class TransactionNotFoundException extends MobileMoneyException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message description, without any secret
     * @param provider provider involved
     */
    public TransactionNotFoundException(String message, ProviderId provider) {
        super(message, provider, null);
    }
}
