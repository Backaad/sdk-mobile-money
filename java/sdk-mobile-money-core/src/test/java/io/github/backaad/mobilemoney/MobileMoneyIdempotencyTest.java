package io.github.backaad.mobilemoney;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.REFERENCE;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.TOKEN;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.fixture;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.json;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.okJson;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.stubToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.backaad.mobilemoney.idempotency.IdempotencyRecord;
import io.github.backaad.mobilemoney.idempotency.IdempotencyStore;
import io.github.backaad.mobilemoney.idempotency.InMemoryIdempotencyStore;
import io.github.backaad.mobilemoney.testing.CampayTestServer;
import io.github.backaad.mobilemoney.testing.MutableClock;
import io.github.backaad.mobilemoney.testing.StubProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Rule 9: nothing is debited or paid out twice for the same idempotency key. Each test counts the
 * requests that actually reach the provider.
 */
class MobileMoneyIdempotencyTest {

    @RegisterExtension
    static WireMockExtension campay = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final MutableClock clock = MutableClock.at("2026-10-10T12:00:00Z");
    private MobileMoney mobileMoney;

    @BeforeEach
    void setUp() {
        stubToken(campay, TOKEN, 3600);
        mobileMoney = MobileMoney.builder()
                .provider(CampayTestServer.provider(campay, clock).build())
                .clock(clock)
                .build();
    }

    static PaymentRequest payment(String key) {
        return PaymentRequest.builder()
                .idempotencyKey(key)
                .amount(100)
                .phoneNumber("670123456")
                .merchantReference("order-1234")
                .description("Order 1234")
                .build();
    }

