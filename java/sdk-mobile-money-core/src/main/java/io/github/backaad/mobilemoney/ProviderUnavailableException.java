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
    private final String providerReference;

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
        this(message, provider, requestMayHaveBeenProcessed, null, cause);
    }

    /**
     * Creates the exception for a request whose provider reference is already known (chosen by the
     * client, or returned by an earlier call), so that its status can still be read.
     *
     * @param message description, without any secret or response body
     * @param provider provider involved
     * @param requestMayHaveBeenProcessed whether the provider may have acted on the request
     * @param providerReference the provider's reference of the transaction, or {@code null}
     * @param cause underlying cause, or {@code null}
     */
    public ProviderUnavailableException(String message, ProviderId provider, boolean requestMayHaveBeenProcessed,
            String providerReference, Throwable cause) {
        super(message, provider, cause);
        this.requestMayHaveBeenProcessed = requestMayHaveBeenProcessed;
        this.providerReference = providerReference;
    }

    /**
     * Tells whether the provider may have received and acted on the request.
     *
     * @return {@code false} only when the request certainly did not reach the provider
     */
    public boolean requestMayHaveBeenProcessed() {
        return requestMayHaveBeenProcessed;
    }

    /**
     * Returns the provider's reference of the transaction, when it is known despite the failure.
     *
     * @return the reference, or empty
     */
    public java.util.Optional<String> providerReference() {
        return java.util.Optional.ofNullable(providerReference);
    }
}
