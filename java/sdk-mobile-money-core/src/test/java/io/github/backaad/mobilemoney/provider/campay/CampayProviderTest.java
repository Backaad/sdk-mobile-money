package io.github.backaad.mobilemoney.provider.campay;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.REFERENCE;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.TOKEN;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.TOKEN_2;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.fixture;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.json;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.okJson;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.stubToken;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.tokenRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.backaad.mobilemoney.Currencies;
import io.github.backaad.mobilemoney.PaymentRejectedException;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.PayoutRequest;
import io.github.backaad.mobilemoney.ProviderAuthenticationException;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionNotFoundException;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.WebhookSignature;
import io.github.backaad.mobilemoney.provider.StatusReport;
import io.github.backaad.mobilemoney.testing.CampayTestServer;
import io.github.backaad.mobilemoney.testing.MutableClock;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Campay adapter against WireMock: success and refusal, 401 then token renewal, 5xx and empty responses,
 * unknown status. The fixtures and their sources (official examples or third-party code) are listed in
 * src/test/resources/campay/README.md.
 */
class CampayProviderTest {

    @RegisterExtension
    static WireMockExtension campay = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final MutableClock clock = MutableClock.at("2026-10-10T12:00:00Z");
    private CampayProvider provider;

    @BeforeEach
    void setUp() {
        stubToken(campay, TOKEN, 3600);
        provider = CampayTestServer.provider(campay, clock).build();
    }

    static PaymentRequest payment() {
        return PaymentRequest.builder()
                .idempotencyKey("key-1")
                .amount(100)
                .phoneNumber("+237 6 70 12 34 56")
                .merchantReference("order-1234")
                .description("Order 1234")
                .build();
    }

    static PayoutRequest payout() {
        return PayoutRequest.builder()
                .idempotencyKey("key-2")
                .amount(250)
                .phoneNumber("699 12 34 56")
                .beneficiaryName("Jane Doe")
                .merchantReference("payout-77")
                .description("Refund 77")
                .build();
    }

    @Test
    void capabilities() {
        assertThat(provider.capabilities().collection()).isTrue();
        assertThat(provider.capabilities().payout()).isTrue();
        assertThat(provider.capabilities().statusQuery()).isTrue();
        assertThat(provider.capabilities().webhookSignature()).isEqualTo(WebhookSignature.JWT_HS256);
        assertThat(provider.capabilities().currencies()).containsExactly(Currencies.XAF);
    }

    @Nested
    class Collect {

        @Test
        void sendsTheDocumentedRequestAndReturnsAPendingTransaction() {
            campay.stubFor(post(urlEqualTo("/api/collect/"))
                    .withHeader("Authorization", equalTo("Token " + TOKEN))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withRequestBody(equalToJson("""
                            {"amount": "100", "currency": "XAF", "from": "237670123456",
                             "description": "Order 1234", "external_reference": "order-1234"}"""))
                    .willReturn(okJson(fixture("collect-200.json"))));

            Transaction transaction = provider.collect(payment());

            assertThat(transaction.provider()).isEqualTo(io.github.backaad.mobilemoney.ProviderId.CAMPAY);
            assertThat(transaction.type()).isEqualTo(TransactionType.COLLECTION);
            assertThat(transaction.providerReference()).isEqualTo(REFERENCE);
            assertThat(transaction.merchantReference()).isEqualTo("order-1234");
            assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
            assertThat(transaction.amount()).isEqualTo(100);
            assertThat(transaction.currency()).isEqualTo(Currencies.XAF);
            campay.verify(postRequestedFor(urlEqualTo("/api/collect/"))
                    .withRequestBody(matchingJsonPath("$.redirect_url", absent())));
        }

        @Test
        void refusalBecomesAPaymentRejectedException() {
            campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(json(400, fixture("error-ER101.json"))));

            assertThatThrownBy(() -> provider.collect(payment()))
                    .isInstanceOfSatisfying(PaymentRejectedException.class, e -> {
                        assertThat(e.providerCode()).contains("ER101");
                        assertThat(e.getMessage()).contains("ER101").contains("Invalid phone number");
                    });
        }

        @Test
        void refusalWithoutJsonBodyIsStillARejection() {
            campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(aResponse().withStatus(400).withBody("<html>")));

