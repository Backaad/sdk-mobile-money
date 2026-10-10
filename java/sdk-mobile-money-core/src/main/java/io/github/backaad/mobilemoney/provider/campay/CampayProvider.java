package io.github.backaad.mobilemoney.provider.campay;

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
import io.github.backaad.mobilemoney.TransactionNotFoundException;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.WebhookRequest;
import io.github.backaad.mobilemoney.WebhookSignature;
import io.github.backaad.mobilemoney.WebhookVerificationException;
import io.github.backaad.mobilemoney.internal.Hs256Jwt;
import io.github.backaad.mobilemoney.internal.HttpCaller;
import io.github.backaad.mobilemoney.internal.Json;
import io.github.backaad.mobilemoney.internal.Urls;
import io.github.backaad.mobilemoney.phone.PhoneNumber;
import io.github.backaad.mobilemoney.phone.ProviderPhoneFormat;
import io.github.backaad.mobilemoney.provider.PaymentProvider;
import io.github.backaad.mobilemoney.provider.StatusReport;
import io.github.backaad.mobilemoney.provider.WebhookNotification;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Campay provider: collections, payouts, status reads and webhooks.
 *
 * <p>Authentication uses the application's username and password: the access token is kept until it
 * expires (minus one minute), and renewed once if Campay answers 401. Webhooks are configured in the
 * Campay dashboard; their {@code signature} parameter is verified as a JWT signed with HS256 with the
 * application's webhook key (scheme taken from third-party code, to be confirmed against Campay's
 * documentation).
 *
 * <p>What was checked against Campay's documentation and what is still to be confirmed is listed in
 * {@code docs/providers/campay.md}.
 */
public final class CampayProvider implements PaymentProvider {

    private static final ProviderId CAMPAY = ProviderId.CAMPAY;
    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(true, true, true,
            WebhookSignature.JWT_HS256, Set.of(Currencies.XAF));
    // Starts with a letter or digit, so that "." or ".." can never become a path segment.
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final int MAX_ERROR_MESSAGE_LENGTH = 200;
    private static final List<String> WEBHOOK_FIELDS = List.of(
            "signature", "reference", "external_reference", "status", "endpoint", "amount", "currency");

    private final CampayEnvironment environment;
    private final URI baseUrl;
    private final HttpCaller http;
    private final Duration requestTimeout;
    private final CampayTokens tokens;
    private final Hs256Jwt webhookJwt;
    private final List<String> secrets;

    private CampayProvider(Builder builder) {
        this.environment = builder.environment;
        this.baseUrl = builder.baseUrl;
        this.requestTimeout = builder.requestTimeout;
        HttpClient client = builder.httpClient != null ? builder.httpClient : HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.http = new HttpCaller(client, CAMPAY);
        this.tokens = new CampayTokens(http, endpoint("/api/token/"), builder.username, builder.password,
                builder.clock, requestTimeout);
        this.secrets = java.util.Arrays.asList(builder.username, builder.password, builder.webhookKey);
        this.webhookJwt = builder.webhookKey == null ? null : new Hs256Jwt(
                builder.webhookKey.getBytes(StandardCharsets.UTF_8), builder.clock, Duration.ofSeconds(30), false);
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
        return CAMPAY;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Transaction collect(PaymentRequest request) {
        ObjectNode body = Json.object();
        body.put("amount", Long.toString(request.amount()));
        body.put("currency", request.currency().getCurrencyCode());
        body.put("from", format(request.phoneNumber()));
        body.put("description", request.description());
        body.put("external_reference", request.merchantReference());
        JsonNode json = send("/api/collect/", body);
        return accepted(json, TransactionType.COLLECTION, request.merchantReference(), request.amount(),
                request.currency());
    }

    @Override
    public Transaction payout(PayoutRequest request) {
        ObjectNode body = Json.object();
        body.put("amount", Long.toString(request.amount()));
        body.put("currency", request.currency().getCurrencyCode());
        body.put("to", format(request.phoneNumber()));
        body.put("description", request.description());
        body.put("external_reference", request.merchantReference());
        JsonNode json = send("/api/withdraw/", body);
        return accepted(json, TransactionType.PAYOUT, request.merchantReference(), request.amount(),
                request.currency());
    }

    @Override
    public StatusReport fetchStatus(TransactionType type, String providerReference) {
        if (providerReference == null || !REFERENCE.matcher(providerReference).matches()) {
            throw new TransactionNotFoundException("Not a Campay reference", CAMPAY);
        }
        HttpResponse<byte[]> response = authorized(token -> HttpRequest.newBuilder(
                        endpoint("/api/transaction/" + providerReference + "/"))
                .timeout(requestTimeout)
                .header("Authorization", "Token " + token)
                .header("Accept", "application/json")
                .GET()
                .build());
        int status = response.statusCode();
        if (status == 404) {
            throw new TransactionNotFoundException("Campay does not know this transaction", CAMPAY);
        }
        if (status != 200) {
            throw new ProviderUnavailableException(
                    "Campay status read answered HTTP " + status, CAMPAY, false, null);
        }
        JsonNode json = parse(response, false);
        String rawStatus = Json.text(json, "status");
        String reference = Json.text(json, "reference");
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new ProviderUnavailableException("Campay status response has no status", CAMPAY, false, null);
        }
        return new StatusReport(
                reference != null ? reference : providerReference,
                Json.text(json, "external_reference"),
                typeOf(Json.text(json, "endpoint")),
                mapStatus(rawStatus),
                rawStatus,
                wholeAmount(json.get("amount")),
                currencyOf(Json.text(json, "currency")));
    }

