package io.github.backaad.mobilemoney.demo;

import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.spring.event.PaymentOutcomeEvent;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The demo's payments, by merchant reference. In memory for the demo: a real server keeps its orders in its
 * database and marks them paid when it receives the outcome event.
 */
@Component
public class Payments {

    private static final Logger LOG = LoggerFactory.getLogger(Payments.class);

    private final Map<String, Transaction> byReference = new ConcurrentHashMap<>();

    void save(Transaction transaction) {
        byReference.merge(transaction.merchantReference(), transaction,
                (old, fresh) -> old.status() != TransactionStatus.PENDING ? old : fresh);
    }

    Optional<Transaction> find(String merchantReference) {
        return Optional.ofNullable(byReference.get(merchantReference));
    }

    /**
     * Records a verified outcome. Called at least once per payment: it must be idempotent.
     *
     * @param event the outcome
     */
    @EventListener
    public void onOutcome(PaymentOutcomeEvent event) {
        LOG.info("Payment {} is {} ({}, via {})", event.merchantReference(),
                event.getClass().getSimpleName().replace("Payment", "").replace("Event", ""), event.provider(),
                event.origin());
        if (event.merchantReference() == null) {
            return;
        }
        byReference.computeIfPresent(event.merchantReference(), (reference, current) ->
                current.status() != TransactionStatus.PENDING ? current : current.withStatus(
                        event instanceof io.github.backaad.mobilemoney.spring.event.PaymentConfirmedEvent
                                ? TransactionStatus.SUCCESSFUL : TransactionStatus.FAILED, event.rawStatus()));
    }
}
