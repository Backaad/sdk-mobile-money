package io.github.backaad.mobilemoney.provider.notchpay;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.PaymentRejectedException;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.PayoutRequest;
import io.github.backaad.mobilemoney.ProviderAuthenticationException;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionNotFoundException;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.UnsupportedCapabilityException;
import io.github.backaad.mobilemoney.Verification;
import io.github.backaad.mobilemoney.WebhookEvent;
import io.github.backaad.mobilemoney.WebhookRequest;
import io.github.backaad.mobilemoney.WebhookVerificationException;
import io.github.backaad.mobilemoney.phone.MobileOperator;
import io.github.backaad.mobilemoney.phone.PrefixTable;
import io.github.backaad.mobilemoney.provider.StatusReport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * NotchPay adapter against WireMock: two-call collection, success and refusal, 5xx and empty responses,
 * unknown status, HMAC-signed webhooks confirmed by a status read. NotchPay uses fixed keys, not tokens:
 * a 401 means the keys are refused. Shapes follow NotchPay's API reference and SDKs; the points still to
 * be confirmed are listed in docs/providers/notchpay.md.
 */
class NotchPayProviderTest {

    static final String PUBLIC_KEY = "CANARY-NOTCH-PUBLIC-1a2b";
    static final String PRIVATE_KEY = "CANARY-NOTCH-PRIVATE-3c4d";
    static final String WEBHOOK_SECRET = "CANARY-NOTCH-HASH-5e6f";
    static final String REFERENCE = "trx.test_kQ8vKcN4W0T2rPd7";

    @RegisterExtension
    static WireMockExtension notch = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private NotchPayProvider provider;

    @BeforeEach
    void setUp() {
        provider = builder().build();
    }

    NotchPayProvider.Builder builder() {
        return NotchPayProvider.builder()
                .baseUrl(URI.create(notch.baseUrl()))
                .publicKey(PUBLIC_KEY)
                .privateKey(PRIVATE_KEY)
                .webhookSecret(WEBHOOK_SECRET)
                .requestTimeout(Duration.ofMillis(800));
    }

    static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    static String initialized() {
        return "{\"status\":\"Accepted\",\"message\":\"Payment initialized\",\"code\":201,"
                + "\"transaction\":{\"amount\":500,\"currency\":\"XAF\",\"status\":\"pending\","
                + "\"reference\":\"" + REFERENCE + "\",\"trxref\":\"order-42\"},"
                + "\"authorization_url\":\"https://pay.notchpay.co/" + REFERENCE + "\"}";
    }

    static String transaction(String status) {
        return "{\"transaction\":{\"amount\":500,\"currency\":\"XAF\",\"status\":\"" + status + "\","
                + "\"reference\":\"" + REFERENCE + "\",\"trxref\":\"order-42\"}}";
    }

    static PaymentRequest payment(String phone) {
        return PaymentRequest.builder().idempotencyKey("key-1").amount(500).phoneNumber(phone)
                .merchantReference("order-42").description("Order 42").build();
    }

    void stubInitialize() {
        notch.stubFor(post(urlEqualTo("/payments")).withHeader("Authorization", equalTo(PUBLIC_KEY))
                .willReturn(json(201, initialized())));
    }

    @ParameterizedTest
    @CsvSource({"670123456, cm.mtn", "690123456, cm.orange"})
    void collectInitializesThenChargesOnTheOperatorChannel(String phone, String channel) {
        stubInitialize();
        notch.stubFor(post(urlEqualTo("/payments/" + REFERENCE))
                .willReturn(json(202, "{\"status\":\"Accepted\",\"transaction\":{\"status\":\"processing\"}}")));

        Transaction transaction = provider.collect(payment(phone));

        assertThat(transaction.providerReference()).isEqualTo(REFERENCE);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(transaction.rawStatus()).isEqualTo("processing");
        notch.verify(postRequestedFor(urlEqualTo("/payments"))
                .withHeader("X-Grant", absent())
                .withRequestBody(matchingJsonPath("$.amount", equalTo("500")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("XAF")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("order-42"))));
        notch.verify(postRequestedFor(urlEqualTo("/payments/" + REFERENCE))
                .withHeader("Authorization", equalTo(PUBLIC_KEY))
                .withRequestBody(matchingJsonPath("$.channel", equalTo(channel)))
                .withRequestBody(matchingJsonPath("$.data.phone", equalTo("237" + phone))));
    }

    @Test
    void numberWithoutKnownOperatorIsRefusedBeforeAnythingIsSent() {
        NotchPayProvider noTable = builder().prefixTable(PrefixTable.builder().range(MobileOperator.MTN, 670, 679)
                .build()).build();

        assertThatThrownBy(() -> noTable.collect(payment("690123456")))
                .isInstanceOf(UnsupportedCapabilityException.class)
                .hasMessageNotContaining("690123456");
        assertThat(notch.getAllServeEvents()).isEmpty();
    }