    @Override
    public WebhookNotification parseWebhook(WebhookRequest request) {
        if (webhookJwt == null) {
            throw new ConfigurationException(
                    "The Campay webhook key is not configured: webhooks cannot be verified", CAMPAY);
        }
        String method = request.method().toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("POST")) {
            throw new WebhookVerificationException("Unexpected webhook method", CAMPAY);
        }
        Map<String, String> fields = webhookFields(request);

        String signature = fields.get("signature");
        if (signature == null || signature.isBlank()) {
            throw new WebhookVerificationException("Unsigned webhook", CAMPAY);
        }
        JsonNode claims;
        try {
            claims = webhookJwt.verify(signature);
        } catch (Hs256Jwt.InvalidJwtException e) {
            throw new WebhookVerificationException("Invalid webhook signature: " + e.getMessage(), CAMPAY);
        }

        String reference = fields.get("reference");
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new WebhookVerificationException("Webhook without a valid reference", CAMPAY);
        }
        String externalReference = blankToNull(fields.get("external_reference"));
        // If the signed claims carry references, they must match the parameters.
        requireSameIfSigned(claims, "reference", reference);
        requireSameIfSigned(claims, "external_reference", externalReference);
        return new WebhookNotification(reference, externalReference, typeOf(fields.get("endpoint")),
                blankToNull(fields.get("status")));
    }

    @Override
    public String toString() {
        return "CampayProvider[environment=" + environment + ", baseUrl=" + baseUrl
                + ", webhookKeyConfigured=" + (webhookJwt != null) + "]";
    }

    private JsonNode send(String path, ObjectNode body) {
        byte[] bytes = Json.write(body);
        HttpResponse<byte[]> response = authorized(token -> HttpRequest.newBuilder(endpoint(path))
                .timeout(requestTimeout)
                .header("Authorization", "Token " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build());
        int status = response.statusCode();
        if (status == 200 || status == 201) {
            return parse(response, true);
        }
        if (status == 403) {
            throw new ProviderAuthenticationException("Campay refused the operation (HTTP 403): check that it is"
                    + " enabled for this application in the Campay dashboard", CAMPAY);
        }
        if (status == 408 || status == 429) {
            throw new ProviderUnavailableException(
                    "Campay did not accept the request now (HTTP " + status + ")", CAMPAY, false, null);
        }
        if (status == 409) {
            // A conflict may mean the transaction already exists: never treat it as a refusal.
            throw new ProviderUnavailableException("Campay answered HTTP 409", CAMPAY, true, null);
        }
        if (status >= 400 && status < 500) {
            throw rejection(response);
        }
        // 5xx, 3xx or anything else: the request may have been processed.
        throw new ProviderUnavailableException("Campay answered HTTP " + status, CAMPAY, true, null);
    }

    /**
     * Sends a request with a valid token. On a 401, the token is renewed and the same request sent once
     * more: a request refused with 401 was not processed.
     */
    private HttpResponse<byte[]> authorized(java.util.function.Function<String, HttpRequest> requestFor) {
        String token = tokens.get();
        HttpResponse<byte[]> response = http.send(requestFor.apply(token));
        if (response.statusCode() != 401) {
            return response;
        }
        tokens.invalidate(token);
        String renewed = tokens.get();
        response = http.send(requestFor.apply(renewed));
        if (response.statusCode() == 401) {
            tokens.invalidate(renewed);
            throw new ProviderAuthenticationException(
                    "Campay refused the access token even after renewing it (HTTP 401)", CAMPAY);
        }
        return response;
    }

    private Transaction accepted(JsonNode json, TransactionType type, String merchantReference, long amount,
            Currency currency) {
        String reference = Json.text(json, "reference");
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new ProviderUnavailableException(
                    "Campay accepted the request but returned no usable reference", CAMPAY, true, null);
        }
        String rawStatus = blankToNull(Json.text(json, "status"));
        TransactionStatus status = rawStatus == null ? TransactionStatus.PENDING : mapStatus(rawStatus);
        return new Transaction(CAMPAY, type, reference, merchantReference, amount, currency, status, rawStatus);
    }

    private static JsonNode parse(HttpResponse<byte[]> response, boolean requestMayHaveBeenProcessed) {
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new ProviderUnavailableException(
                    "Campay returned an empty response", CAMPAY, requestMayHaveBeenProcessed, null);
        }
        JsonNode json;
        try {
            json = Json.parse(body);
        } catch (JacksonException e) {
            throw new ProviderUnavailableException(
                    "Campay returned a response that is not JSON", CAMPAY, requestMayHaveBeenProcessed, null);
        }
        if (json == null || !json.isObject()) {
            throw new ProviderUnavailableException(
                    "Campay returned an unexpected response", CAMPAY, requestMayHaveBeenProcessed, null);
        }
        return json;
    }

    private PaymentRejectedException rejection(HttpResponse<byte[]> response) {
        String message = null;
        String code = null;
        try {
            JsonNode json = Json.parse(response.body());
            message = Json.text(json, "message");
            code = Json.text(json, "error_code");
        } catch (JacksonException | IllegalArgumentException e) {
            // No readable details: a generic message is used.
        }
        // Redact the full text first: a secret cut in half by the truncation would no longer match.
        String safeMessage = sanitize(redact(message));
        String safeCode = sanitize(redact(code));
        String text = "Campay refused the request (HTTP " + response.statusCode() + ")"
                + (safeCode != null ? " " + safeCode : "") + (safeMessage != null ? ": " + safeMessage : "");
        return new PaymentRejectedException(text, CAMPAY, safeCode);
    }

    static TransactionStatus mapStatus(String rawStatus) {
        switch (rawStatus.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESSFUL":
                return TransactionStatus.SUCCESSFUL;
            case "FAILED":
                return TransactionStatus.FAILED;
            default:
                // PENDING, and any status not documented: never guess a final outcome.
                return TransactionStatus.PENDING;
        }
    }

    private static TransactionType typeOf(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        switch (endpoint.trim().toLowerCase(Locale.ROOT)) {
            case "collect":
                return TransactionType.COLLECTION;
            case "withdraw":
                return TransactionType.PAYOUT;
            default:
                return null;
        }
    }

    private static Long wholeAmount(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            BigDecimal value = node.isNumber() ? node.decimalValue()
                    : node.isString() ? new BigDecimal(node.asString().trim()) : null;
            return value == null ? null : value.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private static Currency currencyOf(String code) {
        if (code == null) {
            return null;
        }
        try {
            return Currency.getInstance(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, String> webhookFields(WebhookRequest request) {
        Map<String, List<String>> all = new LinkedHashMap<>();
        try {
            request.queryParameters().forEach((name, values) ->
                    all.computeIfAbsent(name, n -> new ArrayList<>()).addAll(values));
            byte[] body = request.body();
            if (body.length > 0) {
                bodyFields(request, body).forEach((name, values) ->
                        all.computeIfAbsent(name, n -> new ArrayList<>()).addAll(values));
            }
        } catch (IllegalArgumentException | JacksonException e) {
            throw new WebhookVerificationException("Malformed webhook", CAMPAY);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : all.entrySet()) {
            if (entry.getValue().size() > 1 && WEBHOOK_FIELDS.contains(entry.getKey())) {
                throw new WebhookVerificationException(
                        "Webhook parameter '" + entry.getKey() + "' is given more than once", CAMPAY);
            }
            fields.put(entry.getKey(), entry.getValue().get(0));
        }
        return fields;
    }

    private static Map<String, List<String>> bodyFields(WebhookRequest request, byte[] body) {
        String contentType = request.header("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        String text = new String(body, StandardCharsets.UTF_8);
        if (contentType.contains("json") || (!contentType.contains("form") && text.trim().startsWith("{"))) {
            JsonNode json = Json.parse(body);
            if (json == null || !json.isObject()) {
                throw new IllegalArgumentException("not a JSON object");
            }
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : json.properties()) {
                JsonNode value = entry.getValue();
                if (value.isNull()) {
                    continue;
                }
                if (!value.isValueNode()) {
                    throw new IllegalArgumentException("nested value");
                }
                result.computeIfAbsent(entry.getKey(), n -> new ArrayList<>()).add(value.asString());
            }
            return result;
        }
        return WebhookRequest.decodeForm(text);
    }

    private static void requireSameIfSigned(JsonNode claims, String claim, String value) {
        String signed = Json.text(claims, claim);
        if (signed != null && !signed.equals(value)) {
            throw new WebhookVerificationException(
                    "Webhook parameter '" + claim + "' does not match its signature", CAMPAY);
        }
    }

    private static String format(PhoneNumber number) {
        return ProviderPhoneFormat.CAMPAY.format(number);
    }

    private URI endpoint(String path) {
        return URI.create(baseUrl.toString() + path);
    }

    /** Removes our own credentials and token if a provider message ever repeats them. */
    private String redact(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 4) {
                result = result.replace(secret, "***");
            }
        }
        return tokens.redact(result);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length() && out.length() < MAX_ERROR_MESSAGE_LENGTH; i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        String result = out.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /** Configures a {@link CampayProvider}. */
    public static final class Builder {

        private CampayEnvironment environment;
        private URI baseUrl;
        private String username;
        private String password;
        private String webhookKey;
        private HttpClient httpClient;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /**
         * Sets the environment. Required: there is no default, so that sandbox and production keys are
         * never mixed up.
         *
         * @param environment sandbox or production
         * @return this builder
         */
        public Builder environment(CampayEnvironment environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Replaces the environment's base URL, for example to point to a mock server in tests. Must be
         * HTTPS, except for localhost.
         *
         * @param baseUrl the base URL
         * @return this builder
         */
        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the application username (APP USERNAME in the Campay dashboard).
         *
         * @param username the username
         * @return this builder
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * Sets the application password (APP PASSWORD in the Campay dashboard).
         *
         * @param password the password
         * @return this builder
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * Sets the application webhook key, used to verify webhook signatures. Without it,
         * {@link io.github.backaad.mobilemoney.MobileMoney#handleWebhook} throws
         * {@link ConfigurationException} for Campay webhooks.
         *
         * @param webhookKey the key
         * @return this builder
         */
        public Builder webhookKey(String webhookKey) {
            this.webhookKey = webhookKey;
            return this;
        }

        /**
         * Uses a specific HTTP client. Per-request timeouts still apply. It should not follow redirects.
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
         * Sets the clock, for tests.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Builds the provider.
         *
         * @return the provider
         * @throws ConfigurationException if a required setting is missing or dangerous
         */
        public CampayProvider build() {
            if (environment == null) {
                throw new ConfigurationException("Campay environment is required (SANDBOX or PRODUCTION)", CAMPAY);
            }
            if (username == null || username.isBlank()) {
                throw new ConfigurationException("Campay username is required", CAMPAY);
            }
            if (password == null || password.isBlank()) {
                throw new ConfigurationException("Campay password is required", CAMPAY);
            }
            if (webhookKey != null && webhookKey.isBlank()) {
                throw new ConfigurationException("Campay webhook key must not be blank", CAMPAY);
            }
            Objects.requireNonNull(clock, "clock");
            if (httpClient != null && httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
                throw new ConfigurationException(
                        "The HTTP client must not follow redirects: credentials could be sent elsewhere", CAMPAY);
            }
            if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                    || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new ConfigurationException("Campay timeouts must be positive", CAMPAY);
            }
            try {
                baseUrl = Urls.requireSafeBaseUrl(baseUrl != null ? baseUrl : environment.baseUrl());
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException("Campay base URL: " + e.getMessage(), CAMPAY);
            }
            return new CampayProvider(this);
        }
    }
}
