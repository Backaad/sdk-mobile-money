package io.github.backaad.mobilemoney.testing;

import io.github.backaad.mobilemoney.Currencies;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.PayoutRequest;
import io.github.backaad.mobilemoney.ProviderCapabilities;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.WebhookRequest;
import io.github.backaad.mobilemoney.WebhookSignature;
import io.github.backaad.mobilemoney.provider.PaymentProvider;
import io.github.backaad.mobilemoney.provider.StatusReport;
import io.github.backaad.mobilemoney.provider.WebhookNotification;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** A provider whose behaviour each test chooses, and which counts its calls. */
public final class StubProvider implements PaymentProvider {

    private final ProviderId id;
    private final AtomicInteger collects = new AtomicInteger();
    private final AtomicInteger statusReads = new AtomicInteger();
    private volatile Function<PaymentRequest, Transaction> onCollect;
    private volatile ProviderCapabilities capabilities = new ProviderCapabilities(true, true, true,
            WebhookSignature.NONE, Set.of(Currencies.XAF));

    public StubProvider(String id) {
        this.id = new ProviderId(id);
        this.onCollect = request -> accepted(request, "ref-" + collects.get());
    }

    public Transaction accepted(PaymentRequest request, String reference) {
        return new Transaction(id, TransactionType.COLLECTION, reference, request.merchantReference(),
                request.amount(), request.currency(), TransactionStatus.PENDING, "PENDING");
    }

    public StubProvider onCollect(Function<PaymentRequest, Transaction> behaviour) {
        this.onCollect = behaviour;
        return this;
    }

    public StubProvider capabilities(ProviderCapabilities capabilities) {
        this.capabilities = capabilities;
        return this;
    }

    public int collects() {
        return collects.get();
    }

    public int statusReads() {
        return statusReads.get();
    }

    @Override
    public ProviderId id() {
        return id;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public Transaction collect(PaymentRequest request) {
        collects.incrementAndGet();
        return onCollect.apply(request);
    }

    @Override
    public Transaction payout(PayoutRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public StatusReport fetchStatus(TransactionType type, String providerReference) {
        statusReads.incrementAndGet();
        return new StatusReport(providerReference, null, type, TransactionStatus.SUCCESSFUL, "SUCCESSFUL", null, null);
    }

    @Override
    public WebhookNotification parseWebhook(WebhookRequest request) {
        return new WebhookNotification("ref-1", null, null, "SUCCESSFUL");
    }
}
