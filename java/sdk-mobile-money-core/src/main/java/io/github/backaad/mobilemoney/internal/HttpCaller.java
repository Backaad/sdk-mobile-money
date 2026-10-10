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
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends HTTP requests to a provider and turns transport failures into
 * {@link ProviderUnavailableException}.
 *
 * <p>The whole exchange, response body included, is bounded by the request timeout: the JDK client's
 * own timeout stops once the headers have arrived, so a server that stalls in the middle of a body would
 * otherwise block the caller forever.
 *
 * <p>Only the method, the path, the status code and the duration are logged: never headers, query
 * strings or bodies, which can contain keys, tokens, signatures or personal data.
 */
public final class HttpCaller {

    private static final Logger LOG = LoggerFactory.getLogger(HttpCaller.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MARGIN = Duration.ofMillis(500);

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
     * @param request the request; its timeout bounds the whole exchange (30 seconds if it has none)
     * @return the response, whatever its status code
     * @throws ProviderUnavailableException on a transport failure. {@code requestMayHaveBeenProcessed}
     *     is {@code false} only when no connection could be established.
     */
    public HttpResponse<byte[]> send(HttpRequest request) {
        long start = System.nanoTime();
        String target = request.method() + " " + request.uri().getPath();
        Duration limit = request.timeout().orElse(DEFAULT_TIMEOUT).plus(MARGIN);
        CompletableFuture<HttpResponse<byte[]>> exchange =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            HttpResponse<byte[]> response = exchange.get(limit.toMillis(), TimeUnit.MILLISECONDS);
            LOG.debug("{} {} -> {} in {} ms", provider, target, response.statusCode(), elapsedMillis(start));
            return response;
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw timeout(target, start);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("Interrupted while calling " + provider, provider, true, null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                LOG.warn("{} {} failed: could not connect", provider, target);
                throw new ProviderUnavailableException(provider + " could not be reached", provider, false, null);
            }
            if (cause instanceof HttpTimeoutException) {
                throw timeout(target, start);
            }
            LOG.warn("{} {} failed: {}", provider, target,
                    cause instanceof IOException ? "IOException" : cause.getClass().getSimpleName());
            throw new ProviderUnavailableException(provider + " connection failed", provider, true, null);
        }
    }

    private ProviderUnavailableException timeout(String target, long start) {
        LOG.warn("{} {} failed: timeout after {} ms", provider, target, elapsedMillis(start));
        return new ProviderUnavailableException(provider + " did not answer in time", provider, true, null);
    }

    private static long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
