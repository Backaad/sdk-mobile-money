package io.github.backaad.mobilemoney.spring.web;

import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.MobileMoneyException;
import io.github.backaad.mobilemoney.ProviderAuthenticationException;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import io.github.backaad.mobilemoney.WebhookRequest;
import io.github.backaad.mobilemoney.WebhookVerificationException;
import io.github.backaad.mobilemoney.spring.MobileMoneyWebhooks;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives provider webhooks at {@code <mobile-money.webhook.path>/<provider>}, by GET, POST or PUT.
 *
 * <p>Answers have no body, so that nothing about the verification leaks: {@code 200} once verified (even
 * if the payment is still pending), {@code 400} if the webhook is not authentic, {@code 404} for an unknown
 * provider, {@code 413} if the body is too large, {@code 503} if the status could not be read (the provider
 * sends the webhook again later), {@code 500} if the webhook cannot be verified because of the configuration.
 *
 * <p>The path must be reachable without authentication and without CSRF token: with Spring Security, permit
 * it explicitly. Behind a reverse proxy, configure {@code server.forward-headers-strategy} so that the
 * remote address used by an IP allowlist is the provider's.
 */
@RestController
public final class WebhookController {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookController.class);

    private final MobileMoneyWebhooks webhooks;
    private final Set<ProviderId> providers;

    /**
     * Creates the endpoint.
     *
     * @param webhooks the webhook handler
     * @param providers the configured providers
     */
    public WebhookController(MobileMoneyWebhooks webhooks, Set<ProviderId> providers) {
        this.webhooks = Objects.requireNonNull(webhooks, "webhooks");
        this.providers = Set.copyOf(providers);
    }

    /**
     * Receives a webhook.
     *
     * @param provider the provider id from the path
     * @param request the HTTP request
     * @return an empty answer whose status says what happened
     */
    @RequestMapping(path = "${mobile-money.webhook.path:/mobile-money/webhooks}/{provider}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<Void> receive(@PathVariable("provider") String provider, HttpServletRequest request) {
        ProviderId id;
        try {
            id = new ProviderId(provider);
        } catch (IllegalArgumentException e) {
            return status(HttpStatus.NOT_FOUND);
        }
        if (!providers.contains(id)) {
            return status(HttpStatus.NOT_FOUND);
        }
        byte[] body;
        try {
            body = readBody(request);
        } catch (IOException e) {
            return status(HttpStatus.BAD_REQUEST);
        }
        if (body == null) {
            return status(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        WebhookRequest.Builder webhook = WebhookRequest.builder()
                .method(request.getMethod())
                .path(request.getRequestURI())
                .rawQuery(request.getQueryString())
                .body(body)
                .remoteAddress(request.getRemoteAddr());
        for (String name : Collections.list(request.getHeaderNames())) {
            for (String value : Collections.list(request.getHeaders(name))) {
                webhook.header(name, value);
            }
        }
        try {
            webhooks.handle(id, webhook.build());
            return status(HttpStatus.OK);
        } catch (WebhookVerificationException e) {
            LOG.warn("{} webhook refused: {}", id, e.getMessage());
            return status(HttpStatus.BAD_REQUEST);
        } catch (ProviderUnavailableException | ProviderAuthenticationException e) {
            LOG.warn("{} webhook not processed, the status could not be read ({}): the provider will retry", id,
                    e.getClass().getSimpleName());
            return status(HttpStatus.SERVICE_UNAVAILABLE);
        } catch (ConfigurationException e) {
            LOG.error("{} webhook cannot be verified: {}", id, e.getMessage());
            return status(HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (MobileMoneyException | IllegalArgumentException e) {
            LOG.warn("{} webhook refused ({})", id, e.getClass().getSimpleName());
            return status(HttpStatus.BAD_REQUEST);
        }
    }

    /** Reads the body, or returns {@code null} if it is larger than the limit. */
    private static byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > WebhookRequest.MAX_BODY_SIZE) {
            return null;
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(WebhookRequest.MAX_BODY_SIZE + 1);
            return body.length > WebhookRequest.MAX_BODY_SIZE ? null : body;
        }
    }

    private static ResponseEntity<Void> status(HttpStatus status) {
        return ResponseEntity.status(status).build();
    }
}
