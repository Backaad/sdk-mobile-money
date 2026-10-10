package io.github.backaad.mobilemoney;

/**
 * The idempotency key was already used, within the last 24 hours, for a different request. This is a
 * programming error on the caller's side: each distinct request needs its own key. Nothing was sent.
 */
public class IdempotencyKeyConflictException extends MobileMoneyException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message description, without the key
     */
    public IdempotencyKeyConflictException(String message) {
        super(message, null, null);
    }
}
