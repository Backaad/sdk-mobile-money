package io.github.backaad.mobilemoney.spring;

import io.github.backaad.mobilemoney.provider.campay.CampayEnvironment;
import io.github.backaad.mobilemoney.provider.mtnmomo.MtnMomoEnvironment;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration under {@code mobile-money.*}. Checked at startup by {@link MobileMoneyAutoConfiguration}:
 * an incomplete or dangerous configuration prevents the application from starting, with a message that
 * names the property.
 *
 * <p>A provider is enabled when one of its properties is set, or when its {@code enabled} property is
 * {@code true}; {@code enabled: false} turns it off whatever else is set. Keys belong in environment
 * variables or a secret store, never in a file committed to a repository.
 *
 * @param enabled set to {@code false} to turn the whole integration off
 * @param defaultProvider the provider used first ({@code campay}, {@code mtn-momo}, {@code notchpay},
 *     {@code tara}); required when several providers are enabled
 * @param failover providers to try, in order, when the default provider certainly did not receive the
 *     request; empty by default: no failover
 * @param callbackBaseUrl public URL of the webhook endpoint, for example
 *     {@code https://shop.example/mobile-money/webhooks}; each request then gets
 *     {@code <callback-base-url>/<provider>} as callback URL. Required for Tara
 * @param callbackTokenLifetime how long a Tara single-use callback URL stays valid
 * @param store where idempotency records, callback tokens and pending payments are kept
 * @param redis Redis settings, used when the store is Redis
 * @param http timeouts of the calls to providers
 * @param simulation simulation mode
 * @param webhook the webhook endpoint
 * @param pendingCheck the optional task that reads pending payments again
 * @param campay Campay
 * @param mtnMomo MTN MoMo
 * @param notchpay NotchPay
 * @param tara Tara (experimental)
 */
@ConfigurationProperties("mobile-money")
public record MobileMoneyProperties(
        @DefaultValue("true") boolean enabled,
        String defaultProvider,
        @DefaultValue List<String> failover,
        URI callbackBaseUrl,
        @DefaultValue("24h") Duration callbackTokenLifetime,
        @DefaultValue("auto") StoreType store,
        @DefaultValue Redis redis,
        @DefaultValue Http http,
        @DefaultValue Simulation simulation,
        @DefaultValue Webhook webhook,
        @DefaultValue PendingCheck pendingCheck,
        Campay campay,
        MtnMomo mtnMomo,
        NotchPay notchpay,
        Tara tara) {

    /** Treats an empty URL (an unset environment variable) as absent. */
    public MobileMoneyProperties {
        callbackBaseUrl = emptyToNull(callbackBaseUrl);
    }

    static URI emptyToNull(URI url) {
        return url == null || url.toString().isBlank() ? null : url;
    }

    /** Where idempotency records, callback tokens and pending payments are kept. */
    public enum StoreType {
        /** Redis when a Redis connection is configured in the application, memory otherwise. */
        AUTO,
        /** In the JVM: lost on restart, and not shared between instances. */
        MEMORY,
        /** Redis; the application fails to start if no Redis connection is configured. */
        REDIS
    }

    /**
     * Redis settings.
     *
     * @param keyPrefix prefix of every key written by the SDK
     * @param checkEvictionPolicy at startup, refuse a Redis server that may evict keys (any
     *     {@code maxmemory-policy} other than {@code noeviction}): an evicted idempotency record could let
     *     a payment through twice
     */
    public record Redis(@DefaultValue("mobile-money:") String keyPrefix,
            @DefaultValue("true") boolean checkEvictionPolicy) {
    }

    /**
     * Timeouts of the calls to providers.
     *
     * @param connectTimeout connection timeout
     * @param requestTimeout timeout of a whole request, response included
     */
    public record Http(@DefaultValue("10s") Duration connectTimeout, @DefaultValue("30s") Duration requestTimeout) {
    }

    /**
     * Simulation mode: talks to nobody and never confirms a payment. Cannot be combined with a provider.
     *
     * @param enabled turns the simulation on
     */
    public record Simulation(@DefaultValue("false") Boolean enabled) {

        /** An empty value (an unset environment variable) means off. */
        public Simulation {
            enabled = enabled != null && enabled;
        }
    }

    /**
     * The webhook endpoint, {@code <path>/<provider>}, registered when Spring MVC is present.
     *
     * @param enabled registers the endpoint
     * @param path base path, without trailing slash
     */
    public record Webhook(@DefaultValue("true") boolean enabled, @DefaultValue("/mobile-money/webhooks") String path) {
    }

    /**
     * The optional task that reads pending payments again until they succeed or fail.
     *
     * @param enabled turns the task on; off by default
     * @param interval time between two reads of the same payment
     * @param maxAge after this time, a payment still pending is no longer read and is logged for manual
     *     reconciliation
     * @param batchSize payments read at most per run
     */
    public record PendingCheck(@DefaultValue("false") Boolean enabled, @DefaultValue("1m") Duration interval,
            @DefaultValue("24h") Duration maxAge, @DefaultValue("50") int batchSize) {

        /** An empty value (an unset environment variable) means off. */
        public PendingCheck {
            enabled = enabled != null && enabled;
        }
    }

    /**
     * Campay.
     *
     * @param enabled {@code true} or {@code false} to force; otherwise enabled when a property is set
     * @param environment {@code sandbox} or {@code production}
     * @param username application username
     * @param password application password
     * @param webhookKey application webhook key, to verify webhooks
     * @param baseUrl replaces the environment's URL (tests)
     */
    public record Campay(Boolean enabled, CampayEnvironment environment, String username, String password,
            String webhookKey, URI baseUrl) {

        /** Treats an empty URL as absent. */
        public Campay {
            baseUrl = emptyToNull(baseUrl);
        }

        @Override
        public String toString() {
            return "Campay[environment=" + environment + ", credentials=" + (password != null ? "***" : "unset")
                    + "]";
        }
    }

    /**
     * MTN MoMo.
     *
     * @param enabled {@code true} or {@code false} to force; otherwise enabled when a property is set
     * @param environment {@code sandbox} or {@code production}
     * @param targetEnvironment {@code X-Target-Environment}; {@code sandbox} by default in the sandbox,
     *     required in production
     * @param currency the account currency (ISO 4217 code), {@code XAF} by default ({@code EUR} in the public
     *     sandbox)
     * @param collection credentials of the collection product
     * @param disbursement credentials of the disbursement product
     * @param baseUrl replaces the environment's URL (tests)
     */
    public record MtnMomo(Boolean enabled, MtnMomoEnvironment environment, String targetEnvironment,
            String currency, Product collection, Product disbursement, URI baseUrl) {

        /** Treats an empty URL as absent. */
        public MtnMomo {
            baseUrl = emptyToNull(baseUrl);
        }

        /**
         * Credentials of one MTN MoMo product.
         *
         * @param subscriptionKey the product's subscription key
         * @param apiUser the API user
         * @param apiKey the API key
         */
        public record Product(String subscriptionKey, String apiUser, String apiKey) {

            @Override
            public String toString() {
                return "Product[***]";
            }
        }
    }

    /**
     * NotchPay.
     *
     * @param enabled {@code true} or {@code false} to force; otherwise enabled when a property is set
     * @param publicKey public key
     * @param privateKey private key, needed for payouts
     * @param webhookSecret secret that signs webhooks
     * @param baseUrl replaces the API URL (tests)
     */
    public record NotchPay(Boolean enabled, String publicKey, String privateKey, String webhookSecret,
            URI baseUrl) {

        /** Treats an empty URL as absent. */
        public NotchPay {
            baseUrl = emptyToNull(baseUrl);
        }

        @Override
        public String toString() {
            return "NotchPay[keys=***]";
        }
    }

    /**
     * Tara (experimental).
     *
     * @param enabled {@code true} or {@code false} to force; otherwise enabled when a property is set
     * @param apiKey API key
     * @param businessId business identifier
     * @param allowedCallbackAddresses optional IP allowlist for callbacks
     * @param baseUrl replaces the API URL (tests)
     */
    public record Tara(Boolean enabled, String apiKey, String businessId, List<String> allowedCallbackAddresses,
            URI baseUrl) {

        /** Treats an empty URL as absent. */
        public Tara {
            baseUrl = emptyToNull(baseUrl);
        }

        @Override
        public String toString() {
            return "Tara[apiKey=***]";
        }
    }
}
