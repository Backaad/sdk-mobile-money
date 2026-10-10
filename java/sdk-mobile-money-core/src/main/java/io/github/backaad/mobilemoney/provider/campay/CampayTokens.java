package io.github.backaad.mobilemoney.provider.campay;

import io.github.backaad.mobilemoney.ProviderAuthenticationException;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import io.github.backaad.mobilemoney.internal.AccessTokenCache;
import io.github.backaad.mobilemoney.internal.HttpCaller;
import io.github.backaad.mobilemoney.internal.Json;
import io.github.backaad.mobilemoney.internal.Replies;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Obtains Campay access tokens and keeps them until they expire, minus a safety margin of one minute.
 * Only one thread fetches a token at a time; a failed fetch is not cached.
 */
final class CampayTokens {

    /** Used when Campay gives no usable expires_in (to be confirmed); a 401 still renews the token. */
    static final Duration DEFAULT_LIFETIME = AccessTokenCache.DEFAULT_LIFETIME;

    private static final ProviderId CAMPAY = ProviderId.CAMPAY;

    private final HttpCaller http;
    private final URI tokenUri;
    private final String username;
    private final String password;
    private final Duration timeout;
    private final AccessTokenCache cache;

    CampayTokens(HttpCaller http, URI tokenUri, String username, String password, Clock clock, Duration timeout) {
        this.http = http;
        this.tokenUri = tokenUri;
        this.username = username;
        this.password = password;
        this.timeout = timeout;
        this.cache = new AccessTokenCache(CAMPAY, "Campay access token", this::fetch, clock, timeout);
    }

    /**
     * Returns a valid token, fetching a new one if needed.
     *
     * @throws ProviderAuthenticationException if Campay refuses the credentials
     * @throws ProviderUnavailableException if Campay cannot be reached; the operation that needed the
     *     token was not sent
     */
    String get() {
        return cache.get();
    }

    void invalidate(String refused) {
        cache.invalidate(refused);
    }

    String redact(String text) {
        return cache.redact(text);
    }

    private AccessTokenCache.Issued fetch() {
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
        JsonNode json = Replies.object(response, CAMPAY, false);
        String value = Json.text(json, "token");
        if (value == null || value.isBlank()) {
            throw new ProviderUnavailableException("Campay token response has no token", CAMPAY, false, null);
        }
        return new AccessTokenCache.Issued(value, Json.wholeNumber(json.get("expires_in")));
    }
}
