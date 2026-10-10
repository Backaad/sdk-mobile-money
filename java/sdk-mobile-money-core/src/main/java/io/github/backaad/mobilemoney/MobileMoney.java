package io.github.backaad.mobilemoney;

import io.github.backaad.mobilemoney.idempotency.Fingerprint;
import io.github.backaad.mobilemoney.idempotency.IdempotencyRecord;
import io.github.backaad.mobilemoney.idempotency.IdempotencyStore;
import io.github.backaad.mobilemoney.idempotency.InMemoryIdempotencyStore;
import io.github.backaad.mobilemoney.provider.PaymentProvider;
import io.github.backaad.mobilemoney.provider.StatusReport;
import io.github.backaad.mobilemoney.provider.WebhookNotification;
import java.time.Clock;
import java.time.Duration;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the SDK: collects, pays out, reads statuses and verifies webhooks, whatever the provider.
 *
 * <pre>{@code
 * MobileMoney mobileMoney = MobileMoney.builder()
 *         .provider(CampayProvider.builder()
 *                 .environment(CampayEnvironment.SANDBOX)
 *                 .username(System.getenv("CAMPAY_USERNAME"))
 *                 .password(System.getenv("CAMPAY_PASSWORD"))
 *                 .webhookKey(System.getenv("CAMPAY_WEBHOOK_KEY"))
 *                 .build())
 *         .build();
 *
 * Transaction transaction = mobileMoney.collect(PaymentRequest.builder()
 *         .idempotencyKey("order-1234")
 *         .amount(500)
 *         .phoneNumber("+237 6 70 12 34 56")
 *         .merchantReference("order-1234")
 *         .description("Order 1234")
 *         .build());
 * }</pre>
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Every collection and payout goes through its idempotency key: the same request sent again within
 *       24 hours returns the first result and never moves money twice.</li>
 *   <li>When a request may have reached the provider but its answer was lost, the result is a
 *       {@code PENDING} transaction with {@link Transaction#outcomeUnknown()} set. It is never sent
 *       again.</li>
 *   <li>A transient error while reading a status never changes the status.</li>
 *   <li>A webhook is only trusted after its authenticity check and a new read of the status from the
 *       provider that sent it.</li>
 * </ul>
 *
 * <p>Thread-safe.
 */
public final class MobileMoney {

    /** How long an idempotency key stays valid. */
    public static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private static final Logger LOG = LoggerFactory.getLogger(MobileMoney.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final Map<ProviderId, PaymentProvider> providers;
    private final PaymentProvider defaultProvider;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;
    private final Duration lease;
    private final Duration inProgressWait;

    private MobileMoney(Builder builder) {
        this.providers = Map.copyOf(builder.providers);
        this.defaultProvider = builder.providers.values().iterator().next();
        this.idempotencyStore = builder.idempotencyStore;
        this.clock = builder.clock;
        this.lease = builder.lease;
        this.inProgressWait = builder.inProgressWait;
    }

    /**
     * Starts the configuration.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Asks a customer to pay. The customer usually confirms on their phone, so the result is normally
     * {@code PENDING}: follow it with {@link #status(Transaction)} or the provider's webhook.
     *
     * @param request the request
     * @return the transaction
     * @throws PaymentRejectedException if the provider refused (nothing was debited)
     * @throws ProviderUnavailableException if the provider could not be reached; nothing was sent, retry
     *     with the same idempotency key
     * @throws ProviderAuthenticationException if the provider refused the credentials
     * @throws UnsupportedCapabilityException if the provider cannot collect in this currency
     * @throws IdempotencyKeyConflictException if the key was used for a different request
     * @throws RequestInProgressException if the same request is still being processed
     * @throws IdempotencyStoreException if the idempotency store failed; nothing was sent
     */
    public Transaction collect(PaymentRequest request) {
        Objects.requireNonNull(request, "request");
        PaymentProvider provider = defaultProvider;
        ProviderCapabilities capabilities = provider.capabilities();
        if (!capabilities.collection()) {
            throw new UnsupportedCapabilityException(provider.id() + " cannot collect payments", provider.id());
        }
        requireCurrency(provider, request.currency());
        Transaction pending = new Transaction(provider.id(), TransactionType.COLLECTION, null,
                request.merchantReference(), request.amount(), request.currency(), TransactionStatus.PENDING, null);
        return idempotent(request.idempotencyKey(), Fingerprint.of(request), pending, () -> provider.collect(request));
    }

    /**
     * Pays money out to a beneficiary.
     *
     * @param request the request
     * @return the transaction
     * @throws PaymentRejectedException if the provider refused (nothing was paid out)
     * @throws ProviderUnavailableException if the provider could not be reached; nothing was sent, retry
     *     with the same idempotency key
     * @throws ProviderAuthenticationException if the provider refused the credentials
     * @throws UnsupportedCapabilityException if the provider cannot pay out in this currency
     * @throws IdempotencyKeyConflictException if the key was used for a different request
     * @throws RequestInProgressException if the same request is still being processed
     * @throws IdempotencyStoreException if the idempotency store failed; nothing was sent
     */
    public Transaction payout(PayoutRequest request) {
        Objects.requireNonNull(request, "request");
        PaymentProvider provider = defaultProvider;
        ProviderCapabilities capabilities = provider.capabilities();
        if (!capabilities.payout()) {
            throw new UnsupportedCapabilityException(provider.id() + " cannot pay out", provider.id());
        }
        requireCurrency(provider, request.currency());
        Transaction pending = new Transaction(provider.id(), TransactionType.PAYOUT, null,
                request.merchantReference(), request.amount(), request.currency(), TransactionStatus.PENDING, null);
        return idempotent(request.idempotencyKey(), Fingerprint.of(request), pending, () -> provider.payout(request));
    }

    /**
     * Reads the current status of a transaction from the provider that processed it.
     *
     * <p>A transient error (network, HTTP 5xx, timeout, empty or unreadable response) returns the
     * transaction unchanged: it never turns into {@code FAILED}. A transaction whose outcome is unknown
     * (no provider reference) is also returned unchanged: wait for the provider's webhook.
     *
     * @param transaction a transaction returned by this SDK
     * @return the transaction with its current status
     * @throws TransactionNotFoundException if the provider does not know the transaction; this is not a
     *     final answer, keep the payment pending
     * @throws ProviderAuthenticationException if the provider refused the credentials
     * @throws UnsupportedCapabilityException if the provider cannot be queried
     */
    public Transaction status(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        PaymentProvider provider = provider(transaction.provider());
        if (!provider.capabilities().statusQuery()) {
            throw new UnsupportedCapabilityException(
                    provider.id() + " does not let the status be read: use its webhooks", provider.id());
        }
        if (transaction.outcomeUnknown()) {
            return transaction;
        }
        StatusReport report;
        try {
            report = provider.fetchStatus(transaction.type(), transaction.providerReference());
        } catch (TransactionNotFoundException | ProviderAuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Status of a {} transaction could not be read ({}): left unchanged",
                    provider.id(), e.getClass().getSimpleName());
            return transaction;
        }
        if (!transaction.providerReference().equals(report.providerReference())
                || (report.merchantReference() != null
                        && !report.merchantReference().equals(transaction.merchantReference()))) {
            LOG.warn("{} returned the status of another transaction: left unchanged", provider.id());
            return transaction;
        }
        return transaction.withStatus(report.status(), report.rawStatus());
    }

    /**
     * Verifies a webhook received from a provider and returns the verified status.
     *
     * <p>Expose one webhook URL per provider, and pass the provider of that URL: it is never guessed from
     * the content. The webhook must pass the provider's authenticity check, then its status is read again
     * from that same provider: the returned event only contains what the provider's API said. A provider's
     * webhook can therefore only confirm that provider's transactions.
     *
     * <p>Suggested HTTP answers: 200 when an event is returned, 400 on
     * {@link WebhookVerificationException}, 503 on {@link ProviderUnavailableException} so that the
     * provider can send the webhook again.
     *
     * @param provider the provider whose webhook URL was called
     * @param request the webhook as received
     * @return the verified event
     * @throws WebhookVerificationException if the webhook is not authentic, malformed, or does not match
     *     the transaction the provider describes
     * @throws ProviderUnavailableException if the status could not be read again; ask for a new delivery
     * @throws ProviderAuthenticationException if the provider refused the credentials
     */
    public WebhookEvent handleWebhook(ProviderId provider, WebhookRequest request) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(request, "request");
        PaymentProvider p = providers.get(provider);
        if (p == null) {
            throw new WebhookVerificationException("No provider configured with id " + provider, provider);
        }

        WebhookNotification notification;
        try {
            notification = p.parseWebhook(request);
        } catch (WebhookVerificationException | ConfigurationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WebhookVerificationException("Malformed webhook", provider);
        }
        if (notification == null) {
            throw new WebhookVerificationException("Malformed webhook", provider);
        }
        if (!p.capabilities().statusQuery()) {
            throw new WebhookVerificationException(
                    provider + " webhooks cannot be verified: its status cannot be read", provider);
        }

        StatusReport report;
        try {
            // The claimed type only selects the endpoint to read; it never ends up in the event.
            report = p.fetchStatus(notification.type(), notification.providerReference());
        } catch (TransactionNotFoundException e) {
            throw new WebhookVerificationException("The webhook refers to a transaction unknown to " + provider,
                    provider);
        } catch (ProviderUnavailableException | ProviderAuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProviderUnavailableException(
                    "Could not read the status of the transaction from " + provider, provider, false, null);
        }

        if (!notification.providerReference().equals(report.providerReference())) {
            throw new WebhookVerificationException("The provider returned another transaction", provider);
        }
        if (notification.merchantReference() != null
                && !notification.merchantReference().equals(report.merchantReference())) {
            throw new WebhookVerificationException(
                    "The merchant reference in the webhook does not match the provider's", provider);
        }
        if (notification.type() != null && report.type() != null && notification.type() != report.type()) {
            throw new WebhookVerificationException(
                    "The transaction type in the webhook does not match the provider's", provider);
        }
        // Only what the provider's API said: a type claimed by the webhook alone is not trusted.
        return new WebhookEvent(provider, report.type(), report.providerReference(), report.merchantReference(),
                report.amount(), report.currency(), report.status(), report.rawStatus(), Verification.STATUS_REREAD,
                notification.claimedRawStatus());
    }

    /**
     * Returns a configured provider.
     *
     * @param id the provider's identifier
     * @return the provider
     * @throws UnsupportedCapabilityException if no provider is configured with this identifier
     */
    public PaymentProvider provider(ProviderId id) {
        PaymentProvider provider = providers.get(Objects.requireNonNull(id, "id"));
        if (provider == null) {
            throw new UnsupportedCapabilityException("No provider configured with id " + id, id);
        }
        return provider;
    }

    private static void requireCurrency(PaymentProvider provider, Currency currency) {
        if (!provider.capabilities().currencies().contains(currency)) {
            throw new UnsupportedCapabilityException(
                    provider.id() + " does not accept " + currency.getCurrencyCode(), provider.id());
        }
    }

    private Transaction idempotent(String key, String fingerprint, Transaction pending, Supplier<Transaction> call) {
        IdempotencyStore.Reservation reservation;
        try {
            reservation = idempotencyStore.reserve(key, fingerprint, pending, lease, IDEMPOTENCY_TTL);
        } catch (MobileMoneyException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IdempotencyStoreException(
                    "Idempotency store unavailable: nothing was sent, retry with the same key", e);
        }
        if (reservation instanceof IdempotencyStore.Existing existing) {
            return replay(existing.record(), fingerprint);
        }
        String owner = ((IdempotencyStore.Acquired) reservation).ownerToken();

        Transaction result;
        try {
            result = call.get();
        } catch (PaymentRejectedException e) {
            finish(key, owner, IdempotencyRecord.State.REJECTED, null, new IdempotencyRecord.Rejection(
                    e.provider().orElse(pending.provider()), e.providerCode().orElse(null),
                    Objects.requireNonNullElse(e.getMessage(), "Refused by " + pending.provider())));
            throw e;
        } catch (ProviderUnavailableException e) {
            if (!e.requestMayHaveBeenProcessed()) {
                release(key, owner);
                throw e;
            }
            return unknownOutcome(key, owner, pending, e);
        } catch (ProviderAuthenticationException | UnsupportedCapabilityException | InvalidPhoneNumberException e) {
            // Raised before anything was processed: the key can be used again.
            release(key, owner);
            throw e;
        } catch (RuntimeException e) {
            return unknownOutcome(key, owner, pending, e);
        } catch (Error e) {
            finish(key, owner, IdempotencyRecord.State.OUTCOME_UNKNOWN, pending, null);
            throw e;
        }

        if (result == null || result.outcomeUnknown()
                || !result.provider().equals(pending.provider())
                || result.type() != pending.type()
                || !result.merchantReference().equals(pending.merchantReference())) {
            return unknownOutcome(key, owner, pending, new IllegalStateException("Inconsistent provider result"));
        }
        finish(key, owner, IdempotencyRecord.State.COMPLETED, result, null);
        return result;
    }

    private Transaction unknownOutcome(String key, String owner, Transaction pending, RuntimeException cause) {
        LOG.warn("{} {} outcome unknown ({}): it will not be sent again; wait for the provider's webhook",
                pending.provider(), pending.type(), cause.getClass().getSimpleName());
        finish(key, owner, IdempotencyRecord.State.OUTCOME_UNKNOWN, pending, null);
        return pending;
    }

    private Transaction replay(IdempotencyRecord record, String fingerprint) {
        // Real elapsed time, not the injectable clock: the wait itself happens in real time.
        long deadline = System.nanoTime() + inProgressWait.toNanos();
        IdempotencyRecord current = record;
        while (true) {
            if (!current.fingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyConflictException(
                        "This idempotency key was already used for a different request");
            }
            switch (current.state()) {
                case COMPLETED, OUTCOME_UNKNOWN:
                    return current.transaction();
                case REJECTED:
                    IdempotencyRecord.Rejection rejection = current.rejection();
                    throw new PaymentRejectedException(rejection.message(), rejection.provider(), rejection.code());
                case IN_PROGRESS:
                default:
                    break;
            }
            if (!clock.instant().isBefore(current.leaseUntil())) {
                // The process that sent the request did not finish it: the outcome is unknown.
                return current.transaction();
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new RequestInProgressException(
                        "The same request is still being processed: retry later with the same key");
            }
            sleep();
            Optional<IdempotencyRecord> next;
            try {
                next = idempotencyStore.find(current.key());
            } catch (RuntimeException e) {
                throw new RequestInProgressException(
                        "The same request is still being processed: retry later with the same key");
            }
            if (next.isEmpty()) {
                throw new RequestInProgressException(
                        "The same request was not completed: retry with the same key");
            }
            current = next.get();
        }
    }

    private void finish(String key, String owner, IdempotencyRecord.State state, Transaction transaction,
            IdempotencyRecord.Rejection rejection) {
        try {
            if (!idempotencyStore.finish(key, owner, state, transaction, rejection)) {
                LOG.error("Idempotency record could not be finished as {}: replays will be refused", state);
            }
        } catch (RuntimeException e) {
            LOG.error("Idempotency store failed to record {} ({}): replays will be refused", state,
                    e.getClass().getSimpleName());
        }
    }

    private void release(String key, String owner) {
        try {
            idempotencyStore.release(key, owner);
        } catch (RuntimeException e) {
            LOG.error("Idempotency store failed to release a key ({}): it stays blocked until it expires",
                    e.getClass().getSimpleName());
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RequestInProgressException("Interrupted while waiting for the same request");
        }
    }

    /** Configures {@link MobileMoney}. */
    public static final class Builder {

        private final Map<ProviderId, PaymentProvider> providers = new LinkedHashMap<>();
        private IdempotencyStore idempotencyStore;
        private Clock clock = Clock.systemUTC();
        private Duration lease = Duration.ofMinutes(3);
        private Duration inProgressWait = Duration.ofSeconds(35);

        private Builder() {
        }

        /**
         * Adds a provider. The first provider added handles collections and payouts.
         *
         * @param provider the provider
         * @return this builder
         */
        public Builder provider(PaymentProvider provider) {
            Objects.requireNonNull(provider, "provider");
            if (providers.putIfAbsent(provider.id(), provider) != null) {
                throw new ConfigurationException("Provider " + provider.id() + " is configured twice", provider.id());
            }
            return this;
        }

        /**
         * Sets the idempotency store. Defaults to an {@link InMemoryIdempotencyStore}, which only suits a
         * single application instance.
         *
         * @param idempotencyStore the store
         * @return this builder
         */
        public Builder idempotencyStore(IdempotencyStore idempotencyStore) {
            this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
            return this;
        }

        /**
         * Sets the clock, for tests.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Sets how long a request may stay in progress before it is presumed lost. It must be longer than
         * the longest provider call. Defaults to 3 minutes.
         *
         * @param lease the duration
         * @return this builder
         */
        public Builder lease(Duration lease) {
            this.lease = Objects.requireNonNull(lease, "lease");
            return this;
        }

        /**
         * Sets how long a repeated request waits for the first one to finish before giving up with
         * {@link RequestInProgressException}. Defaults to 35 seconds.
         *
         * @param inProgressWait the duration
         * @return this builder
         */
        public Builder inProgressWait(Duration inProgressWait) {
            this.inProgressWait = Objects.requireNonNull(inProgressWait, "inProgressWait");
            return this;
        }

        /**
         * Builds the client.
         *
         * @return the client
         * @throws ConfigurationException if no provider is configured, or if the simulation is mixed with
         *     real providers
         */
        public MobileMoney build() {
            if (providers.isEmpty()) {
                throw new ConfigurationException("At least one provider must be configured", null);
            }
            if (providers.containsKey(ProviderId.SIMULATION) && providers.size() > 1) {
                throw new ConfigurationException(
                        "The simulation cannot be combined with real providers", ProviderId.SIMULATION);
            }
            if (lease.isNegative() || lease.isZero() || inProgressWait.isNegative()) {
                throw new ConfigurationException("lease must be positive and inProgressWait not negative", null);
            }
            if (idempotencyStore == null) {
                idempotencyStore = new InMemoryIdempotencyStore(clock, InMemoryIdempotencyStore.DEFAULT_MAX_ENTRIES);
            }
            return new MobileMoney(this);
        }
    }
}
