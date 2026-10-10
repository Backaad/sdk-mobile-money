package io.github.backaad.mobilemoney.provider.simulation;

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
import io.github.backaad.mobilemoney.WebhookVerificationException;
import io.github.backaad.mobilemoney.provider.PaymentProvider;
import io.github.backaad.mobilemoney.provider.StatusReport;
import io.github.backaad.mobilemoney.provider.WebhookNotification;
import java.util.Set;
import java.util.UUID;

/**
 * Simulation mode, to develop without a provider account.
 *
 * <p>It talks to nobody and never confirms a payment: every transaction stays {@code PENDING} forever and
 * every webhook is rejected. To test a complete payment, use a provider's sandbox.
 */
public final class SimulationProvider implements PaymentProvider {

    /** Raw status reported for every simulated transaction. */
    public static final String RAW_STATUS = "SIMULATED_PENDING";

    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(true, true, true,
            WebhookSignature.NONE, Set.of(Currencies.XAF, Currencies.XOF));

    @Override
    public ProviderId id() {
        return ProviderId.SIMULATION;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Transaction collect(PaymentRequest request) {
        return new Transaction(ProviderId.SIMULATION, TransactionType.COLLECTION, newReference(),
                request.merchantReference(), request.amount(), request.currency(), TransactionStatus.PENDING,
                RAW_STATUS);
    }

    @Override
    public Transaction payout(PayoutRequest request) {
        return new Transaction(ProviderId.SIMULATION, TransactionType.PAYOUT, newReference(),
                request.merchantReference(), request.amount(), request.currency(), TransactionStatus.PENDING,
                RAW_STATUS);
    }

    @Override
    public StatusReport fetchStatus(TransactionType type, String providerReference) {
        return new StatusReport(providerReference, null, type, TransactionStatus.PENDING, RAW_STATUS, null, null);
    }

    @Override
    public WebhookNotification parseWebhook(WebhookRequest request) {
        throw new WebhookVerificationException(
                "The simulation never confirms a payment: use a provider's sandbox", ProviderId.SIMULATION);
    }

    @Override
    public String toString() {
        return "SimulationProvider";
    }

    private static String newReference() {
        return "sim-" + UUID.randomUUID();
    }
}
