package io.github.backaad.mobilemoney.provider.tara;

import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.Currencies;
import io.github.backaad.mobilemoney.PaymentRejectedException;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.PayoutRequest;
import io.github.backaad.mobilemoney.ProviderAuthenticationException;
import io.github.backaad.mobilemoney.ProviderCapabilities;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.UnsupportedCapabilityException;
import io.github.backaad.mobilemoney.WebhookRequest;
import io.github.backaad.mobilemoney.WebhookSignature;
import io.github.backaad.mobilemoney.WebhookVerificationException;
import io.github.backaad.mobilemoney.internal.HttpCaller;
import io.github.backaad.mobilemoney.internal.Json;
import io.github.backaad.mobilemoney.internal.Replies;
import io.github.backaad.mobilemoney.internal.Urls;
import io.github.backaad.mobilemoney.phone.PhoneNumber;
import io.github.backaad.mobilemoney.phone.ProviderPhoneFormat;
import io.github.backaad.mobilemoney.provider.PaymentProvider;
import io.github.backaad.mobilemoney.provider.StatusReport;
import io.github.backaad.mobilemoney.provider.WebhookNotification;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tara provider. <strong>Experimental.</strong>
 *
 * <p>Tara has no status query and does not sign its callbacks. Each payment therefore gets a single-use
 * callback URL carrying a 128-bit random token, created and checked by
 * {@link io.github.backaad.mobilemoney.MobileMoney}: the request must have a {@code callbackUrl}, and the
 * callback must match the expected reference, amount and number. An IP allowlist can be added.
 *
 * <p>The merchant reference is sent as Tara's {@code productId}, so that a callback arriving before the
 * answer to the payment request can still be matched. Payouts are not supported.
 *
 * <p>The API key travels in the request body; such bodies are never logged. What was checked and what is
 * still to be confirmed is listed in {@code docs/providers/tara.md}.
 */
public final class TaraProvider implements PaymentProvider {

    private static final ProviderId TARA = ProviderId.TARA;
    private static final URI DEFAULT_BASE_URL = URI.create("https://www.dklo.co");
    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(true, false, false,
            WebhookSignature.CALLBACK_TOKEN, Set.of(Currencies.XAF));

    private final URI baseUrl;
    private final String apiKey;
    private final String businessId;
    private final Set<String> allowedAddresses;
    private final Duration requestTimeout;
    private final HttpCaller http;

    private TaraProvider(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.apiKey = builder.apiKey;
        this.businessId = builder.businessId;
        this.allowedAddresses = builder.allowedAddresses;
        this.requestTimeout = builder.requestTimeout;
        HttpClient client = builder.httpClient != null ? builder.httpClient : HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.http = new HttpCaller(client, TARA);
    }

