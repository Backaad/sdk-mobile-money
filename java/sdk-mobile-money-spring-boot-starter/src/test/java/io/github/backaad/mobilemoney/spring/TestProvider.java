package io.github.backaad.mobilemoney.spring;

import io.github.backaad.mobilemoney.Currencies;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.PayoutRequest;
import io.github.backaad.mobilemoney.ProviderCapabilities;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.WebhookRequest;
import io.github.backaad.mobilemoney.WebhookSignature;
import io.github.backaad.mobilemoney.WebhookVerificationException;
import io.github.backaad.mobilemoney.provider.PaymentProvider;
import io.github.backaad.mobilemoney.provider.StatusReport;
import io.github.backaad.mobilemoney.provider.WebhookNotification;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A provider for the starter tests: it accepts every request, and each test sets the status it reports.
 * A webhook is "authentic" when it carries the header {@code X-Test-Signature: ok}.
 */
public final class TestProvider implements PaymentProvider {

    public static final ProviderId ID = new ProviderId("test-pay");

    private final AtomicInteger collects = new AtomicInteger();
    private final Map<String, Object> statuses = new ConcurrentHashMap<>();

    public int collects() {
        return collects.get();
    }

    /** Sets what a status read of this reference returns: a status, or an exception to throw. */
    public void status(String reference, Object statusOrException) {
        statuses.put(reference, statusOrException);
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, true, true, WebhookSignature.HMAC_SHA256, Set.of(Currencies.XAF));
    }

    @Override
    public Transaction collect(PaymentRequest request) {
        int n = collects.incrementAndGet();
        return new Transaction(ID, TransactionType.COLLECTION, "ref-" + n, request.merchantReference(),
                request.amount(), request.currency(), TransactionStatus.PENDING, "pending");
    }

    @Override
    public Transaction payout(PayoutRequest request) {
        throw new ProviderUnavailableException("no payouts in tests", ID, false, null);
    }

    @Override
    public StatusReport fetchStatus(TransactionType type, String providerReference) {
        Object value = statuses.getOrDefault(providerReference, TransactionStatus.PENDING);
        if (value instanceof RuntimeException e) {
            throw e;
        }
        TransactionStatus status = (TransactionStatus) value;
        return new StatusReport(providerReference, "order-1", TransactionType.COLLECTION, status,
                status.name().toLowerCase(), 100L, Currencies.XAF);
    }

    @Override
    public WebhookNotification parseWebhook(WebhookRequest request) {
        if (!"ok".equals(request.header("X-Test-Signature").orElse(null))) {
            throw new WebhookVerificationException("bad signature", ID);
        }
        String reference = request.queryParameters().getOrDefault("ref", java.util.List.of("ref-1")).get(0);
        return new WebhookNotification(reference, null, TransactionType.COLLECTION, "SUCCESSFUL");
    }
}
