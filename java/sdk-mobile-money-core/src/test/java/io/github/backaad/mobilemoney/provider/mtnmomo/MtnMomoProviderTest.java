package io.github.backaad.mobilemoney.provider.mtnmomo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.Currencies;
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
import io.github.backaad.mobilemoney.Verification;
import io.github.backaad.mobilemoney.WebhookEvent;
import io.github.backaad.mobilemoney.WebhookRequest;
import io.github.backaad.mobilemoney.WebhookVerificationException;
import io.github.backaad.mobilemoney.provider.StatusReport;
import io.github.backaad.mobilemoney.testing.MutableClock;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MTN MoMo adapter against WireMock: success and refusal, 401 then token renewal, 5xx and empty responses,
 * unknown status, separate tokens per product, unsigned callbacks confirmed by a status read. Request and
 * response shapes follow the MoMo developer portal (see docs/providers/mtn-momo.md).
 */
class MtnMomoProviderTest {

    static final String COLLECTION_KEY = "CANARY-MTN-COLL-SUB-1a2b";
    static final String COLLECTION_USER = "CANARY-MTN-COLL-USER-3c4d";
    static final String COLLECTION_API_KEY = "CANARY-MTN-COLL-APIKEY-5e6f";
    static final String DISBURSEMENT_KEY = "CANARY-MTN-DISB-SUB-7a8b";
    static final String DISBURSEMENT_USER = "CANARY-MTN-DISB-USER-9c0d";
    static final String DISBURSEMENT_API_KEY = "CANARY-MTN-DISB-APIKEY-1e2f";
    static final String TOKEN = "CANARY-MTN-TOKEN-3a4b";
    static final String TOKEN_2 = "CANARY-MTN-TOKEN-RENEWED-5c6d";
    static final String DISB_TOKEN = "CANARY-MTN-DISB-TOKEN-7e8f";
    static final String REFERENCE = "3f2a8c1e-5b6d-4e7f-8a9b-0c1d2e3f4a5b";

    @RegisterExtension
    static WireMockExtension mtn = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final MutableClock clock = MutableClock.at("2026-01-15T10:00:00Z");

    private MtnMomoProvider provider;

    @BeforeEach
    void setUp() {
        provider = builder().build();
    }

    MtnMomoProvider.Builder builder() {
        return MtnMomoProvider.builder()
                .environment(MtnMomoEnvironment.SANDBOX)
                .baseUrl(URI.create(mtn.baseUrl()))
                .collection(COLLECTION_KEY, COLLECTION_USER, COLLECTION_API_KEY)
                .disbursement(DISBURSEMENT_KEY, DISBURSEMENT_USER, DISBURSEMENT_API_KEY)
                .requestTimeout(Duration.ofMillis(800))
                .clock(clock);
    }

