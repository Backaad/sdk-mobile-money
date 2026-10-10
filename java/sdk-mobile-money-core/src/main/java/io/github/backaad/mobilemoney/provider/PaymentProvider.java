package io.github.backaad.mobilemoney.provider;

import io.github.backaad.mobilemoney.PaymentRejectedException;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.PayoutRequest;
import io.github.backaad.mobilemoney.ProviderAuthenticationException;
import io.github.backaad.mobilemoney.ProviderCapabilities;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionNotFoundException;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.WebhookRequest;
import io.github.backaad.mobilemoney.WebhookVerificationException;

/**
 * A payment provider. Implementations talk to one provider's API; idempotency, status re-reads and
 * webhook confirmation are handled by {@link io.github.backaad.mobilemoney.MobileMoney}.
 *
 * <p>Exceptions are part of the contract, because they decide whether a request may be sent again:
 * <ul>
 *   <li>{@link PaymentRejectedException}: the provider refused; nothing was debited or paid out.</li>
 *   <li>{@link ProviderAuthenticationException}: credentials refused; the operation was not processed.</li>
 *   <li>{@link ProviderUnavailableException}: transport or server failure. Its
 *       {@code requestMayHaveBeenProcessed} flag must be {@code false} only when the request certainly
 *       did not reach the provider.</li>
 *   <li>Any other exception is treated as "the request may have been processed".</li>
 * </ul>
 */
public interface PaymentProvider {

    /**
     * Returns the provider's identifier.
     *
     * @return the identifier
     */
    ProviderId id();

    /**
     * Returns what this provider supports, as configured.
     *
     * @return the capabilities
     */
    ProviderCapabilities capabilities();

    /**
     * Asks the customer to pay.
     *
     * @param request the request, already validated
     * @return the transaction, usually {@code PENDING} until the customer confirms
     */
    Transaction collect(PaymentRequest request);

    /**
     * Pays money out.
     *
     * @param request the request, already validated
     * @return the transaction
     */
    Transaction payout(PayoutRequest request);

    /**
     * Reads the status of a transaction.
     *
     * @param type collection or payout, or {@code null} when unknown
     * @param providerReference the provider's reference
     * @return the status; an unknown provider status maps to PENDING
     * @throws TransactionNotFoundException if the provider does not know the transaction
     * @throws ProviderUnavailableException on any transient failure, including an empty or unreadable
     *     response
     */
    StatusReport fetchStatus(TransactionType type, String providerReference);

    /**
     * Checks a webhook's authenticity (signature, when the provider signs its webhooks) and extracts the
     * references it claims.
     *
     * @param request the webhook as received
     * @return what the webhook claims
     * @throws WebhookVerificationException if the webhook is not authentic or not well formed
     */
    WebhookNotification parseWebhook(WebhookRequest request);
}
