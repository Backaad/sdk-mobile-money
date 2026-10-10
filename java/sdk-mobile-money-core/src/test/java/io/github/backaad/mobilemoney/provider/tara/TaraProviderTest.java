package io.github.backaad.mobilemoney.provider.tara;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.Currencies;
import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.PaymentRejectedException;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.ProviderAuthenticationException;
import io.github.backaad.mobilemoney.ProviderCapabilities;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.UnsupportedCapabilityException;
import io.github.backaad.mobilemoney.Verification;
import io.github.backaad.mobilemoney.WebhookEvent;
import io.github.backaad.mobilemoney.WebhookRequest;
import io.github.backaad.mobilemoney.WebhookSignature;
import io.github.backaad.mobilemoney.WebhookVerificationException;
import io.github.backaad.mobilemoney.internal.Json;
import io.github.backaad.mobilemoney.testing.MutableClock;
import io.github.backaad.mobilemoney.testing.StubProvider;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tara (experimental) against WireMock, through {@link MobileMoney}: each payment gets a single-use,
 * unpredictable, expiring callback URL, and a callback must match the expected reference, amount and number.
 * The request and callback field names are still to be confirmed (docs/providers/tara.md).
 */
class TaraProviderTest {

    static final String API_KEY = "CANARY-TARA-APIKEY-1a2b";
    static final String BUSINESS_ID = "CANARY-TARA-BUSINESS-3c4d";

    @RegisterExtension
    static WireMockExtension tara = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final MutableClock clock = MutableClock.at("2026-01-15T10:00:00Z");
    private TaraProvider provider;
    private MobileMoney mobileMoney;

    @BeforeEach
    void setUp() {
        provider = builder().build();
        mobileMoney = MobileMoney.builder().provider(provider).clock(clock).build();
    }

    TaraProvider.Builder builder() {
        return TaraProvider.builder().baseUrl(URI.create(tara.baseUrl())).apiKey(API_KEY).businessId(BUSINESS_ID)
                .requestTimeout(Duration.ofMillis(800));
    }

