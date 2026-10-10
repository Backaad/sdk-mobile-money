package io.github.backaad.mobilemoney.spring;

import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.callback.CallbackTokenStore;
import io.github.backaad.mobilemoney.idempotency.IdempotencyStore;
import io.github.backaad.mobilemoney.spring.pending.PendingTransactionStore;
import io.github.backaad.mobilemoney.spring.redis.RedisCallbackTokenStore;
import io.github.backaad.mobilemoney.spring.redis.RedisIdempotencyStore;
import io.github.backaad.mobilemoney.spring.redis.RedisPendingTransactionStore;
import java.time.Clock;
import java.util.Locale;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Keeps idempotency records, callback tokens and pending transactions in Redis when the application has a
 * Redis connection ({@code spring-boot-starter-data-redis}), unless {@code mobile-money.store=memory}.
 */
@AutoConfiguration(before = MobileMoneyAutoConfiguration.class,
        afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnBooleanProperty(name = "mobile-money.enabled", matchIfMissing = true)
@ConditionalOnClass({StringRedisTemplate.class, RedisConnectionFactory.class})
@ConditionalOnBean(RedisConnectionFactory.class)
@Conditional(MobileMoneyRedisAutoConfiguration.StoreIsNotMemory.class)
@EnableConfigurationProperties(MobileMoneyProperties.class)
public class MobileMoneyRedisAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MobileMoneyRedisAutoConfiguration.class);
    private static final Clock CLOCK = Clock.systemUTC();

    /**
     * The idempotency store, after checking that Redis is reachable and does not evict keys.
     *
     * @param connectionFactory the Redis connection
     * @param properties the configuration
     * @return the store
     * @throws ConfigurationException if Redis cannot be reached or may evict keys
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore mobileMoneyIdempotencyStore(RedisConnectionFactory connectionFactory,
            MobileMoneyProperties properties) {
        checkServer(connectionFactory, properties.redis().checkEvictionPolicy());
        LOG.info("Mobile Money stores its idempotency records in Redis");
        return new RedisIdempotencyStore(new StringRedisTemplate(connectionFactory), properties.redis().keyPrefix(),
                CLOCK);
    }

    /**
     * The callback token store.
     *
     * @param connectionFactory the Redis connection
     * @param properties the configuration
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean
    public CallbackTokenStore mobileMoneyCallbackTokenStore(RedisConnectionFactory connectionFactory,
            MobileMoneyProperties properties) {
        return new RedisCallbackTokenStore(new StringRedisTemplate(connectionFactory),
                properties.redis().keyPrefix(), CLOCK);
    }

    /**
     * The store of pending transactions, when the pending-payment task is on.
     *
     * @param connectionFactory the Redis connection
     * @param properties the configuration
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty("mobile-money.pending-check.enabled")
    public PendingTransactionStore mobileMoneyPendingTransactionStore(RedisConnectionFactory connectionFactory,
            MobileMoneyProperties properties) {
        return new RedisPendingTransactionStore(new StringRedisTemplate(connectionFactory),
                properties.redis().keyPrefix());
    }

    static void checkServer(RedisConnectionFactory connectionFactory, boolean checkEvictionPolicy) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.ping();
            if (!checkEvictionPolicy) {
                return;
            }
            String policy;
            try {
                Properties config = connection.serverCommands().getConfig("maxmemory-policy");
                policy = config == null ? null : config.getProperty("maxmemory-policy");
            } catch (DataAccessException e) {
                LOG.warn("Could not read the Redis maxmemory-policy (CONFIG is not allowed?): make sure it is"
                        + " noeviction, or idempotency records may be evicted");
                return;
            }
            if (policy != null && !policy.toLowerCase(Locale.ROOT).equals("noeviction")) {
                throw new ConfigurationException("Redis maxmemory-policy is '" + policy + "': it may evict"
                        + " idempotency records and let a payment through twice. Use noeviction, or set"
                        + " mobile-money.redis.check-eviction-policy=false if these keys can never be evicted", null);
            }
        } catch (RedisConnectionFailureException e) {
            throw new ConfigurationException("Redis cannot be reached (" + e.getClass().getSimpleName()
                    + "): check spring.data.redis.*, or set mobile-money.store=memory", null);
        }
    }

    /** Matches unless {@code mobile-money.store} is {@code memory}. */
    static final class StoreIsNotMemory extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String store = context.getEnvironment().getProperty("mobile-money.store", "auto");
            return "memory".equalsIgnoreCase(store.trim())
                    ? ConditionOutcome.noMatch("mobile-money.store is memory")
                    : ConditionOutcome.match("mobile-money.store is " + store);
        }
    }
}
