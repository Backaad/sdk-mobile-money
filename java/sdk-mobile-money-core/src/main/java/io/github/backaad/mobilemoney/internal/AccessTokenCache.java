package io.github.backaad.mobilemoney.internal;

import io.github.backaad.mobilemoney.MobileMoneyException;
import io.github.backaad.mobilemoney.ProviderAuthenticationException;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a provider access token until it expires, minus a safety margin, and renews it on demand.
 *
 * <p>Only one fetch runs at a time: concurrent callers wait, for a bounded time, for its outcome, success
 * or failure, instead of fetching again. A failed fetch is not cached.
 */
public final class AccessTokenCache {

    /** Margin taken off the announced lifetime; half the lifetime for very short-lived tokens. */
    public static final Duration SAFETY_MARGIN = Duration.ofMinutes(1);
    /** Lifetime used when the provider gives no usable one; a 401 still renews the token. */
    public static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);

    private static final Logger LOG = LoggerFactory.getLogger(AccessTokenCache.class);

    /** Fetches a new token from the provider. */
    @FunctionalInterface
    public interface Fetcher {

        /**
         * Fetches a token.
         *
         * @return the token
         * @throws ProviderAuthenticationException if the provider refuses the credentials
         * @throws ProviderUnavailableException if the provider cannot be reached or answers badly; it must
         *     say that the operation needing the token was not sent
         */
        Issued fetch();
    }

    /**
     * A token as issued by the provider.
     *
     * @param value the token
     * @param expiresInSeconds the announced lifetime, or {@code null} when absent or unusable
     */
    public record Issued(String value, Long expiresInSeconds) {

        /** Validates the token. */
        public Issued {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "Issued[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    private final ProviderId provider;
    private final String name;
    private final Fetcher fetcher;
    private final Clock clock;
    private final Duration waitLimit;
    private final Object guard = new Object();

    private volatile Cached current;
    private CompletableFuture<Cached> inFlight;

    /**
     * Creates a cache.
     *
     * @param provider the provider, for errors
     * @param name what the token is, for messages, for example {@code "Campay access token"}
     * @param fetcher fetches a new token
     * @param clock the clock
     * @param fetchTimeout the timeout of a fetch; waiting callers give up after twice this time
     */
    public AccessTokenCache(ProviderId provider, String name, Fetcher fetcher, Clock clock, Duration fetchTimeout) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.name = Objects.requireNonNull(name, "name");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        // Long enough for the fetch in flight to finish or fail on its own timeout.
        this.waitLimit = fetchTimeout.multipliedBy(2).plusSeconds(1);
    }

    /**
     * Returns a valid token, fetching a new one if needed.
     *
     * @return the token
     * @throws ProviderAuthenticationException if the provider refuses the credentials
     * @throws ProviderUnavailableException if no token could be obtained; the operation that needed it was
     *     not sent
     */
    public String get() {
        Cached token = current;
        if (token != null && token.isValidAt(clock.instant())) {
            return token.value();
        }
        CompletableFuture<Cached> fetch;
        boolean leader = false;
        synchronized (guard) {
            token = current;
            if (token != null && token.isValidAt(clock.instant())) {
                return token.value();
            }
            if (inFlight == null) {
                inFlight = new CompletableFuture<>();
                leader = true;
            }
            fetch = inFlight;
        }
        if (leader) {
            try {
                Cached fresh = fetch();
                current = fresh;
                fetch.complete(fresh);
            } catch (RuntimeException e) {
                fetch.completeExceptionally(e);
            } finally {
                synchronized (guard) {
                    inFlight = null;
                }
            }
        }
        try {
            return fetch.get(waitLimit.toMillis(), TimeUnit.MILLISECONDS).value();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof MobileMoneyException cause) {
                throw cause;
            }
            throw new ProviderUnavailableException("Could not obtain a " + name, provider, false, null);
        } catch (TimeoutException e) {
            throw new ProviderUnavailableException("Timed out waiting for a " + name, provider, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("Interrupted while waiting for a " + name, provider, false, null);
        }
    }

    /**
     * Forgets a token that the provider refused, unless another thread already replaced it.
     *
     * @param refused the token that got a 401
     */
    public void invalidate(String refused) {
        synchronized (guard) {
            Cached token = current;
            if (token != null && ConstantTime.equals(token.value(), refused)) {
                current = null;
            }
        }
    }

    /**
     * Replaces the current token, if it appears in a text, with {@code ***}.
     *
     * @param text a text that will be shown or logged
     * @return the text without the token
     */
    public String redact(String text) {
        Cached token = current;
        if (text == null || token == null) {
            return text;
        }
        return text.replace(token.value(), "***");
    }

    private Cached fetch() {
        Issued issued = fetcher.fetch();
        if (issued == null || issued.value().isBlank()) {
            throw new ProviderUnavailableException("The " + name + " response has no token", provider, false, null);
        }
        Long expiresIn = issued.expiresInSeconds();
        Duration lifetime;
        if (expiresIn == null || expiresIn <= 0) {
            LOG.warn("{}: the {} response has no usable lifetime, the token is kept {} at most",
                    provider, name, DEFAULT_LIFETIME);
            lifetime = DEFAULT_LIFETIME;
        } else {
            Duration announced = Duration.ofSeconds(expiresIn);
            Duration half = announced.dividedBy(2);
            lifetime = announced.minus(half.compareTo(SAFETY_MARGIN) < 0 ? half : SAFETY_MARGIN);
        }
        Instant expiresAt = clock.instant().plus(lifetime);
        LOG.debug("{}: obtained a {}, cached until {}", provider, name, expiresAt);
        return new Cached(issued.value(), expiresAt);
    }

    private record Cached(String value, Instant expiresAt) {

        boolean isValidAt(Instant now) {
            return now.isBefore(expiresAt);
        }

        @Override
        public String toString() {
            return "Cached[expiresAt=" + expiresAt + "]";
        }
    }
}
