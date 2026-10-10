package io.github.backaad.mobilemoney;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.PASSWORD;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.REFERENCE;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.TOKEN;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.WEBHOOK_KEY;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.fixture;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.json;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.okJson;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.stubToken;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.tokenRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.backaad.mobilemoney.idempotency.IdempotencyRecord;
import io.github.backaad.mobilemoney.idempotency.IdempotencyStore;
import io.github.backaad.mobilemoney.idempotency.InMemoryIdempotencyStore;
import io.github.backaad.mobilemoney.provider.campay.CampayProvider;
import io.github.backaad.mobilemoney.testing.CampayTestServer;
import io.github.backaad.mobilemoney.testing.MutableClock;
import io.github.backaad.mobilemoney.testing.StubProvider;
import io.github.backaad.mobilemoney.testing.TestJwt;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Cases found by the adversarial review of step 2. */
class ReviewFixesTest {

    @RegisterExtension
    static WireMockExtension campay = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final long NOW = 1791633600L;

    private final MutableClock clock = MutableClock.at("2026-10-10T12:00:00Z");
    private CampayProvider provider;
    private MobileMoney mobileMoney;

    @BeforeEach
    void setUp() {
        stubToken(campay, TOKEN, 3600);
        provider = CampayTestServer.provider(campay, clock).build();
        mobileMoney = MobileMoney.builder().provider(provider).clock(clock).build();
    }

    private static PaymentRequest payment(String key) {
        return PaymentRequest.builder().idempotencyKey(key).amount(100).phoneNumber("670123456")
                .merchantReference("order-1234").description("Order 1234").build();
    }

    private static WebhookRequest webhook(String reference, String extra) {
        String signature = TestJwt.hs256("{\"exp\":" + (NOW + 300) + "}", WEBHOOK_KEY);
        return WebhookRequest.builder().method("GET").rawQuery("status=SUCCESSFUL&reference=" + reference
                + "&external_reference=order-1234" + extra + "&signature="
                + URLEncoder.encode(signature, StandardCharsets.UTF_8)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"collect", "withdraw"})
    void typeClaimedByTheWebhookAloneNeverReachesTheEvent(String endpoint) {
        campay.stubFor(get(urlEqualTo("/api/transaction/" + REFERENCE + "/"))
                .willReturn(okJson(fixture("transaction-successful.json"))));

        WebhookEvent event = mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook(REFERENCE, "&endpoint=" + endpoint));

        assertThat(event.type()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", ".hidden", "-x"})
    void referencesMustStartWithALetterOrDigit(String reference) {
        assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook(reference, "")))
                .isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> provider.fetchStatus(null, reference))
                .isInstanceOf(TransactionNotFoundException.class);
        campay.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void secretCutByTheMessageLengthLimitIsStillRemoved() {
        campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(json(400,
                "{\"message\": \"" + "x".repeat(190) + " " + PASSWORD + "\", \"error_code\": \"ER101\"}")));

        assertThatThrownBy(() -> mobileMoney.collect(payment("echo")))
                .isInstanceOfSatisfying(PaymentRejectedException.class, e -> assertThat(e.getMessage())
                        .doesNotContain(PASSWORD.substring(0, 6)).contains("***"));
    }

    @Test
    void stalledResponseBodyIsBoundedByTheRequestTimeout() {
        campay.stubFor(post(urlEqualTo("/api/collect/"))
                .willReturn(okJson(fixture("collect-200.json")).withChunkedDribbleDelay(20, 5_000)));

        Transaction transaction = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> mobileMoney.collect(payment("dribble")));

        assertThat(transaction.outcomeUnknown()).isTrue();
    }

    @Test
    void stalledTokenBodyDoesNotBlockOtherCallers() throws Exception {
        campay.stubFor(tokenRequest().willReturn(okJson("{\"token\": \"" + TOKEN + "\", \"expires_in\": 3600}")
                .withChunkedDribbleDelay(20, 8_000)));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        long start = System.nanoTime();
        int failures = 0;
        try {
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String key = "stall-" + i;
                calls.add(() -> mobileMoney.collect(payment(key)));
            }
            for (Future<Object> f : pool.invokeAll(calls)) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOfSatisfying(ProviderUnavailableException.class,
                            u -> assertThat(u.requestMayHaveBeenProcessed()).isFalse());
                    failures++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).isEqualTo(6);
        // One token request for everyone, and nobody waits for the 8-second body.
        campay.verify(1, postRequestedFor(urlEqualTo("/api/token/")));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        campay.verify(0, postRequestedFor(urlEqualTo("/api/collect/")));
    }

    @Test
    void failedTokenFetchIsSharedByTheCallersWaitingForIt() throws Exception {
        campay.stubFor(tokenRequest().willReturn(aResponse().withStatus(503).withFixedDelay(600)));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> provider.fetchStatus(null, REFERENCE));
            }
            for (Future<Object> f : pool.invokeAll(calls)) {
                assertThatThrownBy(f::get).hasCauseInstanceOf(ProviderUnavailableException.class);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(campay.findAll(postRequestedFor(urlEqualTo("/api/token/")))).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void refusalWithoutMessageIsStillARefusal() {
        StubProvider stub = new StubProvider("stub").onCollect(request -> {
            throw new PaymentRejectedException(null, new ProviderId("stub"), "E1");
        });
        MobileMoney client = MobileMoney.builder().provider(stub).build();

        assertThatThrownBy(() -> client.collect(payment("no-message"))).isInstanceOf(PaymentRejectedException.class);
        assertThatThrownBy(() -> client.collect(payment("no-message")))
                .isInstanceOfSatisfying(PaymentRejectedException.class,
                        e -> assertThat(e.providerCode()).contains("E1"));
        assertThat(stub.collects()).isEqualTo(1);
    }

    @Test
    void releaseAfterTheLeaseKeepsTheOutcomeUnknown() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock, 10);
        Transaction pending = new Transaction(ProviderId.CAMPAY, TransactionType.COLLECTION, null, "order-1", 100,
                Currencies.XAF, TransactionStatus.PENDING, null);
        String owner = ((IdempotencyStore.Acquired) store.reserve("k", "fp", pending, Duration.ofMinutes(3),
                Duration.ofHours(24))).ownerToken();

        clock.advance(Duration.ofMinutes(3));

        assertThat(store.release("k", owner)).isFalse();
        assertThat(store.find("k")).get().extracting(IdempotencyRecord::state)
                .isEqualTo(IdempotencyRecord.State.OUTCOME_UNKNOWN);
    }
}
