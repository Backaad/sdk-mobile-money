package io.github.backaad.mobilemoney;

/**
 * The provider could not be reached or did not answer properly: network error, timeout, HTTP 5xx,
 * empty or unreadable response. The operation may be retried later.
 *
 * <p>{@link #requestMayHaveBeenProcessed()} tells whether the provider may have received and acted on
 * the request. When it is {@code true}, sending the same debit again could charge twice: the status must
 * be checked first.
 */
public class ProviderUnavailableException extends MobileMoneyException {

    private static final long serialVersionUID = 1L;

    private final boolean requestMayHaveBeenProcessed;

    /**
     * Creates the exception.
     *
     * @param message description, without any secret or response body
     * @param provider provider involved
     * @param requestMayHaveBeenProcessed whether the provider may have acted on the request
     * @param cause underlying cause, or {@code null}
     */
    public ProviderUnavailableException(
            String message, ProviderId provider, boolean requestMayHaveBeenProcessed, Throwable cause) {
        super(message, provider, cause);
        this.requestMayHaveBeenProcessed = requestMayHaveBeenProcessed;
    }

    /**
     * Tells whether the provider may have received and acted on the request.
     *
     * @return {@code false} only when the request certainly did not reach the provider
     */
    public boolean requestMayHaveBeenProcessed() {
        return requestMayHaveBeenProcessed;
    }
}