    @Test
    void refusalAtInitializationIsAPaymentRejection() {
        notch.stubFor(post(urlEqualTo("/payments"))
                .willReturn(json(422, "{\"code\":422,\"message\":\"The amount must be at least 100\"}")));

        assertThatThrownBy(() -> provider.collect(payment("670123456")))
                .isInstanceOfSatisfying(PaymentRejectedException.class,
                        e -> assertThat(e.getMessage()).contains("The amount must be at least 100"));
        notch.verify(0, postRequestedFor(urlEqualTo("/payments/" + REFERENCE)));
    }

    @Test
    void refusedKeysAreAnAuthenticationError() {
        notch.stubFor(post(urlEqualTo("/payments")).willReturn(json(401, "{\"message\":\"Unauthorized\"}")));

        assertThatThrownBy(() -> provider.collect(payment("670123456")))
                .isInstanceOf(ProviderAuthenticationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{\"transaction\":{}}"})
    void unusableInitializationAnswerIsAnUnknownOutcome(String body) {
        notch.stubFor(post(urlEqualTo("/payments")).willReturn(json(201, body)));

        assertThatThrownBy(() -> provider.collect(payment("670123456")))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.requestMayHaveBeenProcessed()).isTrue());
    }

    @Test
    void serverErrorOnTheChargeIsAnUnknownOutcomeThatKeepsTheReference() {
        stubInitialize();
        notch.stubFor(post(urlEqualTo("/payments/" + REFERENCE)).willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(() -> provider.collect(payment("670123456")))
                .isInstanceOfSatisfying(ProviderUnavailableException.class, e -> {
                    assertThat(e.requestMayHaveBeenProcessed()).isTrue();
                    assertThat(e.providerReference()).contains(REFERENCE);
                });
    }

    @Test
    void facadeKeepsTheReferenceOfALostCharge() {
        stubInitialize();
        notch.stubFor(post(urlEqualTo("/payments/" + REFERENCE)).willReturn(aResponse().withStatus(500)));
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).build();

        Transaction first = mobileMoney.collect(payment("670123456"));
        Transaction again = mobileMoney.collect(payment("670123456"));

        assertThat(first.providerReference()).isEqualTo(REFERENCE);
        assertThat(first.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(again).isEqualTo(first);
        notch.verify(1, postRequestedFor(urlEqualTo("/payments")));
        notch.verify(1, postRequestedFor(urlEqualTo("/payments/" + REFERENCE)));
    }

    @Test
    void refusedChargeIsAPaymentRejection() {
        stubInitialize();
        notch.stubFor(post(urlEqualTo("/payments/" + REFERENCE))
                .willReturn(json(400, "{\"message\":\"Invalid phone number\"}")));

        assertThatThrownBy(() -> provider.collect(payment("670123456")))
                .isInstanceOf(PaymentRejectedException.class);
    }

    @ParameterizedTest
    @CsvSource({"complete, SUCCESSFUL", "completed, SUCCESSFUL", "failed, FAILED", "canceled, FAILED",
        "expired, FAILED", "pending, PENDING", "processing, PENDING", "something-new, PENDING"})
    void statusIsReadAndMapped(String raw, TransactionStatus expected) {
        notch.stubFor(get(urlEqualTo("/payments/" + REFERENCE)).withHeader("Authorization", equalTo(PUBLIC_KEY))
                .willReturn(json(200, transaction(raw))));

        StatusReport report = provider.fetchStatus(TransactionType.COLLECTION, REFERENCE);

        assertThat(report.status()).isEqualTo(expected);
        assertThat(report.rawStatus()).isEqualTo(raw);
        assertThat(report.merchantReference()).isEqualTo("order-42");
        assertThat(report.amount()).isEqualTo(500);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void statusReadServerErrorIsTransient(int status) {
        notch.stubFor(get(urlEqualTo("/payments/" + REFERENCE)).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> provider.fetchStatus(TransactionType.COLLECTION, REFERENCE))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"transaction\":{\"reference\":\"x\"}}"})
    void unusableStatusAnswerIsTransient(String body) {
        notch.stubFor(get(urlEqualTo("/payments/" + REFERENCE)).willReturn(json(200, body)));

        assertThatThrownBy(() -> provider.fetchStatus(TransactionType.COLLECTION, REFERENCE))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void unknownTransactionIsNotFound() {
        notch.stubFor(get(urlEqualTo("/payments/" + REFERENCE)).willReturn(json(404, "{}")));

        assertThatThrownBy(() -> provider.fetchStatus(TransactionType.COLLECTION, REFERENCE))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void payoutSendsThePrivateKeyInXGrant() {
        notch.stubFor(post(urlEqualTo("/transfers")).withHeader("X-Grant", equalTo(PRIVATE_KEY))
                .willReturn(json(201, "{\"transfer\":{\"reference\":\"trf.test_1\",\"status\":\"pending\"}}")));

        Transaction transaction = provider.payout(PayoutRequest.builder().idempotencyKey("p-1").amount(1000)
                .phoneNumber("690123456").merchantReference("refund-7").description("Refund")
                .beneficiaryName("Jane Doe").build());

        assertThat(transaction.providerReference()).isEqualTo("trf.test_1");
        assertThat(transaction.type()).isEqualTo(TransactionType.PAYOUT);
        notch.verify(postRequestedFor(urlEqualTo("/transfers"))
                .withRequestBody(matchingJsonPath("$.channel", equalTo("cm.orange")))
                .withRequestBody(matchingJsonPath("$.beneficiary.name", equalTo("Jane Doe"))));
    }

    @Test
    void withoutPrivateKeyThereAreNoPayouts() {
        NotchPayProvider publicOnly = NotchPayProvider.builder().publicKey(PUBLIC_KEY).build();

        assertThat(publicOnly.capabilities().payout()).isFalse();
    }

    @Test
    void signedWebhookIsConfirmedByReadingTheStatus() {
        notch.stubFor(get(urlEqualTo("/payments/" + REFERENCE)).willReturn(json(200, transaction("complete"))));
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).build();
        String body = webhookBody("payment.complete", "complete");

        WebhookEvent event = mobileMoney.handleWebhook(ProviderId.NOTCHPAY, webhook(body, sign(body)));

        assertThat(event.status()).isEqualTo(TransactionStatus.SUCCESSFUL);
        assertThat(event.verification()).isEqualTo(Verification.STATUS_REREAD);
        assertThat(event.type()).isEqualTo(TransactionType.COLLECTION);
    }

    @Test
    void signedWebhookClaimingSuccessIsNotTrusted() {
        notch.stubFor(get(urlEqualTo("/payments/" + REFERENCE)).willReturn(json(200, transaction("pending"))));
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).build();
        String body = webhookBody("payment.complete", "complete");

        WebhookEvent event = mobileMoney.handleWebhook(ProviderId.NOTCHPAY, webhook(body, sign(body)));

        assertThat(event.status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void signatureIsCheckedOverTheRawBody() {
        String body = webhookBody("payment.complete", "complete");
        String signature = sign(body);

        assertThat(provider.parseWebhook(webhook(body, signature.toUpperCase())).providerReference())
                .isEqualTo(REFERENCE);
        assertThatThrownBy(() -> provider.parseWebhook(webhook(body + " ", signature)))
                .isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> provider.parseWebhook(webhook(body.replace("500", "5000"), signature)))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "sha256=0000"})
    void missingOrMalformedSignatureIsRejected(String signature) {
        String body = webhookBody("payment.complete", "complete");

        assertThatThrownBy(() -> provider.parseWebhook(webhook(body, signature)))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void signatureWithAnotherSecretIsRejected() throws Exception {
        String body = webhookBody("payment.complete", "complete");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("CANARY-FORGED-0000".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String forged = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> provider.parseWebhook(webhook(body, forged)))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void twoSignatureHeadersAreRejected() {
        String body = webhookBody("payment.complete", "complete");
        WebhookRequest request = WebhookRequest.builder().method("POST").path("/webhooks/notchpay")
                .header(NotchPayProvider.SIGNATURE_HEADER, sign(body))
                .header(NotchPayProvider.SIGNATURE_HEADER, sign(body))
                .body(body).build();

        assertThatThrownBy(() -> provider.parseWebhook(request)).isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void webhooksNeedTheSecret() {
        NotchPayProvider noSecret = NotchPayProvider.builder().publicKey(PUBLIC_KEY).build();
        String body = webhookBody("payment.complete", "complete");

        assertThatThrownBy(() -> noSecret.parseWebhook(webhook(body, sign(body))))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void configurationIsChecked() {
        assertThatThrownBy(() -> NotchPayProvider.builder().build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("public key");
        assertThatThrownBy(() -> NotchPayProvider.builder().publicKey("pk").privateKey(" ").build())
                .isInstanceOf(ConfigurationException.class);
        assertThatThrownBy(() -> NotchPayProvider.builder().publicKey("pk").baseUrl(URI.create("http://x.example"))
                .build()).isInstanceOf(ConfigurationException.class);
    }

    @Test
    void toStringShowsNoSecret() {
        assertThat(provider.toString()).doesNotContain("CANARY");
    }

    static String webhookBody(String type, String status) {
        return "{\"id\":\"evt_1\",\"type\":\"" + type + "\",\"data\":{\"amount\":500,\"currency\":\"XAF\","
                + "\"status\":\"" + status + "\",\"reference\":\"" + REFERENCE + "\",\"trxref\":\"order-42\"}}";
    }

    static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static WebhookRequest webhook(String body, String signature) {
        WebhookRequest.Builder builder = WebhookRequest.builder().method("POST").path("/webhooks/notchpay")
                .header("Content-Type", "application/json");
        if (!signature.isEmpty()) {
            builder.header(NotchPayProvider.SIGNATURE_HEADER, signature);
        }
        return builder.body(body).build();
    }
}
