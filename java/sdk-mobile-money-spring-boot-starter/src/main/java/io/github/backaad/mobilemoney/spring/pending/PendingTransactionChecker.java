package io.github.backaad.mobilemoney.spring.pending;

import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.MobileMoneyException;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.spring.event.PaymentOutcomeEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;

/**
 * Reads the status of pending transactions again, at a fixed interval, until they succeed or fail, and
 * publishes a {@link PaymentOutcomeEvent} when they do. Off by default
 * ({@code mobile-money.pending-check.enabled}).
 *
 * <p>A transient error (network, 5xx, timeout, empty answer) leaves the transaction pending: it is read
 * again at the next round. After {@code max-age}, a transaction still pending is no longer read, and is
 * logged for manual reconciliation.
 */
public final class PendingTransactionChecker implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(PendingTransactionChecker.class);

    private final MobileMoney mobileMoney;
    private final PendingTransactionStore store;
    private final ApplicationEventPublisher events;
    private final Duration interval;
    private final Duration maxAge;
    private final int batchSize;
    private final Clock clock;
    private volatile ScheduledExecutorService executor;

    /**
     * Creates the task.
     *
     * @param mobileMoney the facade, to read statuses
     * @param store the followed transactions
     * @param events publishes the outcome events
     * @param interval time between two reads of the same transaction
     * @param maxAge how long a transaction is followed at most
     * @param batchSize transactions read at most per round
     * @param clock the clock
     */
    public PendingTransactionChecker(MobileMoney mobileMoney, PendingTransactionStore store,
            ApplicationEventPublisher events, Duration interval, Duration maxAge, int batchSize, Clock clock) {
        this.mobileMoney = Objects.requireNonNull(mobileMoney, "mobileMoney");
        this.store = Objects.requireNonNull(store, "store");
        this.events = Objects.requireNonNull(events, "events");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
        this.batchSize = batchSize;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs one round now.
     *
     * @return the number of transactions that reached their outcome
     */
    public int checkNow() {
        Instant now = clock.instant();
        List<PendingTransactionStore.Entry> due = store.claimDue(now, batchSize, now.plus(interval));
        int outcomes = 0;
        for (PendingTransactionStore.Entry entry : due) {
            Transaction pending = entry.transaction();
            if (!now.isBefore(entry.addedAt().plus(maxAge))) {
                store.remove(pending.provider(), pending.providerReference());
                LOG.warn("{} {} {} still pending after {}: no longer checked, reconcile it manually",
                        pending.provider(), pending.type(), pending.merchantReference(), maxAge);
                continue;
            }
            Transaction current;
            try {
                current = mobileMoney.status(pending);
            } catch (MobileMoneyException e) {
                // Transient or not found yet: still pending, read again at the next round (rule 4).
                LOG.debug("{} status read failed ({}): still pending", pending.provider(),
                        e.getClass().getSimpleName());
                continue;
            } catch (RuntimeException e) {
                LOG.warn("{} status read failed ({}): still pending", pending.provider(),
                        e.getClass().getSimpleName());
                continue;
            }
            if (current.status() == TransactionStatus.PENDING) {
                continue;
            }
            try {
                events.publishEvent(PaymentOutcomeEvent.of(current));
            } catch (RuntimeException e) {
                LOG.error("A listener of the {} outcome of {} failed ({}): it will be published again",
                        current.status(), current.merchantReference(), e.getClass().getSimpleName());
                continue;
            }
            store.remove(current.provider(), current.providerReference());
            outcomes++;
        }
        return outcomes;
    }

    @Override
    public void start() {
        ScheduledExecutorService started = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mobile-money-pending-check");
            thread.setDaemon(true);
            return thread;
        });
        started.scheduleWithFixedDelay(this::checkSafely, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
        executor = started;
        LOG.info("Pending payments are read again every {}, for {} at most", interval, maxAge);
    }

    @Override
    public void stop() {
        ScheduledExecutorService running = executor;
        executor = null;
        if (running != null) {
            running.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    private void checkSafely() {
        try {
            checkNow();
        } catch (RuntimeException e) {
            // The store may be down: try again at the next round.
            LOG.warn("Pending payment check failed ({}): retried at the next round", e.getClass().getSimpleName());
        }
    }
}
