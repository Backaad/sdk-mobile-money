package io.github.backaad.mobilemoney.provider;

import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.provider.campay.CampayEnvironment;
import io.github.backaad.mobilemoney.provider.campay.CampayProvider;
import io.github.backaad.mobilemoney.provider.mtnmomo.MtnMomoEnvironment;
import io.github.backaad.mobilemoney.provider.mtnmomo.MtnMomoProvider;
import io.github.backaad.mobilemoney.provider.notchpay.NotchPayProvider;
import io.github.backaad.mobilemoney.provider.tara.TaraProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds providers from configuration values named as in {@code .env.example}, for example
 * {@code CAMPAY_USERNAME}. A provider is built when at least one of its variables is set; an incomplete
 * or dangerous configuration is refused with a {@link ConfigurationException} that names the variable.
 *
 * <p>The environment (sandbox or production) is never guessed: {@code CAMPAY_ENVIRONMENT} and
 * {@code MTN_MOMO_ENVIRONMENT} are required for these providers.
 */
public final class ProviderFactory {

    private final Map<String, String> values;

    private ProviderFactory(Map<String, String> values) {
        this.values = Objects.requireNonNull(values, "values");
    }

    /**
     * Reads configuration values, for example {@code System.getenv()}.
     *
     * @param values the values by variable name
     * @return a factory
     */
    public static ProviderFactory from(Map<String, String> values) {
        return new ProviderFactory(Map.copyOf(withoutNulls(values)));
    }

    /**
     * Builds every provider that has at least one variable set, in the order Campay, MTN MoMo, NotchPay,
     * Tara.
     *
     * @return the providers, possibly none
     * @throws ConfigurationException if a provider's configuration is incomplete or dangerous
     */
    public List<PaymentProvider> configuredProviders() {
        List<PaymentProvider> providers = new ArrayList<>();
        campay().ifPresent(providers::add);
        mtnMomo().ifPresent(providers::add);
        notchPay().ifPresent(providers::add);
        tara().ifPresent(providers::add);
        return providers;
    }

    /**
     * Builds Campay from {@code CAMPAY_ENVIRONMENT}, {@code CAMPAY_USERNAME}, {@code CAMPAY_PASSWORD} and
     * {@code CAMPAY_WEBHOOK_KEY}.
     *
     * @return the provider, or empty if none of these variables is set
     * @throws ConfigurationException if the configuration is incomplete or dangerous
     */
    public Optional<PaymentProvider> campay() {
        if (!anySet("CAMPAY_ENVIRONMENT", "CAMPAY_USERNAME", "CAMPAY_PASSWORD", "CAMPAY_WEBHOOK_KEY")) {
            return Optional.empty();
        }
        return Optional.of(CampayProvider.builder()
                .environment(environment(ProviderId.CAMPAY, "CAMPAY_ENVIRONMENT", CampayEnvironment.class))
                .username(value("CAMPAY_USERNAME"))
                .password(value("CAMPAY_PASSWORD"))
                .webhookKey(value("CAMPAY_WEBHOOK_KEY"))
                .build());
    }

