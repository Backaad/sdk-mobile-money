package io.github.backaad.mobilemoney;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.REFERENCE;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.TOKEN;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.WEBHOOK_KEY;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.fixture;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.json;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.okJson;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.stubToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.backaad.mobilemoney.testing.CampayTestServer;
import io.github.backaad.mobilemoney.testing.MutableClock;
import io.github.backaad.mobilemoney.testing.TestJwt;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Rule 1: the status announced by a webhook is never trusted; it is read again from the provider.
 * Rule 2: a provider's webhook can only confirm that provider's transactions.
 */
class MobileMoneyWebhookTest {

    @RegisterExtension
    static WireMockExtension campay = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final long NOW = 1791633600L;

    private final MutableClock clock = MutableClock.at("2026-10-10T12:00:00Z");
    private MobileMoney mobileMoney;

    @BeforeEach
    void setUp() {
        stubToken(campay, TOKEN, 3600);
        mobileMoney = MobileMoney.builder().provider(CampayTestServer.provider(campay, clock).build()).clock(clock)
                .build();
    }

    static WebhookRequest webhook(String claimedStatus, String reference, String externalReference, String key) {
        String signature = TestJwt.hs256("{\"exp\":" + (NOW + 300) + "}", key);
        return WebhookRequest.builder().method("GET").path("/webhooks/campay")
                .rawQuery("status=" + claimedStatus + "&reference=" + reference + "&amount=500000&currency=XAF"
                        + (externalReference == null ? "" : "&external_reference=" + externalReference)
                        + "&signature=" + URLEncoder.encode(signature, StandardCharsets.UTF_8))
                .build();
    }

    static WebhookRequest webhook(String claimedStatus) {
        return webhook(claimedStatus, REFERENCE, "order-1234", WEBHOOK_KEY);
    }

    private static void stubStatus(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        campay.stubFor(get(urlEqualTo("/api/transaction/" + REFERENCE + "/")).willReturn(response));
    }

    @Nested
    class Rule1StatusIsReadAgain {

        @Test
        void claimedSuccessIsIgnoredWhenTheProviderSaysPending() {
            stubStatus(okJson(fixture("transaction-pending.json")));

            WebhookEvent event = mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook("SUCCESSFUL"));

            assertThat(event.status()).isEqualTo(TransactionStatus.PENDING);
            assertThat(event.isFinal()).isFalse();
            assertThat(event.claimedRawStatus()).isEqualTo("SUCCESSFUL");
            assertThat(event.verification()).isEqualTo(Verification.STATUS_REREAD);
            campay.verify(1, getRequestedFor(urlEqualTo("/api/transaction/" + REFERENCE + "/")));
        }

        @Test
        void claimedFailureIsIgnoredWhenTheProviderSaysSuccessful() {
            stubStatus(okJson(fixture("transaction-successful.json")));

            WebhookEvent event = mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook("FAILED"));

            assertThat(event.status()).isEqualTo(TransactionStatus.SUCCESSFUL);
            assertThat(event.rawStatus()).isEqualTo("SUCCESSFUL");
        }

        @Test
        void everyFieldComesFromTheProvider() {
            // The webhook claims 500000 XAF; the provider says 5 XAF.
            stubStatus(okJson(fixture("transaction-successful.json")));

            WebhookEvent event = mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook("SUCCESSFUL"));

            assertThat(event.amount()).isEqualTo(5L);
            assertThat(event.currency()).isEqualTo(Currencies.XAF);
            assertThat(event.merchantReference()).isEqualTo("order-1234");
            assertThat(event.providerReference()).isEqualTo(REFERENCE);
            assertThat(event.provider()).isEqualTo(ProviderId.CAMPAY);
        }

        @Test
        void transientReadFailureAsksForANewDelivery() {
            stubStatus(aResponse().withStatus(503));

            assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook("SUCCESSFUL")))
                    .isInstanceOf(ProviderUnavailableException.class);
        }

        @Test
        void invalidSignatureIsRejectedBeforeAnyRead() {
            assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.CAMPAY,
                    webhook("SUCCESSFUL", REFERENCE, "order-1234", "forged-key")))
                    .isInstanceOf(WebhookVerificationException.class);
            campay.verify(0, getRequestedFor(urlEqualTo("/api/transaction/" + REFERENCE + "/")));
        }
    }

    @Nested
    class Rule2OnlyItsOwnTransactions {

        @Test
        void referenceUnknownToTheProviderIsRejected() {
            // A valid signature, but the transaction belongs to another provider or account.
            stubStatus(json(404, "{\"message\": \"Transaction not found\"}"));

            assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook("SUCCESSFUL")))
                    .isInstanceOf(WebhookVerificationException.class)
                    .hasMessageContaining("unknown to campay");
        }

        @Test
        void merchantReferenceMustMatchTheProvidersRecord() {
            stubStatus(okJson(fixture("transaction-successful.json")));

            assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.CAMPAY,
                    webhook("SUCCESSFUL", REFERENCE, "order-9999-expensive", WEBHOOK_KEY)))
                    .isInstanceOf(WebhookVerificationException.class)
                    .hasMessageContaining("merchant reference");
        }

        @Test
        void merchantReferenceClaimedButNotReturnedIsRejected() {
            stubStatus(okJson("{\"reference\": \"" + REFERENCE + "\", \"status\": \"SUCCESSFUL\"}"));

            assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook("SUCCESSFUL")))
                    .isInstanceOf(WebhookVerificationException.class);
        }

        @Test
        void providerReturningAnotherTransactionIsRejected() {
            stubStatus(okJson("{\"reference\": \"11111111-2222-3333-4444-555555555555\", "
                    + "\"external_reference\": \"order-1234\", \"status\": \"SUCCESSFUL\"}"));

            assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook("SUCCESSFUL")))
                    .isInstanceOf(WebhookVerificationException.class)
                    .hasMessageContaining("another transaction");
        }

        @Test
        void webhookSentToAnotherProvidersEndpointIsRejected() {
            MobileMoney simulation = MobileMoney.builder()
                    .provider(new io.github.backaad.mobilemoney.provider.simulation.SimulationProvider()).build();

            assertThatThrownBy(() -> simulation.handleWebhook(ProviderId.SIMULATION, webhook("SUCCESSFUL")))
                    .isInstanceOf(WebhookVerificationException.class);
            assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.NOTCHPAY, webhook("SUCCESSFUL")))
                    .isInstanceOf(WebhookVerificationException.class)
                    .hasMessageContaining("No provider configured");
            campay.verify(0, anyRequestedFor(anyUrl()));
        }

        @Test
        void eventNamesTheProviderOfTheEndpoint() {
            stubStatus(okJson(fixture("transaction-successful.json")));

            assertThat(mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook("SUCCESSFUL")).provider())
                    .isEqualTo(ProviderId.CAMPAY);
        }
    }
}
