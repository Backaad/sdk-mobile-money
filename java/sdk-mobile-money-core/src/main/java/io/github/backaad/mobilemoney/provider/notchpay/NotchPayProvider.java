package io.github.backaad.mobilemoney.provider.notchpay;

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
import io.github.backaad.mobilemoney.internal.ConstantTime;
import io.github.backaad.mobilemoney.internal.HttpCaller;
import io.github.backaad.mobilemoney.internal.Json;
import io.github.backaad.mobilemoney.internal.Replies;
import io.github.backaad.mobilemoney.internal.Urls;
import io.github.backaad.mobilemoney.phone.MobileOperator;
import io.github.backaad.mobilemoney.phone.PhoneNumber;
import io.github.backaad.mobilemoney.phone.PrefixTable;
import io.github.backaad.mobilemoney.phone.ProviderPhoneFormat;
import io.github.backaad.mobilemoney.provider.PaymentProvider;
import io.github.backaad.mobilemoney.provider.StatusReport;
import io.github.backaad.mobilemoney.provider.WebhookNotification;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * NotchPay provider: collections, payouts, status reads and webhooks.
 *
 * <p>A collection takes two calls: the payment is initialized ({@code POST /payments}), which gives its
 * NotchPay reference, then charged on the customer's operator ({@code POST /payments/{reference}}). The
 * channel ({@code cm.mtn} or {@code cm.orange}) comes from the number's prefix; a number whose operator
 * is unknown is refused before anything is sent.
 *
 * <p>Every call carries the public key in {@code Authorization}; payouts and transfer reads also carry
 * the private key in {@code X-Grant}. Webhooks are signed with HMAC-SHA256 of the raw body
 * ({@code x-notch-signature}), compared in constant time, and the status is then read again.
 *
 * <p>What was checked and what is still to be confirmed is listed in {@code docs/providers/notchpay.md}.
 */
public final class NotchPayProvider implements PaymentProvider {

    /** Header carrying the webhook signature. */
    public static final String SIGNATURE_HEADER = "x-notch-signature";

    private static final ProviderId NOTCHPAY = ProviderId.NOTCHPAY;
    private static final URI DEFAULT_BASE_URL = URI.create("https://api.notchpay.co");
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern HEX_SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final URI baseUrl;
    private final String publicKey;
    private final String privateKey;
    private final byte[] webhookSecret;
    private final PrefixTable prefixTable;
    private final Duration requestTimeout;
    private final HttpCaller http;
    private final ProviderCapabilities capabilities;
    private final List<String> secrets;

    private NotchPayProvider(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.publicKey = builder.publicKey;
        this.privateKey = builder.privateKey;
        this.webhookSecret = builder.webhookSecret == null ? null
                : builder.webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.prefixTable = builder.prefixTable;
        this.requestTimeout = builder.requestTimeout;
        HttpClient client = builder.httpClient != null ? builder.httpClient : HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.http = new HttpCaller(client, NOTCHPAY);
        this.capabilities = new ProviderCapabilities(true, privateKey != null, true, WebhookSignature.HMAC_SHA256,
                Set.of(Currencies.XAF));
        List<String> all = new ArrayList<>();
        all.add(publicKey);
        all.add(privateKey);
        all.add(builder.webhookSecret);
        this.secrets = all;
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
        return NOTCHPAY;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public Transaction collect(PaymentRequest request) {
        String channel = channel(request.phoneNumber());
        ObjectNode init = Json.object();
        init.put("amount", request.amount());
        init.put("currency", request.currency().getCurrencyCode());
        init.put("reference", request.merchantReference());
        init.put("description", request.description());
        init.put("phone", format(request.phoneNumber()));
        HttpResponse<byte[]> initialized = post("/payments", init, false);
        requireAccepted(initialized, null);
        JsonNode payment = envelope(Replies.object(initialized, NOTCHPAY, true), "transaction");
        String reference = Json.text(payment, "reference");
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new ProviderUnavailableException(
                    "NotchPay initialized the payment but returned no usable reference", NOTCHPAY, true, null);
        }

        // Second call: ask the operator to prompt the customer. Its reference is known from now on.
        ObjectNode charge = Json.object();
        charge.put("channel", channel);
        ObjectNode data = charge.putObject("data");
        data.put("phone", format(request.phoneNumber()));
        data.put("country", "CM");
        HttpResponse<byte[]> charged;
        try {
            charged = post("/payments/" + reference, charge, false);
        } catch (ProviderUnavailableException e) {
            throw withKnownReference(e, reference);
        }
        requireAccepted(charged, reference);
        TransactionStatus status = TransactionStatus.PENDING;
        String rawStatus = null;
        JsonNode chargedJson = Replies.objectOrNull(charged.body());
        if (chargedJson != null) {
            rawStatus = Replies.blankToNull(Json.text(envelope(chargedJson, "transaction"), "status"));
            status = rawStatus == null ? TransactionStatus.PENDING : mapStatus(rawStatus);
        }
        return new Transaction(NOTCHPAY, TransactionType.COLLECTION, reference, request.merchantReference(),
                request.amount(), request.currency(), status, rawStatus);
    }

