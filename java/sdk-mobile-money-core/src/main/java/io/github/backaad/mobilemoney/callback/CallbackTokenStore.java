package io.github.backaad.mobilemoney.callback;

import java.util.Optional;

/**
 * Stores callback tokens. Only a digest of each token is stored, never the token itself.
 *
 * <p>{@link #consume} must be atomic: a token is accepted once. With several application instances, use
 * a shared store.
 */
public interface CallbackTokenStore {

    /**
     * Registers a token.
     *
     * @param tokenDigest SHA-256 digest of the token, in hexadecimal
     * @param expected the payment the token was created for
     */
    void register(String tokenDigest, ExpectedCallback expected);

    /**
     * Adds the provider reference once it is known.
     *
     * @param tokenDigest digest of the token
     * @param providerReference the reference
     */
    void attachProviderReference(String tokenDigest, String providerReference);

    /**
     * Reads a token without using it up.
     *
     * @param tokenDigest digest of the token
     * @return the expected payment, or empty if unknown, used or expired
     */
    Optional<ExpectedCallback> find(String tokenDigest);

    /**
     * Uses a token up: it is then refused.
     *
     * @param tokenDigest digest of the token
     * @return the expected payment, or empty if unknown, already used or expired
     */
    Optional<ExpectedCallback> consume(String tokenDigest);
}
