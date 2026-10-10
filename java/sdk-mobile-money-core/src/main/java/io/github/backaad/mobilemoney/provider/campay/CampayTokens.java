package io.github.backaad.mobilemoney.provider.campay;

import io.github.backaad.mobilemoney.MobileMoneyException;
import io.github.backaad.mobilemoney.ProviderAuthenticationException;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import io.github.backaad.mobilemoney.internal.ConstantTime;
import io.github.backaad.mobilemoney.internal.HttpCaller;
import io.github.backaad.mobilemoney.internal.Json;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Obtains Campay access tokens and keeps them until they expire, minus a safety margin of one minute.
 * Only one thread fetches a token at a time; a failed fetch is not cached.
 */
final class CampayTokens {

    static final Duration SAFETY_MARGIN = Duration.ofMinutes(1);
    /** Used when Campay gives no usable expires_in (to be confirmed); a 401 still renews the token. */
    static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);

    private static final Logger LOG = LoggerFactory.getLogger(CampayTokens.class);
    private static final ProviderId CAMPAY = ProviderId.CAMPAY;

    private final HttpCaller http;
    private final URI tokenUri;
    private final String username;
    private final String password;
    private final Clock clock;
    private final Duration timeout;
    private final Duration waitLimit;
    private final Object guard = new Object();

    private volatile CachedToken current;
    private CompletableFuture<CachedToken> inFlight;

    CampayTokens(HttpCaller http, URI tokenUri, String username, String password, Clock clock, Duration timeout) {
        this.http = http;
        this.tokenUri = tokenUri;
        this.username = username;
        this.password = password;
        this.clock = clock;
        this.timeout = timeout;
        // Long enough for the fetch in flight to finish or fail on its own timeout.
        this.waitLimit = timeout.multipliedBy(2).plusSeconds(1);
    }

    /**
     * Returns a valid token, fetching a new one if needed. Only one fetch runs at a time: concurrent
     * callers wait, for a bounded time, for its outcome, success or failure, instead of fetching again.
     *
     * @throws ProviderAuthenticationException if Campay refuses the credentials
     * @throws ProviderUnavailableException if Campay cannot be reached; the operation that needed the
     *     token was not sent
     */
    String get() {
        CachedToken token = current;
        if (token != null && token.isValidAt(clock.instant())) {
            return token.value();
        }
        CompletableFuture<CachedToken> fetch;
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
                CachedToken fresh = fetch();
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
            throw new ProviderUnavailableException("Could not obtain a Campay access token", CAMPAY, false, null);
        } catch (TimeoutException e) {
            throw new ProviderUnavailableException(
                    "Timed out waiting for a Campay access token", CAMPAY, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException(
                    "Interrupted while waiting for a Campay access token", CAMPAY, false, null);
        }
    }

    /**
     * Forgets a token that Campay refused, unless another thread already replaced it.
     *
     * @param refused the token that got a 401
     */
    void invalidate(String refused) {
        synchronized (guard) {
            CachedToken token = current;
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
    String redact(String text) {
        CachedToken token = current;
        if (text == null || token == null) {
            return text;
        }
        return text.replace(token.value(), "***");
    }

    private CachedToken fetch() {
        ObjectNode body = Json.object();
        body.put("username", username);
        body.put("password", password);
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.write(body)))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = http.send(request);
        } catch (ProviderUnavailableException e) {
            // No debit was sent: only the token request failed.
            throw new ProviderUnavailableException(
                    "Could not obtain a Campay access token: " + e.getMessage(), CAMPAY, false, null);
        }

        int status = response.statusCode();
        if (status == 400 || status == 401 || status == 403) {
            throw new ProviderAuthenticationException(
                    "Campay refused the credentials (HTTP " + status + "): check the application username and password",
                    CAMPAY);
        }
        if (status != 200) {
            throw new ProviderUnavailableException(
                    "Campay token endpoint answered HTTP " + status, CAMPAY, false, null);
        }

        JsonNode json;
        try {
            json = Json.parse(response.body());
        } catch (JacksonException e) {
            throw new ProviderUnavailableException("Campay token response is not valid JSON", CAMPAY, false, null);
        }
        String value = Json.text(json, "token");
        if (value == null || value.isBlank()) {
            throw new ProviderUnavailableException("Campay token response has no token", CAMPAY, false, null);
        }

        Instant now = clock.instant();
        Long expiresIn = seconds(json == null ? null : json.get("expires_in"));
        Duration lifetime;
        if (expiresIn == null || expiresIn <= 0) {
            LOG.warn("Campay token response has no usable expires_in: the token is kept {} at most",
                    DEFAULT_LIFETIME);
            lifetime = DEFAULT_LIFETIME;
        } else {
            Duration announced = Duration.ofSeconds(expiresIn);
            // One minute of margin, or half the lifetime for very short-lived tokens.
            Duration margin = announced.dividedBy(2).compareTo(SAFETY_MARGIN) < 0 ? announced.dividedBy(2) : SAFETY_MARGIN;
            lifetime = announced.minus(margin);
        }
        Instant expiresAt = now.plus(lifetime);
        LOG.debug("Obtained a Campay access token, cached until {}", expiresAt);
        return new CachedToken(value, expiresAt);
    }

    private static Long seconds(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isString()) {
            try {
                return Long.parseLong(node.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private record CachedToken(String value, Instant expiresAt) {

        boolean isValidAt(Instant now) {
            return now.isBefore(expiresAt);
        }

        @Override
        public String toString() {
            return "CachedToken[expiresAt=" + expiresAt + "]";
        }
    }
}
