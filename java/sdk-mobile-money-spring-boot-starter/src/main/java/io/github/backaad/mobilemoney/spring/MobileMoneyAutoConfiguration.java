package io.github.backaad.mobilemoney.spring;

import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.TransactionListener;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.callback.CallbackTokenStore;
import io.github.backaad.mobilemoney.callback.InMemoryCallbackTokenStore;
import io.github.backaad.mobilemoney.idempotency.IdempotencyStore;
import io.github.backaad.mobilemoney.idempotency.InMemoryIdempotencyStore;
import io.github.backaad.mobilemoney.provider.PaymentProvider;
import io.github.backaad.mobilemoney.spring.pending.InMemoryPendingTransactionStore;
import io.github.backaad.mobilemoney.spring.pending.PendingTransactionChecker;
import io.github.backaad.mobilemoney.spring.pending.PendingTransactionStore;
import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Configures {@link MobileMoney} from the {@code mobile-money.*} properties, with the providers they enable
 * and any {@link PaymentProvider} bean of the application, the stores (in memory unless Redis is configured),
 * the webhook handler and the optional pending-payment task.
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "mobile-money.enabled", matchIfMissing = true)
@EnableConfigurationProperties(MobileMoneyProperties.class)
public class MobileMoneyAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MobileMoneyAutoConfiguration.class);
    private static final Clock CLOCK = Clock.systemUTC();

    /**
     * The in-memory idempotency store, when no other store is configured.
     *
     * @param properties the configuration
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore mobileMoneyIdempotencyStore(MobileMoneyProperties properties) {
        requireNotRedis(properties);
        if (!properties.simulation().enabled()) {
            LOG.warn("Mobile Money idempotency records are kept in memory: they are lost on restart and not shared"
                    + " between instances. Configure Redis (spring-boot-starter-data-redis) for production");
        }
        return new InMemoryIdempotencyStore(CLOCK, InMemoryIdempotencyStore.DEFAULT_MAX_ENTRIES);
    }

    /**
     * The in-memory callback token store, when no other store is configured.
     *
     * @param properties the configuration
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean
    public CallbackTokenStore mobileMoneyCallbackTokenStore(MobileMoneyProperties properties) {
        requireNotRedis(properties);
        return new InMemoryCallbackTokenStore(CLOCK);
    }

    /**
     * The in-memory store of pending transactions, when the pending-payment task is on and no other store is
     * configured.
     *
     * @param properties the configuration
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty("mobile-money.pending-check.enabled")
    public PendingTransactionStore mobileMoneyPendingTransactionStore(MobileMoneyProperties properties) {
        requireNotRedis(properties);
        return new InMemoryPendingTransactionStore();
    }

    /**
     * The entry point, checked at startup.
     *
     * @param properties the configuration
     * @param idempotencyStore the idempotency store
     * @param callbackTokenStore the callback token store
     * @param customProviders providers declared as beans by the application
     * @param pendingStore the store of pending transactions, when the pending-payment task is on
     * @return the facade
     * @throws ConfigurationException if the configuration is incomplete or dangerous
     */
    @Bean
    @ConditionalOnMissingBean
    public MobileMoney mobileMoney(MobileMoneyProperties properties, IdempotencyStore idempotencyStore,
            CallbackTokenStore callbackTokenStore, ObjectProvider<PaymentProvider> customProviders,
            ObjectProvider<PendingTransactionStore> pendingStore) {
        checkSettings(properties);
        ConfiguredProviders.Result configured = ConfiguredProviders.build(properties,
                customProviders.orderedStream().toList());
        MobileMoney.Builder builder = MobileMoney.builder();
        configured.providers().forEach(builder::provider);
        builder.failover(configured.failover().toArray(ProviderId[]::new))
                .idempotencyStore(idempotencyStore)
                .callbackTokenStore(callbackTokenStore)
                .callbackTokenLifetime(properties.callbackTokenLifetime())
                .callbackBaseUrl(properties.callbackBaseUrl())
                .clock(CLOCK);
        if (properties.pendingCheck().enabled()) {
            PendingTransactionStore store = pendingStore.getIfAvailable();
            if (store == null) {
                // The property was read as true by the binder but not by the bean conditions (yes, on, 1...).
                throw new ConfigurationException("mobile-money.pending-check.enabled must be true or false", null);
            }
            Set<ProviderId> readable = configured.providers().stream()
                    .filter(p -> p.capabilities().statusQuery())
                    .map(PaymentProvider::id)
                    .collect(Collectors.toUnmodifiableSet());
            builder.transactionListener(follow(store, readable, properties.pendingCheck().interval()));
        }
        MobileMoney mobileMoney = builder.build();
        LOG.info("Mobile Money ready: providers {}, default {}, failover {}", mobileMoney.providerIds(),
                configured.providers().get(0).id(), configured.failover());
        return mobileMoney;
    }

    /**
     * Verifies webhooks and publishes the outcome events.
     *
     * @param mobileMoney the facade
     * @param events the event publisher
     * @param pendingStore the store of pending transactions, if any
     * @return the handler
     */
    @Bean
    @ConditionalOnMissingBean
    public MobileMoneyWebhooks mobileMoneyWebhooks(MobileMoney mobileMoney, ApplicationEventPublisher events,
            ObjectProvider<PendingTransactionStore> pendingStore) {
        return new MobileMoneyWebhooks(mobileMoney, events, pendingStore.getIfAvailable());
    }

    /**
     * Reads pending payments again; only when {@code mobile-money.pending-check.enabled=true}.
     *
     * @param properties the configuration
     * @param mobileMoney the facade
     * @param store the store of pending transactions
     * @param events the event publisher
     * @return the task
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty("mobile-money.pending-check.enabled")
    public PendingTransactionChecker mobileMoneyPendingTransactionChecker(MobileMoneyProperties properties,
            MobileMoney mobileMoney, PendingTransactionStore store, ApplicationEventPublisher events) {
        MobileMoneyProperties.PendingCheck check = properties.pendingCheck();
        return new PendingTransactionChecker(mobileMoney, store, events, check.interval(), check.maxAge(),
                check.batchSize(), CLOCK);
    }

    private static TransactionListener follow(PendingTransactionStore store, Set<ProviderId> readable,
            java.time.Duration interval) {
        return transaction -> {
            if (transaction.status() != TransactionStatus.PENDING) {
                return;
            }
            if (transaction.providerReference() == null || !readable.contains(transaction.provider())) {
                LOG.warn("{} {} {} cannot be followed (no reference or no status query): wait for its webhook or"
                        + " reconcile it manually", transaction.provider(), transaction.type(),
                        transaction.merchantReference());
                return;
            }
            java.time.Instant now = CLOCK.instant();
            store.add(transaction, now, now.plus(interval));
        };
    }

    private static void checkSettings(MobileMoneyProperties properties) {
        String path = properties.webhook().path();
        if (path == null || !path.startsWith("/") || path.endsWith("/") || path.contains("{")) {
            throw new ConfigurationException("mobile-money.webhook.path must start with '/' and not end with '/'",
                    null);
        }
        MobileMoneyProperties.PendingCheck check = properties.pendingCheck();
        if (check.interval().isNegative() || check.interval().isZero() || check.maxAge().compareTo(check.interval()) <= 0
                || check.batchSize() <= 0) {
            throw new ConfigurationException("mobile-money.pending-check: interval and batch-size must be positive,"
                    + " and max-age longer than interval", null);
        }
        if (properties.http().connectTimeout().isNegative() || properties.http().connectTimeout().isZero()
                || properties.http().requestTimeout().isNegative() || properties.http().requestTimeout().isZero()) {
            throw new ConfigurationException("mobile-money.http timeouts must be positive", null);
        }
    }

    private static void requireNotRedis(MobileMoneyProperties properties) {
        if (properties.store() == MobileMoneyProperties.StoreType.REDIS) {
            throw new ConfigurationException("mobile-money.store=redis but no Redis connection is configured: add"
                    + " spring-boot-starter-data-redis and the spring.data.redis.* properties", null);
        }
    }
}