    @Override
    public Transaction payout(PayoutRequest request) {
        if (privateKey == null) {
            throw new UnsupportedCapabilityException("NotchPay payouts need the private key", NOTCHPAY);
        }
        ObjectNode body = Json.object();
        body.put("amount", request.amount());
        body.put("currency", request.currency().getCurrencyCode());
        body.put("description", request.description());
        body.put("reference", request.merchantReference());
        body.put("channel", channel(request.phoneNumber()));
        ObjectNode beneficiary = body.putObject("beneficiary");
        beneficiary.put("name", request.beneficiaryName());
        beneficiary.put("phone", format(request.phoneNumber()));
        HttpResponse<byte[]> response = post("/transfers", body, true);
        requireAccepted(response, null);
        JsonNode transfer = envelope(Replies.object(response, NOTCHPAY, true), "transfer");
        String reference = Json.text(transfer, "reference");
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new ProviderUnavailableException(
                    "NotchPay accepted the transfer but returned no usable reference", NOTCHPAY, true, null);
        }
        String rawStatus = Replies.blankToNull(Json.text(transfer, "status"));
        return new Transaction(NOTCHPAY, TransactionType.PAYOUT, reference, request.merchantReference(),
                request.amount(), request.currency(), rawStatus == null ? TransactionStatus.PENDING
                        : mapStatus(rawStatus), rawStatus);
    }

    @Override
    public StatusReport fetchStatus(TransactionType type, String providerReference) {
        if (providerReference == null || !REFERENCE.matcher(providerReference).matches()) {
            throw new TransactionNotFoundException("Not a NotchPay reference", NOTCHPAY);
        }
        if (type != TransactionType.PAYOUT) {
            try {
                return read(TransactionType.COLLECTION, providerReference);
            } catch (TransactionNotFoundException e) {
                if (type == TransactionType.COLLECTION || privateKey == null) {
                    throw e;
                }
            }
        }
        if (privateKey == null) {
            throw new TransactionNotFoundException("NotchPay transfers cannot be read without the private key",
                    NOTCHPAY);
        }
        return read(TransactionType.PAYOUT, providerReference);
    }

    @Override
    public WebhookNotification parseWebhook(WebhookRequest request) {
        if (webhookSecret == null) {
            throw new ConfigurationException(
                    "The NotchPay webhook secret is not configured: webhooks cannot be verified", NOTCHPAY);
        }
        if (!request.method().equalsIgnoreCase("POST")) {
            throw new WebhookVerificationException("Unexpected webhook method", NOTCHPAY);
        }
        List<String> signatures = request.headers(SIGNATURE_HEADER);
        if (signatures.size() != 1 || !HEX_SHA256.matcher(signatures.get(0).trim()).matches()) {
            throw new WebhookVerificationException("Unsigned webhook", NOTCHPAY);
        }
        byte[] body = request.body();
        String expected = HexFormat.of().formatHex(hmacSha256(webhookSecret, body));
        if (!ConstantTime.equals(expected, signatures.get(0).trim().toLowerCase(Locale.ROOT))) {
            throw new WebhookVerificationException("Invalid webhook signature", NOTCHPAY);
        }
        JsonNode json = Replies.objectOrNull(body);
        if (json == null) {
            throw new WebhookVerificationException("Malformed webhook", NOTCHPAY);
        }
        String event = Replies.blankToNull(Json.text(json, "type"));
        JsonNode data = json.get("data");
        String reference = Json.text(data, "reference");
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new WebhookVerificationException("Webhook without a valid reference", NOTCHPAY);
        }
        TransactionType type = null;
        if (event != null && event.startsWith("payment.")) {
            type = TransactionType.COLLECTION;
        } else if (event != null && event.startsWith("transfer.")) {
            type = TransactionType.PAYOUT;
        }
        String claimed = Replies.blankToNull(Json.text(data, "status"));
        return new WebhookNotification(reference, Replies.blankToNull(Json.text(data, "trxref")), type,
                claimed != null ? claimed : event);
    }

    @Override
    public String toString() {
        return "NotchPayProvider[baseUrl=" + baseUrl + ", payouts=" + (privateKey != null)
                + ", webhookSecretConfigured=" + (webhookSecret != null) + "]";
    }

    /** Maps a NotchPay status. Undocumented values never become a final outcome. */
    static TransactionStatus mapStatus(String rawStatus) {
        switch (rawStatus.trim().toLowerCase(Locale.ROOT)) {
            case "complete":
            case "completed":
            case "successful":
            case "success":
                return TransactionStatus.SUCCESSFUL;
            case "failed":
            case "canceled":
            case "cancelled":
            case "rejected":
            case "expired":
                return TransactionStatus.FAILED;
            default:
                return TransactionStatus.PENDING;
        }
    }

    private StatusReport read(TransactionType type, String reference) {
        boolean transfer = type == TransactionType.PAYOUT;
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        endpoint((transfer ? "/transfers/" : "/payments/") + reference))
                .timeout(requestTimeout)
                .header("Authorization", publicKey)
                .header("Accept", "application/json")
                .GET();
        if (transfer) {
            builder.header("X-Grant", privateKey);
        }
        HttpResponse<byte[]> response = http.send(builder.build());
        int status = response.statusCode();
        if (status == 404) {
            throw new TransactionNotFoundException("NotchPay does not know this transaction", NOTCHPAY);
        }
        if (status == 401 || status == 403) {
            throw new ProviderAuthenticationException(
                    "NotchPay refused the keys (HTTP " + status + ")", NOTCHPAY);
        }
        if (status != 200) {
            throw new ProviderUnavailableException(
                    "NotchPay status read answered HTTP " + status, NOTCHPAY, false, null);
        }
        JsonNode json = envelope(Replies.object(response, NOTCHPAY, false), transfer ? "transfer" : "transaction");
        String rawStatus = Replies.blankToNull(Json.text(json, "status"));
        if (rawStatus == null) {
            throw new ProviderUnavailableException("NotchPay status response has no status", NOTCHPAY, false, null);
        }
        String returned = Json.text(json, "reference");
        return new StatusReport(returned != null ? returned : reference,
                Replies.blankToNull(Json.text(json, "trxref")), type, mapStatus(rawStatus), rawStatus,
                Replies.wholeAmount(json.get("amount")), Replies.currency(Json.text(json, "currency")));
    }

    private HttpResponse<byte[]> post(String path, ObjectNode body, boolean grant) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(path))
                .timeout(requestTimeout)
                .header("Authorization", publicKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.write(body)));
        if (grant) {
            builder.header("X-Grant", privateKey);
        }
        return http.send(builder.build());
    }

    /**
     * Throws unless the response accepts the request.
     *
     * @param reference the NotchPay reference already known, attached to an unknown outcome
     */
    private void requireAccepted(HttpResponse<byte[]> response, String reference) {
        int status = response.statusCode();
        if (status == 200 || status == 201 || status == 202) {
            return;
        }
        if (status == 401 || status == 403) {
            throw new ProviderAuthenticationException("NotchPay refused the keys (HTTP " + status + ")", NOTCHPAY);
        }
        if (status == 408 || status == 429) {
            throw new ProviderUnavailableException(
                    "NotchPay did not accept the request now (HTTP " + status + ")", NOTCHPAY, false, null);
        }
        if (status == 409) {
            throw new ProviderUnavailableException("NotchPay answered HTTP 409", NOTCHPAY, true, reference, null);
        }
        if (status >= 400 && status < 500) {
            throw rejection(response);
        }
        throw new ProviderUnavailableException("NotchPay answered HTTP " + status, NOTCHPAY, true, reference, null);
    }

    private PaymentRejectedException rejection(HttpResponse<byte[]> response) {
        JsonNode json = Replies.objectOrNull(response.body());
        String message = Replies.safeText(Json.text(json, "message"), secrets);
        String text = "NotchPay refused the request (HTTP " + response.statusCode() + ")"
                + (message != null ? ": " + message : "");
        return new PaymentRejectedException(text, NOTCHPAY, Integer.toString(response.statusCode()));
    }

    private String channel(PhoneNumber number) {
        MobileOperator operator = prefixTable.operatorOf(number).orElseThrow(() ->
                new UnsupportedCapabilityException("NotchPay needs the operator of the number to choose the"
                        + " payment channel, and it is not known for " + number.masked(), NOTCHPAY));
        return switch (operator) {
            case MTN -> "cm.mtn";
            case ORANGE -> "cm.orange";
        };
    }

    /** NotchPay wraps the object in a named field ({@code transaction}, {@code transfer}) or {@code data}. */
    private static JsonNode envelope(JsonNode json, String name) {
        JsonNode inner = json.get(name);
        if (inner != null && inner.isObject()) {
            return inner;
        }
        inner = json.get("data");
        return inner != null && inner.isObject() ? inner : json;
    }

    private static ProviderUnavailableException withKnownReference(ProviderUnavailableException e, String reference) {
        if (!e.requestMayHaveBeenProcessed()) {
            // The charge did not reach NotchPay: the payment was only initialized, nothing can be debited.
            return e;
        }
        return new ProviderUnavailableException(e.getMessage(), NOTCHPAY, true, reference, null);
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available");
        }
    }

    private static String format(PhoneNumber number) {
        return ProviderPhoneFormat.NOTCHPAY.format(number);
    }

    private URI endpoint(String path) {
        return URI.create(baseUrl.toString() + path);
    }

    /** Configures a {@link NotchPayProvider}. */
    public static final class Builder {

        private URI baseUrl;
        private String publicKey;
        private String privateKey;
        private String webhookSecret;
        private PrefixTable prefixTable = PrefixTable.cameroonDefault();
        private HttpClient httpClient;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);

        private Builder() {
        }

        /**
         * Replaces the base URL ({@code https://api.notchpay.co}), for example to point to a mock server in
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
         * Sets the public key. Required. Test keys (sandbox) and live keys are told apart by NotchPay.
         *
         * @param publicKey the public key
         * @return this builder
         */
        public Builder publicKey(String publicKey) {
            this.publicKey = publicKey;
            return this;
        }

        /**
         * Sets the private key, sent in {@code X-Grant}. Needed for payouts.
         *
         * @param privateKey the private key
         * @return this builder
         */
        public Builder privateKey(String privateKey) {
            this.privateKey = privateKey;
            return this;
        }

        /**
         * Sets the secret that signs webhooks. Without it, webhooks cannot be verified.
         *
         * @param webhookSecret the secret
         * @return this builder
         */
        public Builder webhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
            return this;
        }

        /**
         * Sets the prefix table used to choose the payment channel. Defaults to
         * {@link PrefixTable#cameroonDefault()}.
         *
         * @param prefixTable the table
         * @return this builder
         */
        public Builder prefixTable(PrefixTable prefixTable) {
            this.prefixTable = prefixTable;
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
        public NotchPayProvider build() {
            if (publicKey == null || publicKey.isBlank()) {
                throw new ConfigurationException("NotchPay public key is required", NOTCHPAY);
            }
            if (privateKey != null && privateKey.isBlank()) {
                throw new ConfigurationException("NotchPay private key must not be blank", NOTCHPAY);
            }
            if (webhookSecret != null && webhookSecret.isBlank()) {
                throw new ConfigurationException("NotchPay webhook secret must not be blank", NOTCHPAY);
            }
            if (prefixTable == null) {
                throw new ConfigurationException("NotchPay prefix table is required", NOTCHPAY);
            }
            if (httpClient != null && httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
                throw new ConfigurationException(
                        "The HTTP client must not follow redirects: keys could be sent elsewhere", NOTCHPAY);
            }
            if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                    || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new ConfigurationException("NotchPay timeouts must be positive", NOTCHPAY);
            }
            try {
                baseUrl = Urls.requireSafeBaseUrl(baseUrl != null ? baseUrl : DEFAULT_BASE_URL);
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException("NotchPay base URL: " + e.getMessage(), NOTCHPAY);
            }
            return new NotchPayProvider(this);
        }
    }
}
