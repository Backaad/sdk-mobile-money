package io.github.backaad.mobilemoney.callback;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps callback tokens in memory. Suitable for a single application instance only. */
public final class InMemoryCallbackTokenStore implements CallbackTokenStore {

    private final ConcurrentHashMap<String, ExpectedCallback> tokens = new ConcurrentHashMap<>();
    private final Clock clock;

    /** Creates a store with the system clock. */
    public InMemoryCallbackTokenStore() {
        this(Clock.systemUTC());
    }

    /**
     * Creates a store.
     *
     * @param clock clock used for expiry
     */
    public InMemoryCallbackTokenStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void register(String tokenDigest, ExpectedCallback expected) {
        tokens.values().removeIf(e -> !clock.instant().isBefore(e.expiresAt()));
        tokens.put(Objects.requireNonNull(tokenDigest, "tokenDigest"), Objects.requireNonNull(expected, "expected"));
    }

    @Override
    public void attachProviderReference(String tokenDigest, String providerReference) {
        tokens.computeIfPresent(tokenDigest, (k, e) -> e.withProviderReference(providerReference));
    }

    @Override
    public Optional<ExpectedCallback> find(String tokenDigest) {
        ExpectedCallback expected = tokens.get(tokenDigest);
        return expected == null || !clock.instant().isBefore(expected.expiresAt())
                ? Optional.empty() : Optional.of(expected);
    }

    @Override
    public Optional<ExpectedCallback> consume(String tokenDigest) {
        ExpectedCallback expected = tokens.remove(tokenDigest);
        return expected == null || !clock.instant().isBefore(expected.expiresAt())
                ? Optional.empty() : Optional.of(expected);
    }
}
