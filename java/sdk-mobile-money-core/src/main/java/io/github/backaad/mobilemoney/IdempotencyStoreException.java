package io.github.backaad.mobilemoney;

/**
 * The idempotency store could not be used. Nothing was sent to the provider: retry later with the same
 * idempotency key.
 */
public class IdempotencyStoreException extends MobileMoneyException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message description, without the key
     * @param cause underlying cause, or {@code null}
     */
    public IdempotencyStoreException(String message, Throwable cause) {
        super(message, null, cause);
    }
}
