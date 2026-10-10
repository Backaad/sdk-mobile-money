package io.github.backaad.mobilemoney.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.ProviderId;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rule 10: providers are built from the variables of .env.example, and an incomplete one prevents startup. */
class ProviderFactoryTest {

    @Test
    void nothingSetMeansNoProvider() {
        Map<String, String> values = new HashMap<>();
        values.put("CAMPAY_USERNAME", "");
        values.put("MTN_MOMO_TARGET_ENVIRONMENT", "sandbox");
        values.put("NOTCHPAY_PUBLIC_KEY", null);

        assertThat(ProviderFactory.from(values).configuredProviders()).isEmpty();
    }

    @Test
    void everyProviderIsBuiltFromItsVariables() {
        Map<String, String> values = new HashMap<>();
        values.put("CAMPAY_ENVIRONMENT", "sandbox");
        values.put("CAMPAY_USERNAME", "u");
        values.put("CAMPAY_PASSWORD", "p");
        values.put("MTN_MOMO_ENVIRONMENT", "SANDBOX");
        values.put("MTN_MOMO_COLLECTION_SUBSCRIPTION_KEY", "s");
        values.put("MTN_MOMO_COLLECTION_API_USER", "u");
        values.put("MTN_MOMO_COLLECTION_API_KEY", "k");
        values.put("MTN_MOMO_CURRENCY", "eur");
        values.put("NOTCHPAY_PUBLIC_KEY", "pk_test_x");
        values.put("TARA_API_KEY", "k");
        values.put("TARA_BUSINESS_ID", "b");
        values.put("TARA_CALLBACK_ALLOWED_IPS", "203.0.113.7, 198.51.100.1");

        List<PaymentProvider> providers = ProviderFactory.from(values).configuredProviders();

        assertThat(providers).extracting(PaymentProvider::id).containsExactly(ProviderId.CAMPAY,
                ProviderId.MTN_MOMO, ProviderId.NOTCHPAY, ProviderId.TARA);
        PaymentProvider mtn = providers.get(1);
        assertThat(mtn.capabilities().collection()).isTrue();
        assertThat(mtn.capabilities().payout()).isFalse();
        assertThat(mtn.capabilities().currencies()).containsExactly(Currency.getInstance("EUR"));
        assertThat(providers.get(2).capabilities().payout()).isFalse();
    }

    @Test
    void environmentIsNeverGuessed() {
        assertThatThrownBy(() -> ProviderFactory.from(Map.of("CAMPAY_USERNAME", "u", "CAMPAY_PASSWORD", "p"))
                .configuredProviders())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("CAMPAY_ENVIRONMENT");
        assertThatThrownBy(() -> ProviderFactory.from(Map.of("MTN_MOMO_COLLECTION_API_KEY", "k")).mtnMomo())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("MTN_MOMO_ENVIRONMENT");
        assertThatThrownBy(() -> ProviderFactory.from(Map.of("CAMPAY_ENVIRONMENT", "staging",
                "CAMPAY_USERNAME", "u", "CAMPAY_PASSWORD", "p")).campay())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("sandbox or production");
    }

    @Test
    void incompleteConfigurationPreventsStartup() {
        assertThatThrownBy(() -> ProviderFactory.from(Map.of("CAMPAY_ENVIRONMENT", "sandbox",
                "CAMPAY_USERNAME", "u")).campay())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("password");
        assertThatThrownBy(() -> ProviderFactory.from(Map.of("MTN_MOMO_ENVIRONMENT", "sandbox",
                "MTN_MOMO_DISBURSEMENT_API_KEY", "k")).mtnMomo())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("disbursement");
        assertThatThrownBy(() -> ProviderFactory.from(Map.of("MTN_MOMO_ENVIRONMENT", "production",
                "MTN_MOMO_COLLECTION_SUBSCRIPTION_KEY", "s", "MTN_MOMO_COLLECTION_API_USER", "u",
                "MTN_MOMO_COLLECTION_API_KEY", "k")).mtnMomo())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("target environment");
        assertThatThrownBy(() -> ProviderFactory.from(Map.of("MTN_MOMO_ENVIRONMENT", "sandbox",
                "MTN_MOMO_COLLECTION_SUBSCRIPTION_KEY", "s", "MTN_MOMO_COLLECTION_API_USER", "u",
                "MTN_MOMO_COLLECTION_API_KEY", "k", "MTN_MOMO_CURRENCY", "euro")).mtnMomo())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("MTN_MOMO_CURRENCY");
        assertThatThrownBy(() -> ProviderFactory.from(Map.of("NOTCHPAY_WEBHOOK_SECRET", "s")).notchPay())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("public key");
        assertThatThrownBy(() -> ProviderFactory.from(Map.of("TARA_API_KEY", "k")).tara())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("business");
    }

    @Test
    void messagesNeverContainTheValues() {
        assertThatThrownBy(() -> ProviderFactory.from(Map.of("CAMPAY_ENVIRONMENT", "sandbox",
                "CAMPAY_PASSWORD", "CANARY-PASSWORD-0000")).campay())
                .isInstanceOf(ConfigurationException.class).hasMessageNotContaining("CANARY");
    }
}
