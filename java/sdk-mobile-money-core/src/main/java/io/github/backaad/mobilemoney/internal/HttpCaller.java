package io.github.backaad.mobilemoney.internal;

import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends HTTP requests to a provider and turns transport failures into
 * {@link ProviderUnavailableException}.
 *
 * <p>Only the method, the path, the status code and the duration are logged: never headers, query
 * strings or bodies, which can contain keys, tokens, signatures or personal data.
 */
public final class HttpCaller {

    private static final Logger LOG = LoggerFactory.getLogger(HttpCaller.class);

    private final HttpClient client;
    private final ProviderId provider;

    /**
     * Creates a caller.
     *
     * @param client the HTTP client
     * @param provider the provider called, for logs and errors
     */
    public HttpCaller(HttpClient client, ProviderId provider) {
        this.client = Objects.requireNonNull(client, "client");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * Sends a request.
     *
     * @param request the request; it should carry a timeout
     * @return the response, whatever its status code
     * @throws ProviderUnavailableException on a transport failure. {@code requestMayHaveBeenProcessed}
     *     is {@code false} only when no connection could be established.
     */
    public HttpResponse<byte[]> send(HttpRequest request) {
        long start = System.nanoTime();
        String target = request.method() + " " + request.uri().getPath();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            LOG.debug("{} {} -> {} in {} ms", provider, target, response.statusCode(), elapsedMillis(start));
            return response;
        } catch (HttpConnectTimeoutException | ConnectException e) {
            LOG.warn("{} {} failed: could not connect", provider, target);
            throw new ProviderUnavailableException(provider + " could not be reached", provider, false, null);
        } catch (HttpTimeoutException e) {
            LOG.warn("{} {} failed: timeout after {} ms", provider, target, elapsedMillis(start));
            throw new ProviderUnavailableException(provider + " did not answer in time", provider, true, null);
        } catch (IOException e) {
            LOG.warn("{} {} failed: {}", provider, target, e.getClass().getSimpleName());
            throw new ProviderUnavailableException(provider + " connection failed", provider, true, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("Interrupted while calling " + provider, provider, true, null);
        }
    }

    private static long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