    void stubAccepted() {
        tara.stubFor(post(urlEqualTo("/api/tara/mobilepay")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"SUCCESS\",\"message\":\"Dial *126# to confirm\"}")));
    }

    static PaymentRequest payment(String key) {
        return PaymentRequest.builder().idempotencyKey(key).amount(2000).phoneNumber("655251245")
                .merchantReference("order-" + key).description("Premium, 1 month")
                .callbackUrl(URI.create("https://shop.example/callbacks/tara?shop=1")).build();
    }

    /** The callback URL that was given to Tara for the last payment. */
    String sentCallbackUrl() {
        var sent = tara.findAll(postRequestedFor(urlEqualTo("/api/tara/mobilepay")));
        return Json.text(Json.parse(sent.get(sent.size() - 1).getBody()), "webHookUrl");
    }

    static WebhookRequest callback(String url, String body) {
        URI uri = URI.create(url);
        return WebhookRequest.builder().method("POST").path(uri.getPath()).rawQuery(uri.getRawQuery())
                .header("Content-Type", "application/json").remoteAddress("203.0.113.7").body(body).build();
    }

    static String body(String reference, String status, long amount, String phone) {
        return "{\"productId\":\"" + reference + "\",\"status\":\"" + status + "\",\"amount\":" + amount
                + ",\"phoneNumber\":\"" + phone + "\"}";
    }

    @Test
    void collectSendsAUniqueCallbackUrlAndReturnsPending() {
        stubAccepted();

        Transaction transaction = mobileMoney.collect(payment("1"));

        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(transaction.provider()).isEqualTo(ProviderId.TARA);
        assertThat(transaction.providerReference()).isEqualTo("order-1");
        assertThat(sentCallbackUrl()).matches(
                "https://shop\\.example/callbacks/tara\\?shop=1&mm_token=[A-Za-z0-9_-]{22}");
        tara.verify(postRequestedFor(urlEqualTo("/api/tara/mobilepay"))
                .withRequestBody(matchingJsonPath("$.apiKey", equalTo(API_KEY)))
                .withRequestBody(matchingJsonPath("$.businessId", equalTo(BUSINESS_ID)))
                .withRequestBody(matchingJsonPath("$.productId", equalTo("order-1")))
                .withRequestBody(matchingJsonPath("$.productPrice", equalTo("2000")))
                .withRequestBody(matchingJsonPath("$.phoneNumber", equalTo("237655251245"))));
    }

    @Test
    void everyPaymentGetsADifferentToken() {
        stubAccepted();

        mobileMoney.collect(payment("1"));
        String first = sentCallbackUrl();
        mobileMoney.collect(payment("2"));

        assertThat(sentCallbackUrl()).isNotEqualTo(first);
    }

    @Test
    void matchingCallbackConfirmsThePaymentOnce() {
        stubAccepted();
        mobileMoney.collect(payment("1"));
        String url = sentCallbackUrl();

        WebhookEvent event = mobileMoney.handleWebhook(ProviderId.TARA,
                callback(url, body("order-1", "SUCCESS", 2000, "237655251245")));

        assertThat(event.status()).isEqualTo(TransactionStatus.SUCCESSFUL);
        assertThat(event.verification()).isEqualTo(Verification.CALLBACK_TOKEN);
        assertThat(event.merchantReference()).isEqualTo("order-1");
        assertThat(event.amount()).isEqualTo(2000);
        // Single use: the same callback again is refused.
        assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.TARA,
                callback(url, body("order-1", "SUCCESS", 2000, "237655251245"))))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void tokenIsKeptWhenRecordingTheOutcomeFails() {
        stubAccepted();
        mobileMoney.collect(payment("1"));
        String url = sentCallbackUrl();
        String body = body("order-1", "SUCCESS", 2000, "237655251245");

        assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.TARA, callback(url, body), event -> {
            throw new IllegalStateException("database down");
        })).isInstanceOf(IllegalStateException.class);
        List<WebhookEvent> recorded = new java.util.ArrayList<>();
        mobileMoney.handleWebhook(ProviderId.TARA, callback(url, body), recorded::add);
        assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.TARA, callback(url, body), recorded::add))
                .isInstanceOf(WebhookVerificationException.class);

        assertThat(recorded).singleElement().satisfies(e -> assertThat(e.status())
                .isEqualTo(TransactionStatus.SUCCESSFUL));
    }

    @Test
    void failedPaymentIsReportedAsFailed() {
        stubAccepted();
        mobileMoney.collect(payment("1"));

        WebhookEvent event = mobileMoney.handleWebhook(ProviderId.TARA,
                callback(sentCallbackUrl(), body("order-1", "FAILURE", 2000, "+237 655 25 12 45")));

        assertThat(event.status()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void unknownStatusStaysPendingAndKeepsTheToken() {
        stubAccepted();
        mobileMoney.collect(payment("1"));
        String url = sentCallbackUrl();

        WebhookEvent pending = mobileMoney.handleWebhook(ProviderId.TARA,
                callback(url, body("order-1", "IN_PROGRESS", 2000, "237655251245")));
        WebhookEvent done = mobileMoney.handleWebhook(ProviderId.TARA,
                callback(url, body("order-1", "SUCCESS", 2000, "237655251245")));

        assertThat(pending.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(done.status()).isEqualTo(TransactionStatus.SUCCESSFUL);
    }

    @Test
    void callbackThatDoesNotMatchThePaymentIsRejected() {
        stubAccepted();
        mobileMoney.collect(payment("1"));
        String url = sentCallbackUrl();

        for (String wrong : List.of(body("order-2", "SUCCESS", 2000, "237655251245"),
                body("order-1", "SUCCESS", 20, "237655251245"),
                body("order-1", "SUCCESS", 2000, "237670123456"),
                "{\"productId\":\"order-1\",\"status\":\"SUCCESS\",\"phoneNumber\":\"237655251245\"}",
                "{\"productId\":\"order-1\",\"status\":\"SUCCESS\",\"amount\":2000}")) {
            assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.TARA, callback(url, wrong)))
                    .as(wrong).isInstanceOf(WebhookVerificationException.class);
        }
        // The right callback still works: failed attempts do not use the token.
        assertThat(mobileMoney.handleWebhook(ProviderId.TARA,
                callback(url, body("order-1", "SUCCESS", 2000, "237655251245"))).status())
                .isEqualTo(TransactionStatus.SUCCESSFUL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://shop.example/callbacks/tara",
        "https://shop.example/callbacks/tara?mm_token=AAAAAAAAAAAAAAAAAAAAAA",
        "https://shop.example/callbacks/tara?mm_token=a&mm_token=b"})
    void callbackWithoutAKnownTokenIsRejected(String url) {
        stubAccepted();
        mobileMoney.collect(payment("1"));

        assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.TARA,
                callback(url, body("order-1", "SUCCESS", 2000, "237655251245"))))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void tokenExpires() {
        stubAccepted();
        mobileMoney.collect(payment("1"));
        clock.advance(Duration.ofHours(25));

        assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.TARA,
                callback(sentCallbackUrl(), body("order-1", "SUCCESS", 2000, "237655251245"))))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void aTokenOnlyWorksForTheProviderItWasCreatedFor() {
        stubAccepted();
        // Another provider whose callbacks are also protected by single-use URLs.
        StubProvider other = new StubProvider("campay").capabilities(new ProviderCapabilities(true, false, false,
                WebhookSignature.CALLBACK_TOKEN, Set.of(Currencies.XAF)));
        MobileMoney shared = MobileMoney.builder().provider(provider).provider(other).clock(clock).build();
        shared.collect(payment("1"));

        assertThatThrownBy(() -> shared.handleWebhook(ProviderId.CAMPAY,
                callback(sentCallbackUrl(), body("order-1", "SUCCESS", 2000, "237655251245"))))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void callbackFromAnAddressOutsideTheAllowlistIsRejected() {
        stubAccepted();
        TaraProvider allowlisted = builder().allowedCallbackAddresses(List.of("198.51.100.1")).build();
        MobileMoney guarded = MobileMoney.builder().provider(allowlisted).clock(clock).build();
        guarded.collect(payment("1"));

        assertThatThrownBy(() -> guarded.handleWebhook(ProviderId.TARA,
                callback(sentCallbackUrl(), body("order-1", "SUCCESS", 2000, "237655251245"))))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void callbackFromAnAllowedAddressIsAccepted() {
        stubAccepted();
        TaraProvider allowlisted = builder().allowedCallbackAddresses(List.of("203.0.113.7")).build();
        MobileMoney guarded = MobileMoney.builder().provider(allowlisted).clock(clock).build();
        guarded.collect(payment("1"));

        assertThat(guarded.handleWebhook(ProviderId.TARA,
                callback(sentCallbackUrl(), body("order-1", "SUCCESS", 2000, "237655251245"))).status())
                .isEqualTo(TransactionStatus.SUCCESSFUL);
    }

    @Test
    void paymentWithoutCallbackUrlIsRefusedBeforeAnythingIsSent() {
        PaymentRequest noCallback = PaymentRequest.builder().idempotencyKey("1").amount(2000)
                .phoneNumber("655251245").merchantReference("order-1").description("d").build();

        assertThatThrownBy(() -> mobileMoney.collect(noCallback)).isInstanceOf(UnsupportedCapabilityException.class);
        assertThat(tara.getAllServeEvents()).isEmpty();
    }

    @Test
    void refusalIsAPaymentRejection() {
        tara.stubFor(post(urlEqualTo("/api/tara/mobilepay")).willReturn(aResponse().withStatus(200)
                .withBody("{\"status\":\"FAILURE\",\"message\":\"Invalid phone number\"}")));

        assertThatThrownBy(() -> mobileMoney.collect(payment("1"))).isInstanceOf(PaymentRejectedException.class);
    }

    @Test
    void refusedKeyIsAnAuthenticationError() {
        tara.stubFor(post(urlEqualTo("/api/tara/mobilepay")).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> mobileMoney.collect(payment("1")))
                .isInstanceOf(ProviderAuthenticationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{\"status\":\"QUEUED\"}"})
    void unusableOrUnknownAnswerIsAnUnknownOutcome(String answer) {
        tara.stubFor(post(urlEqualTo("/api/tara/mobilepay")).willReturn(aResponse().withStatus(200).withBody(answer)));

        Transaction transaction = mobileMoney.collect(payment("1"));
        Transaction again = mobileMoney.collect(payment("1"));

        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(again).isEqualTo(transaction);
        tara.verify(1, postRequestedFor(urlEqualTo("/api/tara/mobilepay")));
    }

    @Test
    void serverErrorIsAnUnknownOutcomeButTheCallbackStillWorks() {
        tara.stubFor(post(urlEqualTo("/api/tara/mobilepay")).willReturn(aResponse().withStatus(500)));

        Transaction transaction = mobileMoney.collect(payment("1"));

        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(mobileMoney.handleWebhook(ProviderId.TARA,
                callback(sentCallbackUrl(), body("order-1", "SUCCESS", 2000, "237655251245"))).status())
                .isEqualTo(TransactionStatus.SUCCESSFUL);
    }

    @Test
    void providerLevelErrorsAreClassified() {
        tara.stubFor(post(urlEqualTo("/api/tara/mobilepay")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> provider.collect(payment("1")))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.requestMayHaveBeenProcessed()).isTrue());
    }

    @Test
    void taraHasNoStatusQueryAndNoPayout() {
        assertThat(provider.capabilities().statusQuery()).isFalse();
        assertThat(provider.capabilities().payout()).isFalse();
    }

    @Test
    void configurationIsChecked() {
        assertThatThrownBy(() -> TaraProvider.builder().businessId("b").build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("API key");
        assertThatThrownBy(() -> TaraProvider.builder().apiKey("a").build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("business");
        assertThatThrownBy(() -> TaraProvider.builder().apiKey("a").businessId("b")
                .allowedCallbackAddresses(List.of(" ")).build()).isInstanceOf(ConfigurationException.class);
    }

    @Test
    void toStringShowsNoSecret() {
        assertThat(provider.toString()).doesNotContain("CANARY");
    }
}