    private void stubCollect(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(response));
    }

    private void verifyCollects(int count) {
        campay.verify(count, postRequestedFor(urlEqualTo("/api/collect/")));
    }

    @Test
    void sameKeyReturnsTheFirstResultWithoutSendingAgain() {
        stubCollect(okJson(fixture("collect-200.json")));

        Transaction first = mobileMoney.collect(payment("order-1234"));
        Transaction second = mobileMoney.collect(payment("order-1234"));

        assertThat(second).isEqualTo(first);
        assertThat(first.providerReference()).isEqualTo(REFERENCE);
        verifyCollects(1);
    }

    @Test
    void sameKeyWithTheNumberWrittenDifferentlyIsTheSameRequest() {
        stubCollect(okJson(fixture("collect-200.json")));

        mobileMoney.collect(payment("order-1234"));
        mobileMoney.collect(PaymentRequest.builder().idempotencyKey("order-1234").amount(100)
                .phoneNumber("+237 6 70 12 34 56").merchantReference("order-1234").description("Another text")
                .build());

        verifyCollects(1);
    }

    @Test
    void doubleTapWaitsForTheFirstRequestAndGetsTheSameResult() throws Exception {
        stubCollect(okJson(fixture("collect-200.json")).withFixedDelay(400));
        mobileMoney = MobileMoney.builder()
                .provider(CampayTestServer.provider(campay, java.time.Clock.systemUTC()).build())
                .build();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Transaction> results = new ArrayList<>();
        try {
            List<Callable<Transaction>> taps = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                taps.add(() -> mobileMoney.collect(payment("double-tap")));
            }
            for (Future<Transaction> f : pool.invokeAll(taps)) {
                results.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).hasSize(8).allSatisfy(t -> assertThat(t.providerReference()).isEqualTo(REFERENCE));
        verifyCollects(1);
    }

    @Test
    void lostAnswerGivesAPendingTransactionThatIsNeverSentAgain() {
        stubCollect(okJson(fixture("collect-200.json")).withFixedDelay(2_000));

        Transaction first = mobileMoney.collect(payment("timeout"));
        Transaction replay = mobileMoney.collect(payment("timeout"));

        assertThat(first.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(first.outcomeUnknown()).isTrue();
        assertThat(first.merchantReference()).isEqualTo("order-1234");
        assertThat(replay).isEqualTo(first);
        verifyCollects(1);
    }

    @Test
    void serverErrorOrBrokenAnswerIsAnUnknownOutcome() {
        stubCollect(aResponse().withStatus(502));
        assertThat(mobileMoney.collect(payment("bad-gateway")).outcomeUnknown()).isTrue();
        assertThat(mobileMoney.collect(payment("bad-gateway")).outcomeUnknown()).isTrue();
        verifyCollects(1);

        stubCollect(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));
        assertThat(mobileMoney.collect(payment("reset")).outcomeUnknown()).isTrue();
        mobileMoney.collect(payment("reset"));

        stubCollect(okJson("{\"ussd_code\": \"*126#\"}"));
        assertThat(mobileMoney.collect(payment("no-reference")).outcomeUnknown()).isTrue();
        mobileMoney.collect(payment("no-reference"));

        verifyCollects(3);
    }

    @Test
    void refusalIsReplayedWithoutSendingAgain() {
        stubCollect(json(400, fixture("error-ER101.json")));

        assertThatThrownBy(() -> mobileMoney.collect(payment("refused"))).isInstanceOf(PaymentRejectedException.class);
        assertThatThrownBy(() -> mobileMoney.collect(payment("refused")))
                .isInstanceOfSatisfying(PaymentRejectedException.class,
                        e -> assertThat(e.providerCode()).contains("ER101"));
        verifyCollects(1);
    }

    @Test
    void whenNothingWasSentTheSameKeyCanBeRetried() {
        campay.stubFor(CampayTestServer.tokenRequest().willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> mobileMoney.collect(payment("retry")))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.requestMayHaveBeenProcessed()).isFalse());
        verifyCollects(0);

        stubToken(campay, TOKEN, 3600);
        stubCollect(okJson(fixture("collect-200.json")));
        assertThat(mobileMoney.collect(payment("retry")).providerReference()).isEqualTo(REFERENCE);
        verifyCollects(1);
    }

    @Test
    void sameKeyForADifferentRequestIsRefused() {
        stubCollect(okJson(fixture("collect-200.json")));
        mobileMoney.collect(payment("order-1234"));

        PaymentRequest otherAmount = PaymentRequest.builder().idempotencyKey("order-1234").amount(999)
                .phoneNumber("670123456").merchantReference("order-1234").description("Order 1234").build();

        assertThatThrownBy(() -> mobileMoney.collect(otherAmount)).isInstanceOf(IdempotencyKeyConflictException.class);
        verifyCollects(1);
    }

    @Test
    void keyIsValidFor24Hours() {
        stubCollect(okJson(fixture("collect-200.json")));
        mobileMoney.collect(payment("order-1234"));

        clock.advance(Duration.ofHours(24).minusSeconds(1));
        mobileMoney.collect(payment("order-1234"));
        verifyCollects(1);

        clock.advance(Duration.ofSeconds(1));
        mobileMoney.collect(payment("order-1234"));
        verifyCollects(2);
    }

    @Test
    void unexpectedProviderErrorIsAnUnknownOutcome() {
        StubProvider stub = new StubProvider("stub").onCollect(request -> {
            throw new IllegalStateException("bug after the debit was accepted");
        });
        MobileMoney client = MobileMoney.builder().provider(stub).build();

        assertThat(client.collect(payment("bug")).outcomeUnknown()).isTrue();
        assertThat(client.collect(payment("bug")).outcomeUnknown()).isTrue();
        assertThat(stub.collects()).isEqualTo(1);
    }

    @Test
    void storeFailureMeansNothingIsSent() {
        IdempotencyStore broken = new FailingStore();
        StubProvider stub = new StubProvider("stub");
        MobileMoney client = MobileMoney.builder().provider(stub).idempotencyStore(broken).build();

        assertThatThrownBy(() -> client.collect(payment("store-down"))).isInstanceOf(IdempotencyStoreException.class);
        assertThat(stub.collects()).isZero();
    }

    @Test
    void storeFailureAfterTheProviderAnsweredStillReturnsTheResult() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyStore failingOnFinish = new IdempotencyStore() {
            @Override
            public Reservation reserve(String key, String fp, Transaction pending, Duration lease, Duration ttl) {
                return store.reserve(key, fp, pending, lease, ttl);
            }

            @Override
            public Optional<IdempotencyRecord> find(String key) {
                return store.find(key);
            }

            @Override
            public boolean finish(String key, String owner, IdempotencyRecord.State state, Transaction t,
                    IdempotencyRecord.Rejection r) {
                throw new IllegalStateException("store down");
            }

            @Override
            public boolean release(String key, String owner) {
                throw new IllegalStateException("store down");
            }
        };
        StubProvider stub = new StubProvider("stub");
        MobileMoney client = MobileMoney.builder().provider(stub).idempotencyStore(failingOnFinish)
                .inProgressWait(Duration.ZERO).build();

        assertThat(client.collect(payment("finish-fails")).providerReference()).isNotNull();
        // The record stays in progress: a replay is refused, never sent again.
        assertThatThrownBy(() -> client.collect(payment("finish-fails"))).isInstanceOf(RequestInProgressException.class);
        assertThat(stub.collects()).isEqualTo(1);
    }

    @Test
    void requestLeftInProgressByADeadProcessIsNeverSentAgain() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock, 10);
        PaymentRequest request = payment("crashed");
        Transaction pending = new Transaction(ProviderId.CAMPAY, TransactionType.COLLECTION, null, "order-1234", 100,
                Currencies.XAF, TransactionStatus.PENDING, null);
        store.reserve("crashed", io.github.backaad.mobilemoney.idempotency.Fingerprint.of(request), pending,
                Duration.ofMinutes(3), Duration.ofHours(24));
        mobileMoney = MobileMoney.builder().provider(CampayTestServer.provider(campay, clock).build())
                .idempotencyStore(store).clock(clock).inProgressWait(Duration.ZERO).build();

        assertThatThrownBy(() -> mobileMoney.collect(request)).isInstanceOf(RequestInProgressException.class);
        clock.advance(Duration.ofMinutes(3));
        assertThat(mobileMoney.collect(request).outcomeUnknown()).isTrue();
        verifyCollects(0);
    }

    @Test
    void waitForARequestInProgressIsBoundedInRealTimeEvenWithAFrozenClock() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock, 10);
        PaymentRequest request = payment("frozen");
        Transaction pending = new Transaction(ProviderId.CAMPAY, TransactionType.COLLECTION, null, "order-1234", 100,
                Currencies.XAF, TransactionStatus.PENDING, null);
        store.reserve("frozen", io.github.backaad.mobilemoney.idempotency.Fingerprint.of(request), pending,
                Duration.ofMinutes(3), Duration.ofHours(24));
        MobileMoney client = MobileMoney.builder().provider(CampayTestServer.provider(campay, clock).build())
                .idempotencyStore(store).clock(clock).inProgressWait(Duration.ofMillis(300)).build();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> client.collect(request)).isInstanceOf(RequestInProgressException.class));
        verifyCollects(0);
    }

    /** A store that is always down. */
    static final class FailingStore implements IdempotencyStore {

        @Override
        public Reservation reserve(String key, String fingerprint, Transaction pending, Duration lease, Duration ttl) {
            throw new IllegalStateException("connection refused");
        }

        @Override
        public Optional<IdempotencyRecord> find(String key) {
            throw new IllegalStateException("connection refused");
        }

        @Override
        public boolean finish(String key, String ownerToken, IdempotencyRecord.State state, Transaction transaction,
                IdempotencyRecord.Rejection rejection) {
            throw new IllegalStateException("connection refused");
        }

        @Override
        public boolean release(String key, String ownerToken) {
            throw new IllegalStateException("connection refused");
        }
    }
}
