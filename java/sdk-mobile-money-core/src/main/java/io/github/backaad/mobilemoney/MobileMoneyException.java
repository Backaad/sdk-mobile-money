package io.github.backaad.mobilemoney;

import java.util.Optional;

/**
 * Base class of every error raised by the SDK.
 *
 * <p>Messages never contain keys, tokens, signatures or provider response bodies.
 */
public class MobileMoneyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProviderId provider;

    /**
     * Creates an exception.
     *
     * @param message description, without any secret
     * @param provider provider involved, or {@code null}
     * @param cause underlying cause, or {@code null}
     */
    public MobileMoneyException(String message, ProviderId provider, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    /**
     * Returns the provider involved, if any.
     *
     * @return the provider, or empty when the error does not concern a provider
     */
    public Optional<ProviderId> provider() {
        return Optional.ofNullable(provider);
    }
}