    static String basic(String user, String key) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    static void stubTokens(WireMockExtension server) {
        server.stubFor(post(urlEqualTo("/collection/token/"))
                .withHeader("Authorization", equalTo(basic(COLLECTION_USER, COLLECTION_API_KEY)))
                .withHeader("Ocp-Apim-Subscription-Key", equalTo(COLLECTION_KEY))
                .willReturn(okJson("{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"access_token\","
                        + "\"expires_in\":3600}")));
        server.stubFor(post(urlEqualTo("/disbursement/token/"))
                .withHeader("Authorization", equalTo(basic(DISBURSEMENT_USER, DISBURSEMENT_API_KEY)))
                .withHeader("Ocp-Apim-Subscription-Key", equalTo(DISBURSEMENT_KEY))
                .willReturn(okJson("{\"access_token\":\"" + DISB_TOKEN + "\",\"token_type\":\"access_token\","
                        + "\"expires_in\":3600}")));
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    static PaymentRequest payment(String callbackUrl) {
        PaymentRequest.Builder builder = PaymentRequest.builder().idempotencyKey("key-1").amount(500)
                .phoneNumber("+237 670 12 34 56").merchantReference("order-42").description("Order 42");
        if (callbackUrl != null) {
            builder.callbackUrl(URI.create(callbackUrl));
        }
        return builder.build();
    }

    static String statusJson(String status, String externalId) {
        return "{\"amount\":\"500\",\"currency\":\"XAF\",\"financialTransactionId\":\"23503452\","
                + "\"externalId\":\"" + externalId + "\",\"payer\":{\"partyIdType\":\"MSISDN\","
                + "\"partyId\":\"237670123456\"},\"payerMessage\":\"Order 42\",\"payeeNote\":\"Order 42\","
                + "\"status\":\"" + status + "\"}";
    }

    private String sentReference() {
        List<LoggedRequest> sent = mtn.findAll(postRequestedFor(urlEqualTo("/collection/v1_0/requesttopay")));
        return sent.get(sent.size() - 1).getHeader("X-Reference-Id");
    }

    @Test
    void collectSendsRequestToPayAndReturnsPendingWithTheReferenceItChose() {
        stubTokens(mtn);
        mtn.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay")).willReturn(aResponse().withStatus(202)));

        Transaction transaction = provider.collect(payment("https://shop.example/callbacks/mtn?shop=1"));

        String reference = sentReference();
        assertThat(reference).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(transaction.provider()).isEqualTo(ProviderId.MTN_MOMO);
        assertThat(transaction.providerReference()).isEqualTo(reference);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
        mtn.verify(postRequestedFor(urlEqualTo("/collection/v1_0/requesttopay"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Ocp-Apim-Subscription-Key", equalTo(COLLECTION_KEY))
                .withHeader("X-Target-Environment", equalTo("sandbox"))
                .withHeader("X-Callback-Url", equalTo("https://shop.example/callbacks/mtn?shop=1&mm_reference="
                        + reference + "&mm_type=collection"))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("500")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("XAF")))
                .withRequestBody(matchingJsonPath("$.externalId", equalTo("order-42")))
                .withRequestBody(matchingJsonPath("$.payer.partyIdType", equalTo("MSISDN")))
                .withRequestBody(matchingJsonPath("$.payer.partyId", equalTo("237670123456"))));
    }

    @Test
    void eachCollectUsesANewReference() {
        stubTokens(mtn);
        mtn.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay")).willReturn(aResponse().withStatus(202)));

        String first = provider.collect(payment(null)).providerReference();
        String second = provider.collect(payment(null)).providerReference();

        assertThat(first).isNotEqualTo(second);
        mtn.verify(1, postRequestedFor(urlEqualTo("/collection/token/")));
    }

    @Test
    void refusalCarriesMtnErrorCode() {
        stubTokens(mtn);
        mtn.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"PAYER_NOT_FOUND\",\"message\":\"Payee does not exist\"}")));

        assertThatThrownBy(() -> provider.collect(payment(null)))
                .isInstanceOfSatisfying(PaymentRejectedException.class, e -> {
                    assertThat(e.providerCode()).contains("PAYER_NOT_FOUND");
                    assertThat(e.getMessage()).contains("Payee does not exist");
                });
    }

    @Test
    void after401TheTokenIsRenewedAndTheSameRequestSentOnceMoreWithTheSameReference() {
        mtn.stubFor(post(urlEqualTo("/collection/token/")).inScenario("token")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("renewed")
                .willReturn(okJson("{\"access_token\":\"" + TOKEN + "\",\"expires_in\":3600}")));
        mtn.stubFor(post(urlEqualTo("/collection/token/")).inScenario("token").whenScenarioStateIs("renewed")
                .willReturn(okJson("{\"access_token\":\"" + TOKEN_2 + "\",\"expires_in\":3600}")));
        mtn.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)).willReturn(aResponse().withStatus(401)));
        mtn.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN_2)).willReturn(aResponse().withStatus(202)));

        Transaction transaction = provider.collect(payment(null));

        List<LoggedRequest> sent = mtn.findAll(postRequestedFor(urlEqualTo("/collection/v1_0/requesttopay")));
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).getHeader("X-Reference-Id")).isEqualTo(sent.get(0).getHeader("X-Reference-Id"))
                .isEqualTo(transaction.providerReference());
    }

    @Test
    void twoRefusedTokensMeanAnAuthenticationError() {
        stubTokens(mtn);
        mtn.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay")).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> provider.collect(payment(null)))
                .isInstanceOf(ProviderAuthenticationException.class);
        mtn.verify(2, postRequestedFor(urlEqualTo("/collection/v1_0/requesttopay")));
    }

    @Test
    void refusedCredentialsMeanAnAuthenticationErrorAndNothingIsSent() {
        mtn.stubFor(post(urlEqualTo("/collection/token/")).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> provider.collect(payment(null)))
                .isInstanceOf(ProviderAuthenticationException.class);
        mtn.verify(0, postRequestedFor(urlEqualTo("/collection/v1_0/requesttopay")));
    }

    @Test
    void tokenEndpointDownMeansNothingWasSent() {
        mtn.stubFor(post(urlEqualTo("/collection/token/")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> provider.collect(payment(null)))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.requestMayHaveBeenProcessed()).isFalse());
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 409})
    void serverErrorIsAnUnknownOutcomeThatKeepsTheReference(int status) {
        stubTokens(mtn);
        mtn.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay")).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> provider.collect(payment(null)))
                .isInstanceOfSatisfying(ProviderUnavailableException.class, e -> {
                    assertThat(e.requestMayHaveBeenProcessed()).isTrue();
                    assertThat(e.providerReference()).contains(sentReference());
                });
    }

    @Test
    void timeoutIsAnUnknownOutcomeThatKeepsTheReference() {
        stubTokens(mtn);
        mtn.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay"))
                .willReturn(aResponse().withStatus(202).withFixedDelay(2000)));

        assertThatThrownBy(() -> provider.collect(payment(null)))
                .isInstanceOfSatisfying(ProviderUnavailableException.class, e -> {
                    assertThat(e.requestMayHaveBeenProcessed()).isTrue();
                    assertThat(e.providerReference()).isPresent();
                });
    }

    @Test
    void facadeTurnsALostAnswerIntoAPendingTransactionThatCanBeLookedUp() {
        stubTokens(mtn);
        mtn.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).build();

        Transaction first = mobileMoney.collect(payment(null));
        Transaction again = mobileMoney.collect(payment(null));

        assertThat(first.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(first.providerReference()).isEqualTo(sentReference());
        assertThat(again).isEqualTo(first);
        mtn.verify(1, postRequestedFor(urlEqualTo("/collection/v1_0/requesttopay")));
    }

    @Test
    void payoutUsesTheDisbursementProductAndItsOwnToken() {
        stubTokens(mtn);
        mtn.stubFor(post(urlEqualTo("/disbursement/v1_0/transfer")).willReturn(aResponse().withStatus(202)));

        Transaction transaction = provider.payout(PayoutRequest.builder().idempotencyKey("p-1").amount(1000)
                .phoneNumber("650123456").merchantReference("refund-7").description("Refund")
                .beneficiaryName("Jane Doe").build());

        assertThat(transaction.type()).isEqualTo(TransactionType.PAYOUT);
        mtn.verify(postRequestedFor(urlEqualTo("/disbursement/v1_0/transfer"))
                .withHeader("Authorization", equalTo("Bearer " + DISB_TOKEN))
                .withHeader("Ocp-Apim-Subscription-Key", equalTo(DISBURSEMENT_KEY))
                .withHeader("X-Reference-Id", equalTo(transaction.providerReference()))
                .withRequestBody(matchingJsonPath("$.payee.partyId", equalTo("237650123456")))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("1000"))));
        mtn.verify(0, postRequestedFor(urlEqualTo("/collection/token/")));
    }

    @Test
    void productsWithoutCredentialsAreNotOffered() {
        MtnMomoProvider collectionOnly = MtnMomoProvider.builder().environment(MtnMomoEnvironment.SANDBOX)
                .collection(COLLECTION_KEY, COLLECTION_USER, COLLECTION_API_KEY).build();

        assertThat(collectionOnly.capabilities().collection()).isTrue();
        assertThat(collectionOnly.capabilities().payout()).isFalse();
        assertThatThrownBy(() -> collectionOnly.fetchStatus(TransactionType.PAYOUT, REFERENCE))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESSFUL", "FAILED", "PENDING", "ONGOING"})
    void statusIsReadAndMapped(String raw) {
        stubTokens(mtn);
        mtn.stubFor(get(urlEqualTo("/collection/v1_0/requesttopay/" + REFERENCE))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("X-Target-Environment", equalTo("sandbox"))
                .willReturn(okJson(statusJson(raw, "order-42"))));

        StatusReport report = provider.fetchStatus(TransactionType.COLLECTION, REFERENCE);

        TransactionStatus expected = switch (raw) {
            case "SUCCESSFUL" -> TransactionStatus.SUCCESSFUL;
            case "FAILED" -> TransactionStatus.FAILED;
            default -> TransactionStatus.PENDING;
        };
        assertThat(report.status()).isEqualTo(expected);
        assertThat(report.rawStatus()).isEqualTo(raw);
        assertThat(report.merchantReference()).isEqualTo("order-42");
        assertThat(report.amount()).isEqualTo(500);
        assertThat(report.currency()).isEqualTo(Currencies.XAF);
        assertThat(report.type()).isEqualTo(TransactionType.COLLECTION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "not json", "[]"})
    void unusableStatusAnswerIsTransient(String body) {
        stubTokens(mtn);
        mtn.stubFor(get(urlEqualTo("/collection/v1_0/requesttopay/" + REFERENCE)).willReturn(okJson(body)));

        assertThatThrownBy(() -> provider.fetchStatus(TransactionType.COLLECTION, REFERENCE))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void statusReadServerErrorIsTransient() {
        stubTokens(mtn);
        mtn.stubFor(get(urlEqualTo("/collection/v1_0/requesttopay/" + REFERENCE))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> provider.fetchStatus(TransactionType.COLLECTION, REFERENCE))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void unknownTypeLooksInBothProducts() {
        stubTokens(mtn);
        mtn.stubFor(get(urlEqualTo("/collection/v1_0/requesttopay/" + REFERENCE))
                .willReturn(aResponse().withStatus(404)));
        mtn.stubFor(get(urlEqualTo("/disbursement/v1_0/transfer/" + REFERENCE))
                .willReturn(okJson(statusJson("SUCCESSFUL", "refund-7"))));

        StatusReport report = provider.fetchStatus(null, REFERENCE);

        assertThat(report.type()).isEqualTo(TransactionType.PAYOUT);
        assertThat(report.status()).isEqualTo(TransactionStatus.SUCCESSFUL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../token", "abc", "3F2A8C1E-5B6D-4E7F-8A9B-0C1D2E3F4A5B"})
    void malformedReferencesAreNeverSent(String reference) {
        assertThatThrownBy(() -> provider.fetchStatus(TransactionType.COLLECTION, reference))
                .isInstanceOf(TransactionNotFoundException.class);
        assertThat(mtn.getAllServeEvents()).isEmpty();
    }

    @Test
    void callbackIsConfirmedByReadingTheStatus() {
        stubTokens(mtn);
        mtn.stubFor(get(urlEqualTo("/collection/v1_0/requesttopay/" + REFERENCE))
                .willReturn(okJson(statusJson("SUCCESSFUL", "order-42"))));
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).build();

        WebhookEvent event = mobileMoney.handleWebhook(ProviderId.MTN_MOMO, callback("PUT",
                "mm_reference=" + REFERENCE + "&mm_type=collection", statusJson("SUCCESSFUL", "order-42")));

        assertThat(event.status()).isEqualTo(TransactionStatus.SUCCESSFUL);
        assertThat(event.verification()).isEqualTo(Verification.STATUS_REREAD);
        assertThat(event.providerReference()).isEqualTo(REFERENCE);
        assertThat(event.merchantReference()).isEqualTo("order-42");
    }

    @Test
    void callbackClaimingSuccessIsNotTrusted() {
        stubTokens(mtn);
        mtn.stubFor(get(urlEqualTo("/collection/v1_0/requesttopay/" + REFERENCE))
                .willReturn(okJson(statusJson("PENDING", "order-42"))));
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).build();

        WebhookEvent event = mobileMoney.handleWebhook(ProviderId.MTN_MOMO, callback("POST",
                "mm_reference=" + REFERENCE, statusJson("SUCCESSFUL", "order-42")));

        assertThat(event.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(event.claimedRawStatus()).isEqualTo("SUCCESSFUL");
    }

    @Test
    void callbackForAnotherOrderIsRejected() {
        stubTokens(mtn);
        mtn.stubFor(get(urlEqualTo("/collection/v1_0/requesttopay/" + REFERENCE))
                .willReturn(okJson(statusJson("SUCCESSFUL", "order-42"))));
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).build();

        assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.MTN_MOMO, callback("POST",
                "mm_reference=" + REFERENCE, statusJson("SUCCESSFUL", "order-99"))))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "mm_reference=abc", "mm_reference=" + REFERENCE + "&mm_reference=" + REFERENCE,
        "mm_reference=" + REFERENCE + "&mm_type=refund"})
    void malformedCallbacksAreRejected(String query) {
        assertThatThrownBy(() -> provider.parseWebhook(callback("POST", query, "{}")))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void callbackWithGetIsRejected() {
        assertThatThrownBy(() -> provider.parseWebhook(callback("GET", "mm_reference=" + REFERENCE, "")))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void configurationIsChecked() {
        assertThatThrownBy(() -> MtnMomoProvider.builder().collection("a", "b", "c").build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("environment");
        assertThatThrownBy(() -> MtnMomoProvider.builder().environment(MtnMomoEnvironment.SANDBOX).build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("credentials");
        assertThatThrownBy(() -> MtnMomoProvider.builder().environment(MtnMomoEnvironment.SANDBOX)
                .collection("a", " ", "c").build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("API user");
        assertThatThrownBy(() -> MtnMomoProvider.builder().environment(MtnMomoEnvironment.PRODUCTION)
                .collection("a", "b", "c").build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("target environment");
        assertThatThrownBy(() -> MtnMomoProvider.builder().environment(MtnMomoEnvironment.PRODUCTION)
                .targetEnvironment("sandbox").collection("a", "b", "c").build())
                .isInstanceOf(ConfigurationException.class);
        assertThatThrownBy(() -> MtnMomoProvider.builder().environment(MtnMomoEnvironment.SANDBOX)
                .baseUrl(URI.create("http://mtn.example")).collection("a", "b", "c").build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("HTTPS");
        assertThat(MtnMomoProvider.builder().environment(MtnMomoEnvironment.PRODUCTION)
                .targetEnvironment("mtncameroon").collection("a", "b", "c").build()).isNotNull();
    }

    @Test
    void sandboxCurrencyCanBeConfigured() {
        MtnMomoProvider eur = builder().currency(java.util.Currency.getInstance("EUR")).build();

        assertThat(eur.capabilities().currencies()).containsExactly(java.util.Currency.getInstance("EUR"));
    }

    @Test
    void toStringShowsNoSecret() {
        assertThat(provider.toString()).doesNotContain("CANARY");
    }

    private static WebhookRequest callback(String method, String query, String body) {
        return WebhookRequest.builder().method(method).path("/callbacks/mtn")
                .rawQuery(query.isEmpty() ? null : query)
                .header("Content-Type", "application/json").body(body).build();
    }
}
