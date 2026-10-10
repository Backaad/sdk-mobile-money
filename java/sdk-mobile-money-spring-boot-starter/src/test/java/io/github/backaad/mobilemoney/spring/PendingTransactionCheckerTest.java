package io.github.backaad.mobilemoney.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.spring.event.PaymentConfirmedEvent;
import io.github.backaad.mobilemoney.spring.event.PaymentFailedEvent;
import io.github.backaad.mobilemoney.spring.event.PaymentOutcomeEvent;
import io.github.backaad.mobilemoney.spring.pending.InMemoryPendingTransactionStore;
import io.github.backaad.mobilemoney.spring.pending.PendingTransactionChecker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The optional task that reads pending payments again (rule 4: a transient error leaves them pending). */
class PendingTransactionCheckerTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-10T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final List<PaymentOutcomeEvent> events = new ArrayList<>();
    private final TestProvider provider = new TestProvider();
    private final InMemoryPendingTransactionStore store = new InMemoryPendingTransactionStore();
    private MobileMoney mobileMoney;
    private PendingTransactionChecker checker;

    @BeforeEach
    void setUp() {
        mobileMoney = MobileMoney.builder().provider(provider)
                .transactionListener(t -> store.add(t, now.get(), now.get().plus(Duration.ofMinutes(1)))).build();
        checker = new PendingTransactionChecker(mobileMoney, store, e -> events.add((PaymentOutcomeEvent) e),
                Duration.ofMinutes(1), Duration.ofHours(24), 10, clock);
    }

    private Transaction collect(String key) {
        return mobileMoney.collect(PaymentRequest.builder().idempotencyKey(key).amount(100).phoneNumber("670123456")
                .merchantReference("order-" + key).description("d").build());
    }

    private void advance(Duration duration) {
        now.set(now.get().plus(duration));
    }

    @Test
    void outcomeIsPublishedOnceAndThePaymentIsNoLongerRead() {
        Transaction t = collect("1");
        assertThat(checker.checkNow()).as("not due yet").isZero();

        advance(Duration.ofMinutes(1));
        assertThat(checker.checkNow()).as("still pending").isZero();
        provider.status(t.providerReference(), TransactionStatus.SUCCESSFUL);
        advance(Duration.ofMinutes(1));
        assertThat(checker.checkNow()).isEqualTo(1);
        advance(Duration.ofMinutes(1));
        assertThat(checker.checkNow()).isZero();

        assertThat(events).singleElement().isInstanceOfSatisfying(PaymentConfirmedEvent.class, e -> {
            assertThat(e.providerReference()).isEqualTo(t.providerReference());
            assertThat(e.origin()).isEqualTo(PaymentOutcomeEvent.Origin.STATUS_CHECK);
        });
        assertThat(store.size()).isZero();
    }

    @Test
    void transientErrorLeavesThePaymentPending() {
        Transaction t = collect("1");
        provider.status(t.providerReference(), new ProviderUnavailableException("down", TestProvider.ID, false, null));
        advance(Duration.ofMinutes(1));

        assertThat(checker.checkNow()).isZero();
        assertThat(events).isEmpty();
        assertThat(store.size()).isEqualTo(1);

        provider.status(t.providerReference(), TransactionStatus.FAILED);
        advance(Duration.ofMinutes(1));
        assertThat(checker.checkNow()).isEqualTo(1);
        assertThat(events).singleElement().isInstanceOf(PaymentFailedEvent.class);
    }

    @Test
    void unexpectedErrorLeavesThePaymentPending() {
        Transaction t = collect("1");
        provider.status(t.providerReference(), new IllegalStateException("bug"));
        advance(Duration.ofMinutes(1));

        assertThat(checker.checkNow()).isZero();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void paymentStillPendingAfterMaxAgeIsDropped() {
        collect("1");
        advance(Duration.ofHours(25));

        assertThat(checker.checkNow()).isZero();
        assertThat(store.size()).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    void failingListenerKeepsThePaymentForTheNextRound() {
        Transaction t = collect("1");
        provider.status(t.providerReference(), TransactionStatus.SUCCESSFUL);
        PendingTransactionChecker failing = new PendingTransactionChecker(mobileMoney, store, e -> {
            throw new IllegalStateException("merchant bug");
        }, Duration.ofMinutes(1), Duration.ofHours(24), 10, clock);
        advance(Duration.ofMinutes(1));

        assertThat(failing.checkNow()).isZero();
        assertThat(store.size()).isEqualTo(1);
        advance(Duration.ofMinutes(1));
        assertThat(checker.checkNow()).isEqualTo(1);
    }

    @Test
    void batchSizeIsRespected() {
        for (int i = 0; i < 25; i++) {
            collect("k" + i);
        }
        advance(Duration.ofMinutes(1));

        // Reads 10 payments, all still pending: the other 15 wait for the next round.
        assertThat(checker.checkNow()).isZero();
        assertThat(store.claimDue(now.get(), 100, now.get().plus(Duration.ofMinutes(1)))).hasSize(15);
    }

    @Test
    void startsAndStops() {
        assertThat(checker.isRunning()).isFalse();
        checker.start();
        assertThat(checker.isRunning()).isTrue();
        checker.stop();
        assertThat(checker.isRunning()).isFalse();
    }
}