            assertThatThrownBy(() -> provider.collect(payment()))
                    .isInstanceOfSatisfying(PaymentRejectedException.class,
                            e -> assertThat(e.providerCode()).isEmpty());
        }

        @Test
        void renewsTheTokenAfterA401AndSendsTheRequestOnceMore() {
            campay.stubFor(post(urlEqualTo("/api/collect/"))
                    .withHeader("Authorization", equalTo("Token " + TOKEN))
                    .willReturn(json(401, "{\"detail\": \"Invalid token.\"}")));
            campay.stubFor(post(urlEqualTo("/api/collect/"))
                    .withHeader("Authorization", equalTo("Token " + TOKEN_2))
                    .willReturn(okJson(fixture("collect-200.json"))));
            campay.stubFor(tokenRequest().inScenario("renewal").whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(okJson("{\"token\": \"" + TOKEN + "\", \"expires_in\": 3600}"))
                    .willSetStateTo("first token given"));
            campay.stubFor(tokenRequest().inScenario("renewal").whenScenarioStateIs("first token given")
                    .willReturn(okJson("{\"token\": \"" + TOKEN_2 + "\", \"expires_in\": 3600}")));

            Transaction transaction = provider.collect(payment());

            assertThat(transaction.providerReference()).isEqualTo(REFERENCE);
            campay.verify(2, postRequestedFor(urlEqualTo("/api/token/")));
            campay.verify(2, postRequestedFor(urlEqualTo("/api/collect/")));
        }

        @Test
        void a401AfterRenewalIsAnAuthenticationError() {
            campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(json(401, "{}")));

            assertThatThrownBy(() -> provider.collect(payment())).isInstanceOf(ProviderAuthenticationException.class);
            campay.verify(2, postRequestedFor(urlEqualTo("/api/collect/")));
        }

        @ParameterizedTest
        @ValueSource(ints = {500, 502, 503, 504})
        void serverErrorsMayHaveBeenProcessed(int status) {
            campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(aResponse().withStatus(status)));

            assertUnavailable(() -> provider.collect(payment()), true);
            campay.verify(1, postRequestedFor(urlEqualTo("/api/collect/")));
        }

        @Test
        void emptySuccessResponseMayHaveBeenProcessed() {
            campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(aResponse().withStatus(200)));

            assertUnavailable(() -> provider.collect(payment()), true);
        }

        @Test
        void successWithoutReferenceMayHaveBeenProcessed() {
            campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(okJson("{\"ussd_code\": \"*126#\"}")));

            assertUnavailable(() -> provider.collect(payment()), true);
        }

        @Test
        void droppedConnectionMayHaveBeenProcessed() {
            campay.stubFor(post(urlEqualTo("/api/collect/"))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertUnavailable(() -> provider.collect(payment()), true);
        }

        @Test
        void timeoutMayHaveBeenProcessed() {
            campay.stubFor(post(urlEqualTo("/api/collect/"))
                    .willReturn(okJson(fixture("collect-200.json")).withFixedDelay(2_000)));

            assertUnavailable(() -> provider.collect(payment()), true);
        }

        @Test
        void rateLimitWasNotProcessed() {
            campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(aResponse().withStatus(429)));

            assertUnavailable(() -> provider.collect(payment()), false);
        }

        @Test
        void redirectIsNotFollowed() {
            campay.stubFor(post(urlEqualTo("/api/collect/"))
                    .willReturn(aResponse().withStatus(302).withHeader("Location", "https://elsewhere.example/")));

            assertUnavailable(() -> provider.collect(payment()), true);
        }

        @Test
        void conflictMayMeanTheTransactionAlreadyExists() {
            campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(json(409, "{\"message\": \"Duplicate\"}")));

            assertUnavailable(() -> provider.collect(payment()), true);
        }

        @Test
        void unprocessableIsARejection() {
            campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(json(422, "{\"message\": \"Invalid amount\"}")));

            assertThatThrownBy(() -> provider.collect(payment())).isInstanceOf(PaymentRejectedException.class);
        }

        @Test
        void connectionRefusedWasNotProcessed() {
            CampayProvider closed = CampayTestServer.provider(campay, clock)
                    .baseUrl(java.net.URI.create("http://localhost:1")).build();

            assertUnavailable(() -> closed.collect(payment()), false);
        }

        @Test
        void forbiddenIsAnAuthenticationError() {
            campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(json(403, "{\"message\": \"Forbidden\"}")));

            assertThatThrownBy(() -> provider.collect(payment())).isInstanceOf(ProviderAuthenticationException.class);
        }
    }

    @Nested
    class Payout {

        @Test
        void sendsTheDocumentedRequest() {
            campay.stubFor(post(urlEqualTo("/api/withdraw/"))
                    .withHeader("Authorization", equalTo("Token " + TOKEN))
                    .withRequestBody(equalToJson("""
                            {"amount": "250", "currency": "XAF", "to": "237699123456",
                             "description": "Refund 77", "external_reference": "payout-77"}"""))
                    .willReturn(okJson(fixture("withdraw-200.json"))));

            Transaction transaction = provider.payout(payout());

            assertThat(transaction.type()).isEqualTo(TransactionType.PAYOUT);
            assertThat(transaction.providerReference()).isEqualTo("26676007-1c31-46d7-9c71-acb031cf0de4");
            assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
            assertThat(transaction.rawStatus()).isEqualTo("PENDING");
        }

        @Test
        void refusal() {
            campay.stubFor(post(urlEqualTo("/api/withdraw/")).willReturn(json(400,
                    "{\"message\": \"Withdrawal is above your current balance\", \"error_code\": \"ER301\"}")));

            assertThatThrownBy(() -> provider.payout(payout()))
                    .isInstanceOfSatisfying(PaymentRejectedException.class,
                            e -> assertThat(e.providerCode()).contains("ER301"));
        }

        @Test
        void renewsTheTokenAfterA401() {
            campay.stubFor(post(urlEqualTo("/api/withdraw/"))
                    .inScenario("withdraw").whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(json(401, "{}")).willSetStateTo("refused once"));
            campay.stubFor(post(urlEqualTo("/api/withdraw/"))
                    .inScenario("withdraw").whenScenarioStateIs("refused once")
                    .willReturn(okJson(fixture("withdraw-200.json"))));

            assertThat(provider.payout(payout()).providerReference()).isNotBlank();
            campay.verify(2, postRequestedFor(urlEqualTo("/api/token/")));
            campay.verify(2, postRequestedFor(urlEqualTo("/api/withdraw/")));
        }

        @Test
        void serverErrorAndEmptyResponseMayHaveBeenProcessed() {
            campay.stubFor(post(urlEqualTo("/api/withdraw/")).willReturn(aResponse().withStatus(500)));
            assertUnavailable(() -> provider.payout(payout()), true);

            campay.stubFor(post(urlEqualTo("/api/withdraw/")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
            assertUnavailable(() -> provider.payout(payout()), true);
        }
    }

    @Nested
    class Status {

        @Test
        void successful() {
            stubStatus(okJson(fixture("transaction-successful.json")));

            StatusReport report = provider.fetchStatus(TransactionType.COLLECTION, REFERENCE);

            assertThat(report.status()).isEqualTo(TransactionStatus.SUCCESSFUL);
            assertThat(report.rawStatus()).isEqualTo("SUCCESSFUL");
            assertThat(report.providerReference()).isEqualTo(REFERENCE);
            assertThat(report.merchantReference()).isEqualTo("order-1234");
            assertThat(report.amount()).isEqualTo(5L);
            assertThat(report.currency()).isEqualTo(Currencies.XAF);
            campay.verify(getRequestedFor(urlEqualTo("/api/transaction/" + REFERENCE + "/"))
                    .withHeader("Authorization", equalTo("Token " + TOKEN)));
        }

        @Test
        void failed() {
            stubStatus(okJson(fixture("transaction-failed.json")));

            assertThat(provider.fetchStatus(null, REFERENCE).status()).isEqualTo(TransactionStatus.FAILED);
        }

        @Test
        void pendingWithDecimalAmount() {
            stubStatus(okJson(fixture("transaction-pending.json")));

            StatusReport report = provider.fetchStatus(null, REFERENCE);

            assertThat(report.status()).isEqualTo(TransactionStatus.PENDING);
            assertThat(report.amount()).isEqualTo(1L);
        }

        @ParameterizedTest
        @ValueSource(strings = {"WEIRD", "CANCELLED", "TIMEOUT", "SUCCESS"})
        void unknownStatusIsPendingWithTheRawStatusKept(String raw) {
            stubStatus(okJson("{\"reference\": \"" + REFERENCE + "\", \"status\": \"" + raw + "\"}"));

            StatusReport report = provider.fetchStatus(null, REFERENCE);

            assertThat(report.status()).isEqualTo(TransactionStatus.PENDING);
            assertThat(report.rawStatus()).isEqualTo(raw);
        }

        @Test
        void notFound() {
            stubStatus(json(404, "{\"message\": \"Transaction not found\"}"));

            assertThatThrownBy(() -> provider.fetchStatus(null, REFERENCE))
                    .isInstanceOf(TransactionNotFoundException.class);
        }

        @Test
        void renewsTheTokenAfterA401() {
            campay.stubFor(get(urlEqualTo("/api/transaction/" + REFERENCE + "/"))
                    .inScenario("status").whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(json(401, "{}")).willSetStateTo("refused once"));
            campay.stubFor(get(urlEqualTo("/api/transaction/" + REFERENCE + "/"))
                    .inScenario("status").whenScenarioStateIs("refused once")
                    .willReturn(okJson(fixture("transaction-successful.json"))));

            assertThat(provider.fetchStatus(null, REFERENCE).status()).isEqualTo(TransactionStatus.SUCCESSFUL);
            campay.verify(2, postRequestedFor(urlEqualTo("/api/token/")));
        }

        @ParameterizedTest
        @ValueSource(ints = {400, 403, 429, 500, 502, 503, 302})
        void otherAnswersAreTransient(int status) {
            stubStatus(aResponse().withStatus(status));

            assertUnavailable(() -> provider.fetchStatus(null, REFERENCE), false);
        }

        @Test
        void emptyOrUnreadableResponsesAreTransient() {
            stubStatus(aResponse().withStatus(200));
            assertUnavailable(() -> provider.fetchStatus(null, REFERENCE), false);

            stubStatus(aResponse().withStatus(200).withBody("not json"));
            assertUnavailable(() -> provider.fetchStatus(null, REFERENCE), false);

            stubStatus(okJson("{}"));
            assertUnavailable(() -> provider.fetchStatus(null, REFERENCE), false);

            stubStatus(aResponse().withFault(Fault.EMPTY_RESPONSE));
            assertThatThrownBy(() -> provider.fetchStatus(null, REFERENCE))
                    .isInstanceOf(ProviderUnavailableException.class);
        }

        @Test
        void referenceIsCheckedBeforeBuildingTheUrl() {
            assertThatThrownBy(() -> provider.fetchStatus(null, "../balance"))
                    .isInstanceOf(TransactionNotFoundException.class);
            campay.verify(0, getRequestedFor(urlEqualTo("/api/balance/")));
        }

        private void stubStatus(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
            campay.stubFor(get(urlEqualTo("/api/transaction/" + REFERENCE + "/")).willReturn(response));
        }
    }

    @Nested
    class Token {

        @Test
        void wrongCredentialsAreAnAuthenticationError() {
            campay.stubFor(tokenRequest().willReturn(json(400,
                    "{\"non_field_errors\": [\"Unable to log in with provided credentials.\"]}")));

            assertThatThrownBy(() -> provider.collect(payment())).isInstanceOf(ProviderAuthenticationException.class);
            campay.verify(0, postRequestedFor(urlEqualTo("/api/collect/")));
        }

        @Test
        void tokenEndpointFailureMeansNothingWasSent() {
            campay.stubFor(tokenRequest().willReturn(aResponse().withStatus(503)));
            assertUnavailable(() -> provider.collect(payment()), false);

            campay.stubFor(tokenRequest().willReturn(aResponse().withStatus(200)));
            assertUnavailable(() -> provider.collect(payment()), false);

            campay.stubFor(tokenRequest().willReturn(okJson("{\"expires_in\": 3600}")));
            assertUnavailable(() -> provider.collect(payment()), false);

            campay.stubFor(tokenRequest().willReturn(okJson(fixture("collect-200.json")).withFixedDelay(2_000)));
            assertUnavailable(() -> provider.collect(payment()), false);

            campay.verify(0, postRequestedFor(urlEqualTo("/api/collect/")));
        }
    }

    static void assertUnavailable(Supplier<?> call, boolean mayHaveBeenProcessed) {
        assertThatThrownBy(call::get)
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.requestMayHaveBeenProcessed()).isEqualTo(mayHaveBeenProcessed));
    }
}