    /**
     * Starts the configuration.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ProviderId id() {
        return TARA;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Transaction collect(PaymentRequest request) {
        if (request.callbackUrl() == null) {
            throw new UnsupportedCapabilityException("Tara needs a single-use callback URL", TARA);
        }
        ObjectNode body = Json.object();
        body.put("apiKey", apiKey);
        body.put("businessId", businessId);
        body.put("productId", request.merchantReference());
        body.put("productName", request.description());
        body.put("productDescription", request.description());
        body.put("productPrice", request.amount());
        body.put("phoneNumber", format(request.phoneNumber()));
        body.put("webHookUrl", request.callbackUrl().toString());
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tara/mobilepay"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.write(body)))
                .build();
        HttpResponse<byte[]> response = http.send(httpRequest);
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new ProviderAuthenticationException("Tara refused the API key (HTTP " + status + ")", TARA);
        }
        if (status == 408 || status == 429) {
            throw new ProviderUnavailableException(
                    "Tara did not accept the request now (HTTP " + status + ")", TARA, false, null);
        }
        if (status >= 400 && status < 500 && status != 409) {
            throw rejection(status, Replies.objectOrNull(response.body()));
        }
        if (status != 200 && status != 201) {
            throw new ProviderUnavailableException(
                    "Tara answered HTTP " + status, TARA, true, request.merchantReference(), null);
        }
        JsonNode json = Replies.object(response, TARA, true);
        String rawStatus = Replies.blankToNull(Json.text(json, "status"));
        String normalized = rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("SUCCESS")) {
            // The payment request was accepted; the customer has not paid yet.
            return new Transaction(TARA, TransactionType.COLLECTION, request.merchantReference(),
                    request.merchantReference(), request.amount(), request.currency(), TransactionStatus.PENDING,
                    rawStatus);
        }
        if (normalized.equals("FAILURE") || normalized.equals("FAILED") || normalized.equals("ERROR")) {
            throw rejection(status, json);
        }
        throw new ProviderUnavailableException("Tara answered with an unexpected status", TARA, true,
                request.merchantReference(), null);
    }

    @Override
    public Transaction payout(PayoutRequest request) {
        throw new UnsupportedCapabilityException("Tara payouts are not supported", TARA);
    }

    @Override
    public StatusReport fetchStatus(TransactionType type, String providerReference) {
        throw new UnsupportedCapabilityException("Tara has no status query", TARA);
    }

    @Override
    public WebhookNotification parseWebhook(WebhookRequest request) {
        if (!allowedAddresses.isEmpty()) {
            String address = request.remoteAddress().orElse(null);
            if (address == null || !allowedAddresses.contains(address)) {
                throw new WebhookVerificationException("Callback from an address that is not allowed", TARA);
            }
        }
        if (!request.method().equalsIgnoreCase("POST")) {
            throw new WebhookVerificationException("Unexpected callback method", TARA);
        }
        JsonNode json = Replies.objectOrNull(request.body());
        if (json == null) {
            throw new WebhookVerificationException("Malformed callback", TARA);
        }
        String reference = Replies.blankToNull(Json.text(json, "productId"));
        if (reference == null) {
            throw new WebhookVerificationException("Callback without a reference", TARA);
        }
        Long amount = Replies.wholeAmount(json.get("amount"));
        if (amount == null) {
            amount = Replies.wholeAmount(json.get("productPrice"));
        }
        String phone = Replies.blankToNull(Json.text(json, "phoneNumber"));
        String e164 = null;
        if (phone != null) {
            try {
                e164 = PhoneNumber.parse(phone).e164();
            } catch (RuntimeException e) {
                throw new WebhookVerificationException("Callback with an invalid phone number", TARA);
            }
        }
        // The productId is the merchant reference: it identifies the payment on both sides.
        return new WebhookNotification(reference, reference, TransactionType.COLLECTION,
                Replies.blankToNull(Json.text(json, "status")), amount, e164);
    }

    @Override
    public TransactionStatus mapClaimedStatus(String rawStatus) {
        if (rawStatus == null) {
            return TransactionStatus.PENDING;
        }
        switch (rawStatus.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESS":
            case "SUCCESSFUL":
                return TransactionStatus.SUCCESSFUL;
            case "FAILURE":
            case "FAILED":
                return TransactionStatus.FAILED;
            default:
                return TransactionStatus.PENDING;
        }
    }

    @Override
    public String toString() {
        return "TaraProvider[baseUrl=" + baseUrl + ", allowlist=" + allowedAddresses.size() + " addresses]";
    }

    private PaymentRejectedException rejection(int status, JsonNode json) {
        String message = Replies.safeText(Json.text(json, "message"), Arrays.asList(apiKey, businessId));
        return new PaymentRejectedException("Tara refused the request (HTTP " + status + ")"
                + (message != null ? ": " + message : ""), TARA, null);
    }

    private static String format(PhoneNumber number) {
        return ProviderPhoneFormat.TARA.format(number);
    }

    /** Configures a {@link TaraProvider}. */
    public static final class Builder {

        private URI baseUrl;
        private String apiKey;
        private String businessId;
        private Set<String> allowedAddresses = Set.of();
        private HttpClient httpClient;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);

        private Builder() {
        }

        /**
         * Replaces the base URL ({@code https://www.dklo.co}), for example to point to a mock server in
         * tests. Must be HTTPS, except for localhost.
         *
         * @param baseUrl the base URL
         * @return this builder
         */
        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the API key. Required.
         *
         * @param apiKey the key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the business identifier. Required.
         *
         * @param businessId the identifier
         * @return this builder
         */
        public Builder businessId(String businessId) {
            this.businessId = businessId;
            return this;
        }

        /**
         * Accepts callbacks only from these IP addresses, compared with
         * {@link WebhookRequest#remoteAddress()}. Optional: empty by default, which accepts any address.
         *
         * @param addresses the addresses, as written by the web server
         * @return this builder
         */
        public Builder allowedCallbackAddresses(List<String> addresses) {
            this.allowedAddresses = Set.copyOf(addresses);
            return this;
        }

        /**
         * Uses a specific HTTP client. Per-request timeouts still apply. It must not follow redirects.
         *
         * @param httpClient the client
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Sets the connection timeout of the default HTTP client. Defaults to 10 seconds.
         *
         * @param connectTimeout the timeout
         * @return this builder
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * Sets the timeout of each request. Defaults to 30 seconds.
         *
         * @param requestTimeout the timeout
         * @return this builder
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /**
         * Builds the provider.
         *
         * @return the provider
         * @throws ConfigurationException if a required setting is missing or dangerous
         */
        public TaraProvider build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new ConfigurationException("Tara API key is required", TARA);
            }
            if (businessId == null || businessId.isBlank()) {
                throw new ConfigurationException("Tara business id is required", TARA);
            }
            if (allowedAddresses.stream().anyMatch(a -> a == null || a.isBlank())) {
                throw new ConfigurationException("Tara callback addresses must not be blank", TARA);
            }
            if (httpClient != null && httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
                throw new ConfigurationException(
                        "The HTTP client must not follow redirects: the API key could be sent elsewhere", TARA);
            }
            if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                    || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new ConfigurationException("Tara timeouts must be positive", TARA);
            }
            try {
                baseUrl = Urls.requireSafeBaseUrl(baseUrl != null ? baseUrl : DEFAULT_BASE_URL);
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException("Tara base URL: " + e.getMessage(), TARA);
            }
            return new TaraProvider(this);
        }
    }
}
