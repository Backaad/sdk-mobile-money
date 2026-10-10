package io.github.backaad.mobilemoney;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.TOKEN;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.fixture;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.json;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.okJson;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.stubToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.backaad.mobilemoney.testing.CampayTestServer;
import io.github.backaad.mobilemoney.testing.MutableClock;
import io.github.backaad.mobilemoney.testing.StubProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** More idempotency, webhook and configuration cases, for payouts and edge cases. */
class MobileMoneyMoreTest {

    @RegisterExtension
    static WireMockExtension campay = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final MutableClock clock = MutableClock.at("2026-10-10T12:00:00Z");
    private MobileMoney mobileMoney;

    @BeforeEach
    void setUp() {
        stubToken(campay, TOKEN, 3600);
        mobileMoney = MobileMoney.builder().provider(CampayTestServer.provider(campay, clock).build()).clock(clock)
                .build();
    }

    private static PayoutRequest payout(String key) {
        return PayoutRequest.builder().idempotencyKey(key).amount(250).phoneNumber("699123456")
                .beneficiaryName("Jane Doe").merchantReference("payout-77").description("Refund 77").build();
    }

    private static PaymentRequest payment(String key) {
        return PaymentRequest.builder().idempotencyKey(key).amount(100).phoneNumber("670123456")
                .merchantReference("order-1234").description("Order 1234").build();
    }

    @Test
    void payoutIsNeverSentTwiceForTheSameKey() {
        campay.stubFor(post(urlEqualTo("/api/withdraw/")).willReturn(okJson(fixture("withdraw-200.json"))));

        Transaction first = mobileMoney.payout(payout("payout-77"));
        Transaction second = mobileMoney.payout(payout("payout-77"));

        assertThat(second).isEqualTo(first);
        assertThat(first.type()).isEqualTo(TransactionType.PAYOUT);
        campay.verify(1, postRequestedFor(urlEqualTo("/api/withdraw/")));
    }

    @Test
    void payoutWithLostAnswerIsNeverSentAgain() {
        campay.stubFor(post(urlEqualTo("/api/withdraw/")).willReturn(aResponse().withStatus(504)));

        assertThat(mobileMoney.payout(payout("payout-78")).outcomeUnknown()).isTrue();
        assertThat(mobileMoney.payout(payout("payout-78")).outcomeUnknown()).isTrue();
        campay.verify(1, postRequestedFor(urlEqualTo("/api/withdraw/")));
    }

    @Test
    void refusedCredentialsReleaseTheKey() {
        campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(json(401, "{}")));
        assertThatThrownBy(() -> mobileMoney.collect(payment("auth"))).isInstanceOf(ProviderAuthenticationException.class);
        campay.verify(2, postRequestedFor(urlEqualTo("/api/collect/")));

        campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(okJson(fixture("collect-200.json"))));
        assertThat(mobileMoney.collect(payment("auth")).outcomeUnknown()).isFalse();
        campay.verify(3, postRequestedFor(urlEqualTo("/api/collect/")));
    }

    @Test
    void connectionRefusedReleasesTheKey() {
        MobileMoney unreachable = MobileMoney.builder().provider(CampayTestServer.provider(campay, clock)
                .baseUrl(URI.create("http://localhost:1")).build()).build();

        assertThatThrownBy(() -> unreachable.collect(payment("refused")))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.requestMayHaveBeenProcessed()).isFalse());
        assertThatThrownBy(() -> unreachable.collect(payment("refused")))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void invalidRequestIsRefusedBeforeAnythingIsStoredOrSent() {
        assertThatThrownBy(() -> payment("bad\r\nkey")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentRequest.builder().phoneNumber("12345"))
                .isInstanceOf(InvalidPhoneNumberException.class);
        campay.verify(0, postRequestedFor(urlEqualTo("/api/collect/")));
    }

    @Test
    void providerWithoutStatusQueryCannotConfirmWebhooks() {
        StubProvider unsigned = new StubProvider("unsigned").capabilities(
                new ProviderCapabilities(true, true, false, WebhookSignature.NONE, Set.of(Currencies.XAF)));
        MobileMoney client = MobileMoney.builder().provider(unsigned).build();

        assertThatThrownBy(() -> client.handleWebhook(new ProviderId("unsigned"),
                WebhookRequest.builder().method("POST").build()))
                .isInstanceOf(WebhookVerificationException.class);
        assertThat(unsigned.statusReads()).isZero();
    }

    @Test
    void interruptedStatusReadLeavesTheTransactionUnchanged() {
        Transaction pending = new Transaction(ProviderId.CAMPAY, TransactionType.COLLECTION,
                CampayTestServer.REFERENCE, "order-1234", 5, Currencies.XAF, TransactionStatus.PENDING, null);
        Thread.currentThread().interrupt();
        try {
            assertThat(mobileMoney.status(pending)).isEqualTo(pending);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void httpClientFollowingRedirectsIsRefused() {
        assertThatThrownBy(() -> CampayTestServer.provider(campay, clock)
                .httpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()).build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("redirects");
    }

    @Test
    void campayToStringShowsNoSecret() {
        String text = CampayTestServer.provider(campay, clock).build().toString();

        assertThat(text).doesNotContain(CampayTestServer.USERNAME).doesNotContain(CampayTestServer.PASSWORD)
                .doesNotContain(CampayTestServer.WEBHOOK_KEY).contains("webhookKeyConfigured=true");
    }
}
