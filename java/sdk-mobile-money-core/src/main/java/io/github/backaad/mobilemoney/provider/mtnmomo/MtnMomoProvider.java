package io.github.backaad.mobilemoney.provider.mtnmomo;

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
import io.github.backaad.mobilemoney.UnsupportedCapabilityException;
import io.github.backaad.mobilemoney.WebhookRequest;
import io.github.backaad.mobilemoney.WebhookSignature;
import io.github.backaad.mobilemoney.WebhookVerificationException;
import io.github.backaad.mobilemoney.internal.AccessTokenCache;
import io.github.backaad.mobilemoney.internal.HttpCaller;
import io.github.backaad.mobilemoney.internal.Json;
import io.github.backaad.mobilemoney.internal.Replies;
import io.github.backaad.mobilemoney.internal.Urls;
import io.github.backaad.mobilemoney.phone.ProviderPhoneFormat;
import io.github.backaad.mobilemoney.provider.PaymentProvider;
import io.github.backaad.mobilemoney.provider.StatusReport;
import io.github.backaad.mobilemoney.provider.WebhookNotification;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MTN MoMo provider (MoMo Open API): collections ({@code requesttopay}), payouts (disbursement
 * {@code transfer}), status reads and callbacks.
 *
 * <p>Each product (collection, disbursement) has its own subscription key, API user and API key, and its
 * own access token, kept until it expires minus one minute and renewed once after a 401. The transaction
 * reference ({@code X-Reference-Id}, a random UUID) is chosen before the request is sent: even when the
 * answer is lost, the transaction can be looked up.
 *
 * <p>MTN callbacks are not signed. The callback URL given to MTN carries the reference and the type
 * ({@value #REFERENCE_PARAMETER}, {@value #TYPE_PARAMETER}), and the status is always read again from
 * MTN before anything is confirmed.
 *
 * <p>What was checked and what is still to be confirmed is listed in {@code docs/providers/mtn-momo.md}.
 */
public final class MtnMomoProvider implements PaymentProvider {

    /** Query parameter added to the callback URL: the MTN reference of the transaction. */
    public static final String REFERENCE_PARAMETER = "mm_reference";
    /** Query parameter added to the callback URL: {@code collection} or {@code payout}. */
    public static final String TYPE_PARAMETER = "mm_type";

    private static final ProviderId MTN = ProviderId.MTN_MOMO;
    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final MtnMomoEnvironment environment;
    private final URI baseUrl;
    private final String targetEnvironment;
    private final Duration requestTimeout;
    private final HttpCaller http;
    private final Product collection;
    private final Product disbursement;
    private final ProviderCapabilities capabilities;
    private final List<String> secrets;

    private MtnMomoProvider(Builder builder) {
        this.environment = builder.environment;
        this.baseUrl = builder.baseUrl;
        this.targetEnvironment = builder.targetEnvironment;
        this.requestTimeout = builder.requestTimeout;
        HttpClient client = builder.httpClient != null ? builder.httpClient : HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.http = new HttpCaller(client, MTN);
        this.collection = builder.collection == null ? null
                : new Product("collection", builder.collection, builder.clock);
        this.disbursement = builder.disbursement == null ? null
                : new Product("disbursement", builder.disbursement, builder.clock);
        this.capabilities = new ProviderCapabilities(collection != null, disbursement != null, true,
                WebhookSignature.NONE, Set.of(builder.currency));
        List<String> all = new ArrayList<>();
        for (Credentials c : new Credentials[] {builder.collection, builder.disbursement}) {
            if (c != null) {
                all.addAll(List.of(c.subscriptionKey(), c.apiUser(), c.apiKey()));
            }
        }
        this.secrets = List.copyOf(all);
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
        return MTN;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public Transaction collect(PaymentRequest request) {
        Product product = require(collection, "collect payments: the collection product is not configured");
        ObjectNode body = Json.object();
        body.put("amount", Long.toString(request.amount()));
        body.put("currency", request.currency().getCurrencyCode());
        body.put("externalId", request.merchantReference());
        ObjectNode payer = body.putObject("payer");
        payer.put("partyIdType", "MSISDN");
        payer.put("partyId", ProviderPhoneFormat.MTN_MOMO.format(request.phoneNumber()));
        body.put("payerMessage", request.description());
        body.put("payeeNote", request.description());
        String reference = UUID.randomUUID().toString();
        send(product, "/collection/v1_0/requesttopay", reference, TransactionType.COLLECTION,
                request.callbackUrl(), body);
        return new Transaction(MTN, TransactionType.COLLECTION, reference, request.merchantReference(),
                request.amount(), request.currency(), TransactionStatus.PENDING, null);
    }

    @Override
    public Transaction payout(PayoutRequest request) {
        Product product = require(disbursement, "pay out: the disbursement product is not configured");
        ObjectNode body = Json.object();
        body.put("amount", Long.toString(request.amount()));
        body.put("currency", request.currency().getCurrencyCode());
        body.put("externalId", request.merchantReference());
        ObjectNode payee = body.putObject("payee");
        payee.put("partyIdType", "MSISDN");
        payee.put("partyId", ProviderPhoneFormat.MTN_MOMO.format(request.phoneNumber()));
        body.put("payerMessage", request.description());
        body.put("payeeNote", request.description());
        String reference = UUID.randomUUID().toString();
        send(product, "/disbursement/v1_0/transfer", reference, TransactionType.PAYOUT, request.callbackUrl(), body);
        return new Transaction(MTN, TransactionType.PAYOUT, reference, request.merchantReference(),
                request.amount(), request.currency(), TransactionStatus.PENDING, null);
    }

    @Override
    public StatusReport fetchStatus(TransactionType type, String providerReference) {
        if (providerReference == null || !UUID_FORMAT.matcher(providerReference).matches()) {
            throw new TransactionNotFoundException("Not an MTN MoMo reference", MTN);
        }
        if (type != TransactionType.PAYOUT && collection != null) {
            try {
                return read(collection, TransactionType.COLLECTION, providerReference);
            } catch (TransactionNotFoundException e) {
                if (type == TransactionType.COLLECTION || disbursement == null) {
                    throw e;
                }
            }
        }
        if (type != TransactionType.COLLECTION && disbursement != null) {
            // Type unknown, or a payout: the reference is a UUID, unique across products.
            return read(disbursement, TransactionType.PAYOUT, providerReference);
        }
        throw new TransactionNotFoundException("MTN MoMo product not configured for this transaction type", MTN);
    }

    @Override
    public WebhookNotification parseWebhook(WebhookRequest request) {
        String method = request.method().toUpperCase(Locale.ROOT);
        // MTN calls back with POST or PUT, depending on the product (to be confirmed).
        if (!method.equals("POST") && !method.equals("PUT")) {
            throw new WebhookVerificationException("Unexpected callback method", MTN);
        }
        Map<String, List<String>> query;
        try {
            query = request.queryParameters();
        } catch (IllegalArgumentException e) {
            throw new WebhookVerificationException("Malformed callback", MTN);
        }
        String reference = single(query, REFERENCE_PARAMETER);
        if (reference == null || !UUID_FORMAT.matcher(reference).matches()) {
            throw new WebhookVerificationException("Callback without a valid MTN reference", MTN);
        }
        String typeName = single(query, TYPE_PARAMETER);
        TransactionType type = null;
        if (typeName != null) {
            switch (typeName) {
                case "collection" -> type = TransactionType.COLLECTION;
                case "payout" -> type = TransactionType.PAYOUT;
                default -> throw new WebhookVerificationException("Callback with an unknown type", MTN);
            }
        }
        // The body is not signed: it only gives the claimed status and merchant reference, which are
        // compared with what MTN's API returns.
        JsonNode body = Replies.objectOrNull(request.body());
        if (request.body().length > 0 && body == null) {
            throw new WebhookVerificationException("Malformed callback", MTN);
        }
        return new WebhookNotification(reference, Replies.blankToNull(Json.text(body, "externalId")), type,
                Replies.blankToNull(Json.text(body, "status")));
    }

    @Override
    public String toString() {
        return "MtnMomoProvider[environment=" + environment + ", baseUrl=" + baseUrl + ", targetEnvironment="
                + targetEnvironment + ", collection=" + (collection != null) + ", disbursement="
                + (disbursement != null) + "]";
    }

    /** Maps an MTN status. Undocumented values never become a final outcome. */
    static TransactionStatus mapStatus(String rawStatus) {
        switch (rawStatus.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESSFUL":
                return TransactionStatus.SUCCESSFUL;
            case "FAILED":
                return TransactionStatus.FAILED;
            default:
                return TransactionStatus.PENDING;
        }
    }

    private void send(Product product, String path, String reference, TransactionType type, URI callbackUrl,
            ObjectNode body) {
        byte[] bytes = Json.write(body);
        URI callback = callbackUrl == null ? null : withReference(callbackUrl, reference, type);
        HttpResponse<byte[]> response;
        try {
            response = authorized(product, token -> {
                HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(path))
                        .timeout(requestTimeout)
                        .header("Authorization", "Bearer " + token)
                        .header("Ocp-Apim-Subscription-Key", product.credentials.subscriptionKey())
                        .header("X-Target-Environment", targetEnvironment)
                        // The same reference on the replay after a 401: MTN refuses a second use of it.
                        .header("X-Reference-Id", reference)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json");
                if (callback != null) {
                    builder.header("X-Callback-Url", callback.toString());
                }
                return builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            });
        } catch (ProviderUnavailableException e) {
            throw withKnownReference(e, reference);
        }
        int status = response.statusCode();
        if (status == 202 || status == 200 || status == 201) {
            return;
        }
        if (status == 403) {
            throw new ProviderAuthenticationException("MTN MoMo refused the operation (HTTP 403): check the"
                    + " subscription of the " + product.name + " product", MTN);
        }
        if (status == 408 || status == 429) {
            throw new ProviderUnavailableException(
                    "MTN MoMo did not accept the request now (HTTP " + status + ")", MTN, false, null);
        }
        if (status == 409) {
            // The reference already exists: the request may have been processed.
            throw new ProviderUnavailableException("MTN MoMo answered HTTP 409", MTN, true, reference, null);
        }
        if (status >= 400 && status < 500) {
            throw rejection(response);
        }
        throw new ProviderUnavailableException("MTN MoMo answered HTTP " + status, MTN, true, reference, null);
    }

    private StatusReport read(Product product, TransactionType type, String reference) {
        String path = (type == TransactionType.COLLECTION ? "/collection/v1_0/requesttopay/"
                : "/disbursement/v1_0/transfer/") + reference;
        HttpResponse<byte[]> response = authorized(product, token -> HttpRequest.newBuilder(endpoint(path))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("Ocp-Apim-Subscription-Key", product.credentials.subscriptionKey())
                .header("X-Target-Environment", targetEnvironment)
                .header("Accept", "application/json")
                .GET()
                .build());
        int status = response.statusCode();
        if (status == 404) {
            throw new TransactionNotFoundException("MTN MoMo does not know this transaction", MTN);
        }
        if (status != 200) {
            throw new ProviderUnavailableException("MTN MoMo status read answered HTTP " + status, MTN, false, null);
        }
        JsonNode json = Replies.object(response, MTN, false);
        String rawStatus = Replies.blankToNull(Json.text(json, "status"));
        if (rawStatus == null) {
            throw new ProviderUnavailableException("MTN MoMo status response has no status", MTN, false, null);
        }
        // The answer of this product's endpoint for this reference: the type is the one read.
        return new StatusReport(reference, Replies.blankToNull(Json.text(json, "externalId")), type,
                mapStatus(rawStatus), rawStatus, Replies.wholeAmount(json.get("amount")),
                Replies.currency(Json.text(json, "currency")));
    }

    /**
     * Sends a request with a valid token of the product. On a 401, the token is renewed and the same request
     * sent once more: a request refused with 401 was not processed.
     */
    private HttpResponse<byte[]> authorized(Product product, Function<String, HttpRequest> requestFor) {
        String token = product.tokens.get();
        HttpResponse<byte[]> response = http.send(requestFor.apply(token));
        if (response.statusCode() != 401) {
            return response;
        }
        product.tokens.invalidate(token);
        String renewed = product.tokens.get();
        response = http.send(requestFor.apply(renewed));
        if (response.statusCode() == 401) {
            product.tokens.invalidate(renewed);
            throw new ProviderAuthenticationException(
                    "MTN MoMo refused the " + product.name + " access token even after renewing it (HTTP 401)", MTN);
        }
        return response;
    }

    private AccessTokenCache.Issued fetchToken(Product product) {
        String basic = Base64.getEncoder().encodeToString(
                (product.credentials.apiUser() + ":" + product.credentials.apiKey()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(endpoint("/" + product.name + "/token/"))
                .timeout(requestTimeout)
                .header("Authorization", "Basic " + basic)
                .header("Ocp-Apim-Subscription-Key", product.credentials.subscriptionKey())
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request);
        } catch (ProviderUnavailableException e) {
            // Only the token request failed: the operation that needed it was not sent.
            throw new ProviderUnavailableException("Could not obtain an MTN MoMo " + product.name
                    + " access token: " + e.getMessage(), MTN, false, null);
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new ProviderAuthenticationException("MTN MoMo refused the " + product.name + " credentials (HTTP "
                    + status + "): check the subscription key, API user and API key", MTN);
        }
        if (status != 200) {
            throw new ProviderUnavailableException(
                    "MTN MoMo token endpoint answered HTTP " + status, MTN, false, null);
        }
        JsonNode json = Replies.object(response, MTN, false);
        String value = Json.text(json, "access_token");
        if (value == null || value.isBlank()) {
            throw new ProviderUnavailableException("MTN MoMo token response has no token", MTN, false, null);
        }
        return new AccessTokenCache.Issued(value, Json.wholeNumber(json.get("expires_in")));
    }

    private PaymentRejectedException rejection(HttpResponse<byte[]> response) {
        JsonNode json = Replies.objectOrNull(response.body());
        List<String> hidden = new ArrayList<>(secrets);
        String code = Replies.safeText(redactTokens(Json.text(json, "code")), hidden);
        String message = Replies.safeText(redactTokens(Json.text(json, "message")), hidden);
        String text = "MTN MoMo refused the request (HTTP " + response.statusCode() + ")"
                + (code != null ? " " + code : "") + (message != null ? ": " + message : "");
        return new PaymentRejectedException(text, MTN, code);
    }

    private String redactTokens(String text) {
        String result = text;
        for (Product product : new Product[] {collection, disbursement}) {
            if (product != null) {
                result = product.tokens.redact(result);
            }
        }
        return result;
    }

    private static ProviderUnavailableException withKnownReference(ProviderUnavailableException e, String reference) {
        if (!e.requestMayHaveBeenProcessed()) {
            return e;
        }
        return new ProviderUnavailableException(e.getMessage(), MTN, true, reference, null);
    }

    private static URI withReference(URI callbackUrl, String reference, TransactionType type) {
        String text = callbackUrl.toString();
        int hash = text.indexOf('#');
        String fragment = hash >= 0 ? text.substring(hash) : "";
        String base = hash >= 0 ? text.substring(0, hash) : text;
        String added = REFERENCE_PARAMETER + "=" + URLEncoder.encode(reference, StandardCharsets.UTF_8) + "&"
                + TYPE_PARAMETER + "=" + (type == TransactionType.COLLECTION ? "collection" : "payout");
        return URI.create(base + (callbackUrl.getRawQuery() == null ? "?" : "&") + added + fragment);
    }

    private static String single(Map<String, List<String>> query, String name) {
        List<String> values = query.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new WebhookVerificationException("Callback parameter '" + name + "' is given more than once", MTN);
        }
        return values.get(0);
    }

    private static Product require(Product product, String what) {
        if (product == null) {
            throw new UnsupportedCapabilityException("MTN MoMo cannot " + what, MTN);
        }
        return product;
    }

    private URI endpoint(String path) {
        return URI.create(baseUrl.toString() + path);
    }

    private final class Product {

        private final String name;
        private final Credentials credentials;
        private final AccessTokenCache tokens;

        Product(String name, Credentials credentials, Clock clock) {
            this.name = name;
            this.credentials = credentials;
            this.tokens = new AccessTokenCache(MTN, "MTN MoMo " + name + " access token",
                    () -> fetchToken(this), clock, requestTimeout);
        }
    }

    private record Credentials(String subscriptionKey, String apiUser, String apiKey) {

        @Override
        public String toString() {
            return "Credentials[***]";
        }
    }

    /** Configures an {@link MtnMomoProvider}. */
    public static final class Builder {

        private MtnMomoEnvironment environment;
        private URI baseUrl;
        private String targetEnvironment;
        private Credentials collection;
        private Credentials disbursement;
        private Currency currency = Currencies.XAF;
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
        public Builder environment(MtnMomoEnvironment environment) {
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
         * Sets the target environment ({@code X-Target-Environment}). Defaults to {@code sandbox} in the
         * sandbox; required in production, where MTN gives the value for each country.
         *
         * @param targetEnvironment the target environment
         * @return this builder
         */
        public Builder targetEnvironment(String targetEnvironment) {
            this.targetEnvironment = targetEnvironment;
            return this;
        }

        /**
         * Enables collections with the credentials of the collection product.
         *
         * @param subscriptionKey the collection subscription key (primary key)
         * @param apiUser the API user (a UUID)
         * @param apiKey the API key of that user
         * @return this builder
         */
        public Builder collection(String subscriptionKey, String apiUser, String apiKey) {
            this.collection = new Credentials(subscriptionKey, apiUser, apiKey);
            return this;
        }

        /**
         * Enables payouts with the credentials of the disbursement product.
         *
         * @param subscriptionKey the disbursement subscription key (primary key)
         * @param apiUser the API user (a UUID)
         * @param apiKey the API key of that user
         * @return this builder
         */
        public Builder disbursement(String subscriptionKey, String apiUser, String apiKey) {
            this.disbursement = new Credentials(subscriptionKey, apiUser, apiKey);
            return this;
        }

        /**
         * Sets the currency of the account. Defaults to {@link Currencies#XAF}. The public sandbox only
         * accepts {@code EUR} (to be confirmed).
         *
         * @param currency the currency
         * @return this builder
         */
        public Builder currency(Currency currency) {
            this.currency = currency;
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
        public MtnMomoProvider build() {
            if (environment == null) {
                throw new ConfigurationException("MTN MoMo environment is required (SANDBOX or PRODUCTION)", MTN);
            }
            if (collection == null && disbursement == null) {
                throw new ConfigurationException(
                        "MTN MoMo needs the credentials of the collection or disbursement product", MTN);
            }
            check(collection, "collection");
            check(disbursement, "disbursement");
            if (targetEnvironment == null) {
                targetEnvironment = environment.defaultTargetEnvironment();
            }
            if (targetEnvironment == null || !targetEnvironment.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new ConfigurationException("MTN MoMo target environment is required in production (the"
                        + " value given by MTN for the country)", MTN);
            }
            if (environment == MtnMomoEnvironment.PRODUCTION && targetEnvironment.equals("sandbox")) {
                throw new ConfigurationException(
                        "MTN MoMo target environment 'sandbox' cannot be used in production", MTN);
            }
            if (currency == null) {
                throw new ConfigurationException("MTN MoMo currency is required", MTN);
            }
            Objects.requireNonNull(clock, "clock");
            if (httpClient != null && httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
                throw new ConfigurationException(
                        "The HTTP client must not follow redirects: credentials could be sent elsewhere", MTN);
            }
            if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                    || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new ConfigurationException("MTN MoMo timeouts must be positive", MTN);
            }
            try {
                baseUrl = Urls.requireSafeBaseUrl(baseUrl != null ? baseUrl : environment.baseUrl());
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException("MTN MoMo base URL: " + e.getMessage(), MTN);
            }
            return new MtnMomoProvider(this);
        }

        private static void check(Credentials credentials, String product) {
            if (credentials == null) {
                return;
            }
            if (blank(credentials.subscriptionKey()) || blank(credentials.apiUser()) || blank(credentials.apiKey())) {
                throw new ConfigurationException("MTN MoMo " + product
                        + " needs a subscription key, an API user and an API key", MTN);
            }
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
