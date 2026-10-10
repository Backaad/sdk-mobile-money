package io.github.backaad.mobilemoney;

/**
 * A request with the same idempotency key is still being processed. Retry later with the SAME key:
 * never with a new one, which could charge twice.
 */
public class RequestInProgressException extends MobileMoneyException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message description, without the key
     */
    public RequestInProgressException(String message) {
        super(message, null, null);
    }
}
