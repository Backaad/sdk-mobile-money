package io.github.backaad.mobilemoney.spring;

import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.provider.PaymentProvider;
import io.github.backaad.mobilemoney.provider.campay.CampayEnvironment;
import io.github.backaad.mobilemoney.provider.campay.CampayProvider;
import io.github.backaad.mobilemoney.provider.mtnmomo.MtnMomoEnvironment;
import io.github.backaad.mobilemoney.provider.mtnmomo.MtnMomoProvider;
import io.github.backaad.mobilemoney.provider.notchpay.NotchPayProvider;
import io.github.backaad.mobilemoney.provider.simulation.SimulationProvider;
import io.github.backaad.mobilemoney.provider.tara.TaraProvider;
import java.lang.reflect.RecordComponent;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the providers from {@link MobileMoneyProperties} and checks the configuration. Every message names
 * the property to fix and never contains a configured value.
 */
final class ConfiguredProviders {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguredProviders.class);
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /**
     * The providers, default first, and the failover order.
     *
     * @param providers the providers, the default one first
     * @param failover the failover providers, in order
     */
    record Result(List<PaymentProvider> providers, List<ProviderId> failover) {
    }

    private ConfiguredProviders() {
    }

    /**
     * Builds and checks the providers.
     *
     * @param properties the configuration
     * @param custom providers declared as beans by the application
     * @return the providers
     * @throws ConfigurationException if the configuration is incomplete or dangerous
     */
    static Result build(MobileMoneyProperties properties, List<PaymentProvider> custom) {
        checkUnresolvedPlaceholders(properties, "mobile-money");
        Map<ProviderId, PaymentProvider> all = new LinkedHashMap<>();
        if (properties.simulation().enabled()) {
            if (anyRealProviderEnabled(properties) || !custom.isEmpty()) {
                throw new ConfigurationException("mobile-money.simulation.enabled=true cannot be combined with a"
                        + " real provider: turn the simulation off, or remove the provider configuration",
                        ProviderId.SIMULATION);
            }
            LOG.warn("Mobile Money runs in SIMULATION mode: nothing is sent, and no payment is ever confirmed");
            all.put(ProviderId.SIMULATION, new SimulationProvider());
        } else {
            if (enabled(properties.campay())) {
                add(all, campay(properties));
            }
            if (enabled(properties.mtnMomo())) {
                add(all, mtnMomo(properties));
            }
            if (enabled(properties.notchpay())) {
                add(all, notchPay(properties));
            }
            if (enabled(properties.tara())) {
                add(all, tara(properties));
            }
            for (PaymentProvider provider : custom) {
                add(all, provider);
            }
        }
        if (all.isEmpty()) {
            throw new ConfigurationException("No Mobile Money provider is configured: set the properties of one"
                    + " provider (mobile-money.campay.*, mobile-money.mtn-momo.*, mobile-money.notchpay.*,"
                    + " mobile-money.tara.*), or mobile-money.simulation.enabled=true to develop without an account,"
                    + " or mobile-money.enabled=false", null);
        }

        PaymentProvider first = defaultProvider(properties, all);
        List<PaymentProvider> ordered = new ArrayList<>();
        ordered.add(first);
        all.values().stream().filter(p -> p != first).forEach(ordered::add);

        List<ProviderId> failover = new ArrayList<>();
        for (String name : properties.failover()) {
            ProviderId id = id(name, "mobile-money.failover");
            if (!all.containsKey(id)) {
                throw new ConfigurationException("mobile-money.failover lists " + id + ", which is not enabled", id);
            }
            if (id.equals(first.id())) {
                throw new ConfigurationException("mobile-money.failover lists the default provider " + id, id);
            }
            if (failover.contains(id)) {
                throw new ConfigurationException("mobile-money.failover lists " + id + " twice", id);
            }
            failover.add(id);
        }

        checkCallbackUrl(properties, all);
        return new Result(List.copyOf(ordered), List.copyOf(failover));
    }

    private static PaymentProvider defaultProvider(MobileMoneyProperties properties,
            Map<ProviderId, PaymentProvider> all) {
        if (properties.defaultProvider() == null || properties.defaultProvider().isBlank()) {
            if (all.size() > 1) {
                throw new ConfigurationException("Several providers are enabled (" + all.keySet()
                        + "): set mobile-money.default-provider to the one to use first", null);
            }
            return all.values().iterator().next();
        }
        ProviderId id = id(properties.defaultProvider(), "mobile-money.default-provider");
        PaymentProvider provider = all.get(id);
        if (provider == null) {
            throw new ConfigurationException("mobile-money.default-provider is " + id + ", which is not enabled"
                    + " (enabled: " + all.keySet() + ")", id);
        }
        return provider;
    }

    private static PaymentProvider campay(MobileMoneyProperties properties) {
        MobileMoneyProperties.Campay c = properties.campay();
        require(c.environment(), "mobile-money.campay.environment (sandbox or production)", ProviderId.CAMPAY);
        require(c.username(), "mobile-money.campay.username", ProviderId.CAMPAY);
        require(c.password(), "mobile-money.campay.password", ProviderId.CAMPAY);
        if (blank(c.webhookKey())) {
            LOG.warn("mobile-money.campay.webhook-key is not set: Campay webhooks will be refused");
        }
        return CampayProvider.builder()
                .environment(c.environment())
                .baseUrl(c.baseUrl())
                .username(c.username())
                .password(c.password())
                .webhookKey(blankToNull(c.webhookKey()))
                .connectTimeout(properties.http().connectTimeout())
                .requestTimeout(properties.http().requestTimeout())
                .build();
    }

    private static PaymentProvider mtnMomo(MobileMoneyProperties properties) {
        MobileMoneyProperties.MtnMomo m = properties.mtnMomo();
        require(m.environment(), "mobile-money.mtn-momo.environment (sandbox or production)", ProviderId.MTN_MOMO);
        MobileMoneyProperties.MtnMomo.Product collection = present(m.collection());
        MobileMoneyProperties.MtnMomo.Product disbursement = present(m.disbursement());
        if (collection == null && disbursement == null) {
            throw new ConfigurationException("MTN MoMo is enabled but neither mobile-money.mtn-momo.collection.*"
                    + " nor mobile-money.mtn-momo.disbursement.* is set", ProviderId.MTN_MOMO);
        }
        if (m.environment() == MtnMomoEnvironment.PRODUCTION) {
            require(m.targetEnvironment(), "mobile-money.mtn-momo.target-environment (given by MTN for the country)",
                    ProviderId.MTN_MOMO);
        }
        MtnMomoProvider.Builder builder = MtnMomoProvider.builder()
                .environment(m.environment())
                .baseUrl(m.baseUrl())
                .targetEnvironment(blankToNull(m.targetEnvironment()))
                .connectTimeout(properties.http().connectTimeout())
                .requestTimeout(properties.http().requestTimeout());
        if (!blank(m.currency())) {
            try {
                builder.currency(Currency.getInstance(m.currency().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException("mobile-money.mtn-momo.currency is not an ISO 4217 currency code",
                        ProviderId.MTN_MOMO);
            }
        }
        if (collection != null) {
            MobileMoneyProperties.MtnMomo.Product p = product(collection, "collection");
            builder.collection(p.subscriptionKey(), p.apiUser(), p.apiKey());
        }
        if (disbursement != null) {
            MobileMoneyProperties.MtnMomo.Product p = product(disbursement, "disbursement");
            builder.disbursement(p.subscriptionKey(), p.apiUser(), p.apiKey());
        }
        return builder.build();
    }

    private static MobileMoneyProperties.MtnMomo.Product product(MobileMoneyProperties.MtnMomo.Product product,
            String name) {
        String prefix = "mobile-money.mtn-momo." + name + ".";
        require(product.subscriptionKey(), prefix + "subscription-key", ProviderId.MTN_MOMO);
        require(product.apiUser(), prefix + "api-user", ProviderId.MTN_MOMO);
        require(product.apiKey(), prefix + "api-key", ProviderId.MTN_MOMO);
        return product;
    }

    private static PaymentProvider notchPay(MobileMoneyProperties properties) {
        MobileMoneyProperties.NotchPay n = properties.notchpay();
        require(n.publicKey(), "mobile-money.notchpay.public-key", ProviderId.NOTCHPAY);
        if (blank(n.webhookSecret())) {
            LOG.warn("mobile-money.notchpay.webhook-secret is not set: NotchPay webhooks will be refused");
        }
        return NotchPayProvider.builder()
                .baseUrl(n.baseUrl())
                .publicKey(n.publicKey())
                .privateKey(blankToNull(n.privateKey()))
                .webhookSecret(blankToNull(n.webhookSecret()))
                .connectTimeout(properties.http().connectTimeout())
                .requestTimeout(properties.http().requestTimeout())
                .build();
    }

    private static PaymentProvider tara(MobileMoneyProperties properties) {
        MobileMoneyProperties.Tara t = properties.tara();
        require(t.apiKey(), "mobile-money.tara.api-key", ProviderId.TARA);
        require(t.businessId(), "mobile-money.tara.business-id", ProviderId.TARA);
        if (properties.callbackBaseUrl() == null) {
            throw new ConfigurationException("Tara needs mobile-money.callback-base-url: each payment gets its own"
                    + " single-use callback URL under it", ProviderId.TARA);
        }
        TaraProvider.Builder builder = TaraProvider.builder()
                .baseUrl(t.baseUrl())
                .apiKey(t.apiKey())
                .businessId(t.businessId())
                .connectTimeout(properties.http().connectTimeout())
                .requestTimeout(properties.http().requestTimeout());
        if (t.allowedCallbackAddresses() != null) {
            List<String> addresses = t.allowedCallbackAddresses().stream()
                    .filter(a -> a != null && !a.isBlank()).map(String::trim).toList();
            if (!addresses.isEmpty()) {
                builder.allowedCallbackAddresses(addresses);
            }
        }
        LOG.warn("Tara is EXPERIMENTAL: see docs/providers/tara.md");
        return builder.build();
    }

    /**
     * The callback base URL is sent to providers that call back a URL given with each request (MTN MoMo,
     * Tara): in production, it must be reachable from the internet.
     */
    private static void checkCallbackUrl(MobileMoneyProperties properties, Map<ProviderId, PaymentProvider> all) {
        URI url = properties.callbackBaseUrl();
        if (url == null) {
            if (all.containsKey(ProviderId.MTN_MOMO)) {
                LOG.warn("mobile-money.callback-base-url is not set: MTN MoMo will not call back, payments are only"
                        + " known by reading their status");
            }
            return;
        }
        if (!url.isAbsolute() || url.getHost() == null) {
            throw new ConfigurationException("mobile-money.callback-base-url must be an absolute URL", null);
        }
        if (url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null
                || url.toString().endsWith("/")) {
            throw new ConfigurationException("mobile-money.callback-base-url must not contain user information, a"
                    + " query or a fragment, and must not end with '/'", null);
        }
        boolean local = isLocal(url.getHost());
        boolean https = "https".equalsIgnoreCase(url.getScheme());
        boolean loopback = isLoopback(url.getHost());
        if (!https && !("http".equalsIgnoreCase(url.getScheme()) && loopback)) {
            throw new ConfigurationException("mobile-money.callback-base-url must use HTTPS (plain HTTP is only"
                    + " allowed on localhost)", null);
        }
        // Properties tell the environment of the providers they configure; a provider declared as a bean
        // with the same id has no such property.
        boolean mtnProduction = properties.mtnMomo() != null && enabled(properties.mtnMomo())
                && properties.mtnMomo().environment() == MtnMomoEnvironment.PRODUCTION;
        boolean campayProduction = properties.campay() != null && enabled(properties.campay())
                && properties.campay().environment() == CampayEnvironment.PRODUCTION;
        boolean live = mtnProduction || all.containsKey(ProviderId.TARA);
        boolean anyProduction = live || campayProduction;
        if (live && (local || !https)) {
            throw new ConfigurationException("mobile-money.callback-base-url must be a public HTTPS URL in production"
                    + " (MTN MoMo production or Tara send it to the provider), not " + (local ? "a local address"
                    : "plain HTTP"), null);
        }
        if (anyProduction && local) {
            LOG.warn("mobile-money.callback-base-url is a local address while a provider is in production");
        }
        String path = properties.webhook().path();
        if (properties.webhook().enabled() && !Objects.requireNonNullElse(url.getPath(), "").endsWith(path)) {
            LOG.warn("mobile-money.callback-base-url does not end with mobile-money.webhook.path ({}): check that"
                    + " callbacks reach the webhook endpoint", path);
        }
    }

    private static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }

    static boolean isLocal(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")
                || h.endsWith(".lan") || h.endsWith(".home.arpa")) {
            return true;
        }
        if (!IPV4.matcher(h).matches() && !h.contains(":")) {
            return false;
        }
        try {
            // A literal address: no DNS lookup is made.
            InetAddress address = InetAddress.getByName(h);
            byte[] bytes = address.getAddress();
            boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress() || uniqueLocalV6;
        } catch (UnknownHostException e) {
            return true;
        }
    }

    /**
     * Spring keeps an unresolvable {@code ${NAME}} as the value itself: a key written {@code ${CAMPAY_PASSWORD}}
     * whose variable is not set would otherwise pass for a real key.
     */
    static void checkUnresolvedPlaceholders(Object value, String property) {
        if (value == null) {
            return;
        }
        if (value instanceof String || value instanceof URI) {
            if (value.toString().contains("${")) {
                throw new ConfigurationException(property + " contains an unresolved ${...} placeholder: the"
                        + " environment variable it names is not set", null);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                checkUnresolvedPlaceholders(item, property);
            }
            return;
        }
        if (value instanceof Record record) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    checkUnresolvedPlaceholders(component.getAccessor().invoke(record),
                            property + "." + kebab(component.getName()));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static void add(Map<ProviderId, PaymentProvider> all, PaymentProvider provider) {
        if (all.putIfAbsent(provider.id(), provider) != null) {
            throw new ConfigurationException("Provider " + provider.id() + " is configured twice (properties and a"
                    + " PaymentProvider bean?)", provider.id());
        }
    }

    private static boolean anyRealProviderEnabled(MobileMoneyProperties p) {
        return enabled(p.campay()) || enabled(p.mtnMomo()) || enabled(p.notchpay()) || enabled(p.tara());
    }

    private static boolean enabled(MobileMoneyProperties.Campay c) {
        return c != null && (c.enabled() != null ? c.enabled() : anySet(c.environment(), c.username(), c.password(),
                c.webhookKey(), c.baseUrl()));
    }

    private static boolean enabled(MobileMoneyProperties.MtnMomo m) {
        return m != null && (m.enabled() != null ? m.enabled() : anySet(m.environment(), present(m.collection()),
                present(m.disbursement()), m.currency(), m.baseUrl()));
    }

    private static boolean enabled(MobileMoneyProperties.NotchPay n) {
        return n != null && (n.enabled() != null ? n.enabled() : anySet(n.publicKey(), n.privateKey(),
                n.webhookSecret(), n.baseUrl()));
    }

    private static boolean enabled(MobileMoneyProperties.Tara t) {
        return t != null && (t.enabled() != null ? t.enabled() : anySet(t.apiKey(), t.businessId(), t.baseUrl(),
                t.allowedCallbackAddresses() == null || t.allowedCallbackAddresses().stream().allMatch(
                        a -> a == null || a.isBlank()) ? null : "set"));
    }

    /** A product whose properties are all blank (unset environment variables) counts as absent. */
    private static MobileMoneyProperties.MtnMomo.Product present(MobileMoneyProperties.MtnMomo.Product product) {
        return product != null && anySet(product.subscriptionKey(), product.apiUser(), product.apiKey())
                ? product : null;
    }

    private static boolean anySet(Object... values) {
        for (Object value : values) {
            if (value != null && !(value instanceof String text && text.isBlank())) {
                return true;
            }
        }
        return false;
    }

    private static ProviderId id(String name, String property) {
        try {
            return new ProviderId(name.trim());
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(property + " contains an invalid provider id", null);
        }
    }

    private static void require(Object value, String property, ProviderId provider) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new ConfigurationException(provider + " is enabled but " + property + " is not set", provider);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value;
    }
}
