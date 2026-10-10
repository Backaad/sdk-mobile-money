package io.github.backaad.mobilemoney;

import io.github.backaad.mobilemoney.callback.CallbackTokenStore;
import io.github.backaad.mobilemoney.callback.ExpectedCallback;
import io.github.backaad.mobilemoney.callback.InMemoryCallbackTokenStore;
import io.github.backaad.mobilemoney.idempotency.Fingerprint;
import io.github.backaad.mobilemoney.idempotency.IdempotencyRecord;
import io.github.backaad.mobilemoney.idempotency.IdempotencyStore;
import io.github.backaad.mobilemoney.idempotency.InMemoryIdempotencyStore;
import io.github.backaad.mobilemoney.internal.CallbackTokens;
import io.github.backaad.mobilemoney.internal.Urls;
import io.github.backaad.mobilemoney.provider.PaymentProvider;
import io.github.backaad.mobilemoney.provider.StatusReport;
import io.github.backaad.mobilemoney.provider.WebhookNotification;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
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
    private final List<PaymentProvider> failover;
    private final IdempotencyStore idempotencyStore;
    private final CallbackTokenStore callbackTokenStore;
    private final Duration callbackTokenLifetime;
    private final Clock clock;
    private final Duration lease;
    private final Duration inProgressWait;
    private final URI callbackBaseUrl;
    private final TransactionListener listener;

    private MobileMoney(Builder builder) {
        this.providers = Map.copyOf(builder.providers);
        this.defaultProvider = builder.providers.values().iterator().next();
        this.failover = builder.failover.stream().map(builder.providers::get).toList();
        this.idempotencyStore = builder.idempotencyStore;
        this.callbackTokenStore = builder.callbackTokenStore;
        this.callbackTokenLifetime = builder.callbackTokenLifetime;
        this.clock = builder.clock;
        this.lease = builder.lease;
        this.inProgressWait = builder.inProgressWait;
        this.callbackBaseUrl = builder.callbackBaseUrl;
        this.listener = builder.listener;
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
        List<PaymentProvider> chain = chain(TransactionType.COLLECTION, request.currency());
        Transaction pending = new Transaction(chain.get(0).id(), TransactionType.COLLECTION, null,
                request.merchantReference(), request.amount(), request.currency(), TransactionStatus.PENDING, null);
        return idempotent(request.idempotencyKey(), Fingerprint.of(request), pending, chain,
                provider -> collectWith(provider, request));
    }

    private Transaction collectWith(PaymentProvider provider, PaymentRequest original) {
        PaymentRequest request = withCallback(original, callbackUrlFor(provider, original.callbackUrl()));
        if (provider.capabilities().webhookSignature() != WebhookSignature.CALLBACK_TOKEN) {
            return provider.collect(request);
        }
        if (request.callbackUrl() == null) {
            throw new UnsupportedCapabilityException(
                    provider.id() + " needs a callbackUrl on the request: its callbacks are protected by a"
                            + " single-use URL", provider.id());
        }
        String token = CallbackTokens.newToken();
        String digest = CallbackTokens.digest(token);
        callbackTokenStore.register(digest, new ExpectedCallback(provider.id(), TransactionType.COLLECTION, null,
                request.merchantReference(), request.amount(), request.currency(), request.phoneNumber().e164(),
                clock.instant().plus(callbackTokenLifetime)));
        Transaction result = provider.collect(new PaymentRequest(request.idempotencyKey(), request.amount(),
                request.currency(), request.phoneNumber(), request.merchantReference(), request.description(),
                CallbackTokens.withToken(request.callbackUrl(), token)));
        if (result != null && result.providerReference() != null) {
            callbackTokenStore.attachProviderReference(digest, result.providerReference());
        }
        return result;
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
        List<PaymentProvider> chain = chain(TransactionType.PAYOUT, request.currency());
        Transaction pending = new Transaction(chain.get(0).id(), TransactionType.PAYOUT, null,
                request.merchantReference(), request.amount(), request.currency(), TransactionStatus.PENDING, null);
        return idempotent(request.idempotencyKey(), Fingerprint.of(request), pending, chain,
                provider -> provider.payout(withCallback(request, callbackUrlFor(provider, request.callbackUrl()))));
    }

    /** The request's callback URL, or the provider's own URL under the configured callback base URL. */
    private URI callbackUrlFor(PaymentProvider provider, URI requested) {
        if (requested != null || callbackBaseUrl == null) {
            return requested;
        }
        return URI.create(callbackBaseUrl + "/" + provider.id().value());
    }

    private static PaymentRequest withCallback(PaymentRequest request, URI callbackUrl) {
        if (Objects.equals(callbackUrl, request.callbackUrl())) {
            return request;
        }
        return new PaymentRequest(request.idempotencyKey(), request.amount(), request.currency(),
                request.phoneNumber(), request.merchantReference(), request.description(), callbackUrl);
    }

    private static PayoutRequest withCallback(PayoutRequest request, URI callbackUrl) {
        if (Objects.equals(callbackUrl, request.callbackUrl())) {
            return request;
        }
        return new PayoutRequest(request.idempotencyKey(), request.amount(), request.currency(),
                request.phoneNumber(), request.beneficiaryName(), request.merchantReference(), request.description(),
                callbackUrl);
    }

    /**
     * The default provider, followed by the failover providers configured by the merchant that support the
     * operation and the currency.
     */
    private List<PaymentProvider> chain(TransactionType type, Currency currency) {
        PaymentProvider first = defaultProvider;
        if (!supports(first, type)) {
            throw new UnsupportedCapabilityException(first.id() + (type == TransactionType.COLLECTION
                    ? " cannot collect payments" : " cannot pay out"), first.id());
        }
        requireCurrency(first, currency);
        List<PaymentProvider> chain = new ArrayList<>();
        chain.add(first);
        for (PaymentProvider next : failover) {
            if (supports(next, type) && next.capabilities().currencies().contains(currency)) {
                chain.add(next);
            }
        }
        return chain;
    }

    private static boolean supports(PaymentProvider provider, TransactionType type) {
        return type == TransactionType.COLLECTION ? provider.capabilities().collection()
                : provider.capabilities().payout();
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
     * provider may send the webhook again (whether it does is to be confirmed for each provider), 500 on
     * {@link ConfigurationException} (fix the configuration).
     *
     * @param provider the provider whose webhook URL was called
     * @param request the webhook as received
     * @return the verified event
     * @throws WebhookVerificationException if the webhook is not authentic, malformed, or does not match
     *     the transaction the provider describes
     * @throws ProviderUnavailableException if the status could not be read again; ask for a new delivery
     * @throws ProviderAuthenticationException if the provider refused the credentials
     * @throws ConfigurationException if the provider is not configured to verify webhooks (for example no
     *     Campay webhook key)
     */
    public WebhookEvent handleWebhook(ProviderId provider, WebhookRequest request) {
        return handleWebhook(provider, request, event -> { });
    }

    /**
     * Verifies a webhook like {@link #handleWebhook(ProviderId, WebhookRequest)}, and hands the verified event
     * to {@code onVerified} before a single-use callback token is consumed. If {@code onVerified} throws, the
     * exception propagates and the token stays usable, so that the provider's next delivery is accepted: an
     * outcome is never lost because the code recording it failed.
     *
     * @param provider the provider whose webhook URL was called
     * @param request the webhook as received
     * @param onVerified receives the verified event, for example to record the outcome
     * @return the verified event
     * @throws WebhookVerificationException if the webhook is not authentic, malformed, or does not match
     *     the transaction the provider describes
     * @throws ProviderUnavailableException if the status could not be read again; ask for a new delivery
     * @throws ProviderAuthenticationException if the provider refused the credentials
     * @throws ConfigurationException if the provider is not configured to verify webhooks
     */
    public WebhookEvent handleWebhook(ProviderId provider, WebhookRequest request, Consumer<WebhookEvent> onVerified) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onVerified, "onVerified");
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
        if (p.capabilities().webhookSignature() == WebhookSignature.CALLBACK_TOKEN) {
            return callbackEvent(p, request, notification, onVerified);
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
        WebhookEvent event = new WebhookEvent(provider, report.type(), report.providerReference(), report.merchantReference(),
                report.amount(), report.currency(), report.status(), report.rawStatus(), Verification.STATUS_REREAD,
                notification.claimedRawStatus());
        onVerified.accept(event);
        return event;
    }

    /**
     * Verifies a callback that came to a single-use callback URL, for providers that neither sign their
     * callbacks nor let the status be read. The token must be known, unused and unexpired, created for this
     * provider, and the callback must match the expected reference, amount and number.
     */
    private WebhookEvent callbackEvent(PaymentProvider p, WebhookRequest request, WebhookNotification notification,
            Consumer<WebhookEvent> onVerified) {
        ProviderId provider = p.id();
        List<String> tokens;
        try {
            tokens = request.queryParameters().getOrDefault(CallbackTokens.PARAMETER, List.of());
        } catch (IllegalArgumentException e) {
            throw new WebhookVerificationException("Malformed webhook", provider);
        }
        if (tokens.size() != 1 || tokens.get(0).isBlank()) {
            throw new WebhookVerificationException("Callback without a valid token", provider);
        }
        String digest = CallbackTokens.digest(tokens.get(0));
        ExpectedCallback expected = callbackTokenStore.find(digest).orElseThrow(() ->
                new WebhookVerificationException("Unknown, used or expired callback token", provider));
        if (!expected.provider().equals(provider)) {
            throw new WebhookVerificationException("Callback token created for another provider", provider);
        }
        boolean referenceMatches = expected.providerReference() != null
                ? expected.providerReference().equals(notification.providerReference())
                : expected.merchantReference().equals(notification.merchantReference());
        if (!referenceMatches
                || notification.claimedAmount() == null || notification.claimedAmount() != expected.amount()
                || notification.claimedPhoneNumber() == null
                || !notification.claimedPhoneNumber().equals(expected.phoneNumber())) {
            throw new WebhookVerificationException(
                    "The callback does not match the expected payment (reference, amount, number)", provider);
        }
        TransactionStatus status = p.mapClaimedStatus(notification.claimedRawStatus());
        WebhookEvent event = new WebhookEvent(provider, expected.type(), notification.providerReference(),
                expected.merchantReference(), expected.amount(), expected.currency(), status,
                notification.claimedRawStatus(), Verification.CALLBACK_TOKEN, notification.claimedRawStatus());
        // Recorded first: if that fails, the token stays usable for the provider's next delivery.
        onVerified.accept(event);
        if (status != TransactionStatus.PENDING && callbackTokenStore.consume(digest).isEmpty()) {
            // Another delivery used the token in the meantime; its outcome was recorded too.
            throw new WebhookVerificationException("Unknown, used or expired callback token", provider);
        }
        return event;
    }

    /**
     * Returns the identifiers of the configured providers.
     *
     * @return the identifiers
     */
    public Set<ProviderId> providerIds() {
        return providers.keySet();
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

    private Transaction idempotent(String key, String fingerprint, Transaction pending, List<PaymentProvider> chain,
            Function<PaymentProvider, Transaction> call) {
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

        for (int i = 0; i < chain.size(); i++) {
            PaymentProvider provider = chain.get(i);
            Transaction attempt = pending.withProvider(provider.id());
            Transaction result;
            try {
                result = call.apply(provider);
            } catch (PaymentRejectedException e) {
                finish(key, owner, IdempotencyRecord.State.REJECTED, null, new IdempotencyRecord.Rejection(
                        e.provider().orElse(provider.id()), e.providerCode().orElse(null),
                        Objects.requireNonNullElse(e.getMessage(), "Refused by " + provider.id())));
                throw e;
            } catch (ProviderUnavailableException e) {
                if (e.requestMayHaveBeenProcessed()) {
                    return unknownOutcome(key, owner, attempt.withProviderReference(e.providerReference().orElse(null)), e);
                }
                if (i + 1 < chain.size()) {
                    // Configured by the merchant, and safe: nothing reached this provider.
                    LOG.warn("{} unavailable and nothing was sent: failing over to {}, as configured",
                            provider.id(), chain.get(i + 1).id());
                    continue;
                }
                release(key, owner);
                throw e;
            } catch (ProviderAuthenticationException | UnsupportedCapabilityException | InvalidPhoneNumberException e) {
                // Raised before anything was processed: the key can be used again.
                release(key, owner);
                throw e;
            } catch (RuntimeException e) {
                return unknownOutcome(key, owner, attempt, e);
            } catch (Error e) {
                finish(key, owner, IdempotencyRecord.State.OUTCOME_UNKNOWN, attempt, null);
                throw e;
            }

            if (result == null || result.outcomeUnknown()
                    || !result.provider().equals(provider.id())
                    || result.type() != pending.type()
                    || !result.merchantReference().equals(pending.merchantReference())) {
                return unknownOutcome(key, owner, attempt, new IllegalStateException("Inconsistent provider result"));
            }
            finish(key, owner, IdempotencyRecord.State.COMPLETED, result, null);
            notifyListener(result);
            return result;
        }
        throw new IllegalStateException("No provider to try");
    }

    private Transaction unknownOutcome(String key, String owner, Transaction pending, RuntimeException cause) {
        LOG.warn("{} {} outcome unknown ({}): it will not be sent again; {}", pending.provider(), pending.type(),
                cause.getClass().getSimpleName(), pending.providerReference() == null
                        ? "wait for the provider's webhook" : "read its status later");
        finish(key, owner, IdempotencyRecord.State.OUTCOME_UNKNOWN, pending, null);
        notifyListener(pending);
        return pending;
    }

    private void notifyListener(Transaction transaction) {
        if (listener == null) {
            return;
        }
        try {
            listener.onTransaction(transaction);
        } catch (RuntimeException e) {
            LOG.error("Transaction listener failed ({}); the payment itself is not affected",
                    e.getClass().getSimpleName());
        }
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
        private final List<ProviderId> failover = new ArrayList<>();
        private IdempotencyStore idempotencyStore;
        private CallbackTokenStore callbackTokenStore;
        private Duration callbackTokenLifetime = Duration.ofHours(24);
        private Clock clock = Clock.systemUTC();
        private Duration lease = Duration.ofMinutes(3);
        private Duration inProgressWait = Duration.ofSeconds(35);
        private URI callbackBaseUrl;
        private TransactionListener listener;

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
         * Sets the public base URL of the merchant's webhook endpoint. A request without its own
         * {@code callbackUrl} then gets {@code <base>/<provider id>}, for example
         * {@code https://shop.example/mobile-money/webhooks/mtn-momo}, for the provider that actually
         * processes it (failover included). Must be HTTPS, except for localhost.
         *
         * @param callbackBaseUrl the base URL, without trailing slash, query or fragment, or {@code null}
         * @return this builder
         */
        public Builder callbackBaseUrl(URI callbackBaseUrl) {
            this.callbackBaseUrl = callbackBaseUrl;
            return this;
        }

        /**
         * Sets a listener told about each new transaction, for example to follow pending payments.
         *
         * @param listener the listener, or {@code null}
         * @return this builder
         */
        public Builder transactionListener(TransactionListener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * Allows failover: when the default provider cannot be reached and nothing was sent to it, the
         * request goes to these providers, in this order. Without this call, there is never any failover.
         * A request that may have reached a provider is never sent to another one.
         *
         * @param providerIds configured providers, other than the default one
         * @return this builder
         */
        public Builder failover(ProviderId... providerIds) {
            failover.clear();
            for (ProviderId id : providerIds) {
                failover.add(Objects.requireNonNull(id, "providerId"));
            }
            return this;
        }

        /**
         * Sets the store of single-use callback tokens (Tara). Defaults to an in-memory store, which only
         * suits a single application instance.
         *
         * @param store the store
         * @return this builder
         */
        public Builder callbackTokenStore(CallbackTokenStore store) {
            this.callbackTokenStore = Objects.requireNonNull(store, "store");
            return this;
        }

        /**
         * Sets how long a single-use callback URL stays valid. Defaults to 24 hours.
         *
         * @param lifetime the duration
         * @return this builder
         */
        public Builder callbackTokenLifetime(Duration lifetime) {
            this.callbackTokenLifetime = Objects.requireNonNull(lifetime, "lifetime");
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
            for (ProviderId id : failover) {
                if (!providers.containsKey(id)) {
                    throw new ConfigurationException("Failover provider " + id + " is not configured", id);
                }
                if (id.equals(providers.keySet().iterator().next())) {
                    throw new ConfigurationException("The default provider cannot be its own failover", id);
                }
            }
            if (failover.size() != failover.stream().distinct().count()) {
                throw new ConfigurationException("A failover provider is listed twice", null);
            }
            if (callbackBaseUrl != null) {
                try {
                    Urls.requireSafeCallbackUrl(callbackBaseUrl);
                } catch (IllegalArgumentException e) {
                    throw new ConfigurationException("callbackBaseUrl " + e.getMessage(), null);
                }
                if (callbackBaseUrl.getRawQuery() != null || callbackBaseUrl.getRawFragment() != null
                        || callbackBaseUrl.toString().endsWith("/")) {
                    throw new ConfigurationException(
                            "callbackBaseUrl must not end with '/' or contain a query or a fragment", null);
                }
            }
            if (callbackTokenLifetime.isNegative() || callbackTokenLifetime.isZero()) {
                throw new ConfigurationException("callbackTokenLifetime must be positive", null);
            }
            if (callbackTokenStore == null) {
                callbackTokenStore = new InMemoryCallbackTokenStore(clock);
            }
            if (idempotencyStore == null) {
                idempotencyStore = new InMemoryIdempotencyStore(clock, InMemoryIdempotencyStore.DEFAULT_MAX_ENTRIES);
            }
            return new MobileMoney(this);
        }
    }
}