    /**
     * Builds MTN MoMo from {@code MTN_MOMO_ENVIRONMENT}, {@code MTN_MOMO_TARGET_ENVIRONMENT},
     * {@code MTN_MOMO_CURRENCY} and the {@code MTN_MOMO_COLLECTION_*} and {@code MTN_MOMO_DISBURSEMENT_*}
     * credentials. A product is enabled when one of its variables is set.
     *
     * @return the provider, or empty if none of these variables is set
     * @throws ConfigurationException if the configuration is incomplete or dangerous
     */
    public Optional<PaymentProvider> mtnMomo() {
        String[] collection = {"MTN_MOMO_COLLECTION_SUBSCRIPTION_KEY", "MTN_MOMO_COLLECTION_API_USER",
            "MTN_MOMO_COLLECTION_API_KEY"};
        String[] disbursement = {"MTN_MOMO_DISBURSEMENT_SUBSCRIPTION_KEY", "MTN_MOMO_DISBURSEMENT_API_USER",
            "MTN_MOMO_DISBURSEMENT_API_KEY"};
        boolean hasCollection = anySet(collection);
        boolean hasDisbursement = anySet(disbursement);
        if (!hasCollection && !hasDisbursement && !anySet("MTN_MOMO_ENVIRONMENT", "MTN_MOMO_CURRENCY")) {
            // MTN_MOMO_TARGET_ENVIRONMENT alone (it is pre-filled in .env.example) does not enable MTN MoMo.
            return Optional.empty();
        }
        MtnMomoProvider.Builder builder = MtnMomoProvider.builder()
                .environment(environment(ProviderId.MTN_MOMO, "MTN_MOMO_ENVIRONMENT", MtnMomoEnvironment.class))
                .targetEnvironment(value("MTN_MOMO_TARGET_ENVIRONMENT"));
        if (hasCollection) {
            builder.collection(value(collection[0]), value(collection[1]), value(collection[2]));
        }
        if (hasDisbursement) {
            builder.disbursement(value(disbursement[0]), value(disbursement[1]), value(disbursement[2]));
        }
        String currency = value("MTN_MOMO_CURRENCY");
        if (currency != null) {
            try {
                builder.currency(Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException("MTN_MOMO_CURRENCY is not an ISO 4217 currency code",
                        ProviderId.MTN_MOMO);
            }
        }
        return Optional.of(builder.build());
    }

    /**
     * Builds NotchPay from {@code NOTCHPAY_PUBLIC_KEY}, {@code NOTCHPAY_PRIVATE_KEY} and
     * {@code NOTCHPAY_WEBHOOK_SECRET}.
     *
     * @return the provider, or empty if none of these variables is set
     * @throws ConfigurationException if the configuration is incomplete or dangerous
     */
    public Optional<PaymentProvider> notchPay() {
        if (!anySet("NOTCHPAY_PUBLIC_KEY", "NOTCHPAY_PRIVATE_KEY", "NOTCHPAY_WEBHOOK_SECRET")) {
            return Optional.empty();
        }
        return Optional.of(NotchPayProvider.builder()
                .publicKey(value("NOTCHPAY_PUBLIC_KEY"))
                .privateKey(value("NOTCHPAY_PRIVATE_KEY"))
                .webhookSecret(value("NOTCHPAY_WEBHOOK_SECRET"))
                .build());
    }

    /**
     * Builds Tara from {@code TARA_API_KEY}, {@code TARA_BUSINESS_ID} and the optional comma-separated
     * {@code TARA_CALLBACK_ALLOWED_IPS}.
     *
     * @return the provider, or empty if none of these variables is set
     * @throws ConfigurationException if the configuration is incomplete or dangerous
     */
    public Optional<PaymentProvider> tara() {
        if (!anySet("TARA_API_KEY", "TARA_BUSINESS_ID", "TARA_CALLBACK_ALLOWED_IPS")) {
            return Optional.empty();
        }
        TaraProvider.Builder builder = TaraProvider.builder()
                .apiKey(value("TARA_API_KEY"))
                .businessId(value("TARA_BUSINESS_ID"));
        String allowed = value("TARA_CALLBACK_ALLOWED_IPS");
        if (allowed != null) {
            builder.allowedCallbackAddresses(Arrays.stream(allowed.split(","))
                    .map(String::trim)
                    .filter(a -> !a.isEmpty())
                    .toList());
        }
        return Optional.of(builder.build());
    }

    private <E extends Enum<E>> E environment(ProviderId provider, String name, Class<E> type) {
        String text = value(name);
        if (text == null) {
            throw new ConfigurationException(name + " is required (sandbox or production)", provider);
        }
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(name + " must be sandbox or production", provider);
        }
    }

    private boolean anySet(String... names) {
        for (String name : names) {
            if (value(name) != null) {
                return true;
            }
        }
        return false;
    }

    /** The value, or {@code null} when absent or blank (an empty line copied from .env.example). */
    private String value(String name) {
        String value = values.get(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, String> withoutNulls(Map<String, String> values) {
        Map<String, String> copy = new java.util.HashMap<>();
        Objects.requireNonNull(values, "values").forEach((k, v) -> {
            if (k != null && v != null) {
                copy.put(k, v);
            }
        });
        return copy;
    }
}
