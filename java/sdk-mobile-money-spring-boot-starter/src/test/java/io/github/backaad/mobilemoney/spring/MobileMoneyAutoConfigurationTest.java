package io.github.backaad.mobilemoney.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.idempotency.IdempotencyStore;
import io.github.backaad.mobilemoney.idempotency.InMemoryIdempotencyStore;
import io.github.backaad.mobilemoney.provider.PaymentProvider;
import io.github.backaad.mobilemoney.spring.pending.PendingTransactionChecker;
import io.github.backaad.mobilemoney.spring.pending.PendingTransactionStore;
import io.github.backaad.mobilemoney.spring.web.WebhookController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Rule 10: the mobile-money.* configuration is checked at startup, with messages naming the property. */
class MobileMoneyAutoConfigurationTest {

    static final AutoConfigurations AUTO = AutoConfigurations.of(MobileMoneyRedisAutoConfiguration.class,
            MobileMoneyAutoConfiguration.class, MobileMoneyWebMvcAutoConfiguration.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(AUTO);

    static final String[] CAMPAY = {"mobile-money.campay.environment=sandbox",
        "mobile-money.campay.username=CANARY-USER-0001", "mobile-money.campay.password=CANARY-PASSWORD-0002",
        "mobile-money.campay.webhook-key=CANARY-WEBHOOK-0003"};
    static final String[] NOTCHPAY = {"mobile-money.notchpay.public-key=CANARY-PUBLIC-0004"};
    static final String[] MTN_SANDBOX = {"mobile-money.mtn-momo.environment=sandbox",
        "mobile-money.mtn-momo.collection.subscription-key=CANARY-SUB-0005",
        "mobile-money.mtn-momo.collection.api-user=CANARY-USER-0006",
        "mobile-money.mtn-momo.collection.api-key=CANARY-KEY-0007"};

    private static String[] plus(String[] base, String... more) {
        String[] all = java.util.Arrays.copyOf(base, base.length + more.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    private static void failsWith(AssertableApplicationContext context, String message) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause().isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(message).hasMessageNotContaining("CANARY");
    }

    @Test
    void simulationStartsWithInMemoryStores() {
        runner.withPropertyValues("mobile-money.simulation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MobileMoney.class).providerIds()).containsExactly(ProviderId.SIMULATION);
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(InMemoryIdempotencyStore.class);
            assertThat(context).hasSingleBean(MobileMoneyWebhooks.class)
                    .doesNotHaveBean(PendingTransactionChecker.class)
                    .doesNotHaveBean(WebhookController.class);
        });
    }

    @Test
    void nothingConfiguredPreventsStartup() {
        runner.run(context -> failsWith(context, "No Mobile Money provider is configured"));
    }

    @Test
    void disabledIntegrationCreatesNothing() {
        runner.withPropertyValues("mobile-money.enabled=false").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(MobileMoney.class));
    }

    @Test
    void providerEnabledWithoutKeyPreventsStartup() {
        runner.withPropertyValues("mobile-money.campay.environment=sandbox", "mobile-money.campay.username=u")
                .run(context -> failsWith(context, "mobile-money.campay.password is not set"));
        runner.withPropertyValues("mobile-money.campay.enabled=true")
                .run(context -> failsWith(context, "mobile-money.campay.environment"));
        runner.withPropertyValues("mobile-money.mtn-momo.environment=sandbox",
                "mobile-money.mtn-momo.collection.api-key=CANARY-KEY-0007")
                .run(context -> failsWith(context, "mobile-money.mtn-momo.collection.subscription-key is not set"));
        runner.withPropertyValues("mobile-money.notchpay.webhook-secret=CANARY-SECRET-0008")
                .run(context -> failsWith(context, "mobile-money.notchpay.public-key is not set"));
        runner.withPropertyValues(plus(MTN_SANDBOX, "mobile-money.mtn-momo.currency=euro"))
                .run(context -> failsWith(context, "mobile-money.mtn-momo.currency is not an ISO 4217"));
        runner.withPropertyValues("mobile-money.tara.api-key=CANARY-KEY-0009")
                .run(context -> failsWith(context, "mobile-money.tara.business-id is not set"));
    }

    @Test
    void explicitlyDisabledProviderIsIgnored() {
        runner.withPropertyValues(plus(NOTCHPAY, "mobile-money.campay.enabled=false",
                "mobile-money.campay.username=u")).run(context ->
                assertThat(context.getBean(MobileMoney.class).providerIds()).containsExactly(ProviderId.NOTCHPAY));
    }

    @Test
    void unknownEnvironmentPreventsStartup() {
        runner.withPropertyValues(plus(CAMPAY, "mobile-money.campay.environment=staging")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(context.getStartupFailure()).isInstanceOfAny(BindException.class, RuntimeException.class);
        });
    }

    @Test
    void severalProvidersNeedADefault() {
        runner.withPropertyValues(plus(CAMPAY, NOTCHPAY))
                .run(context -> failsWith(context, "set mobile-money.default-provider"));
        runner.withPropertyValues(plus(plus(CAMPAY, NOTCHPAY), "mobile-money.default-provider=tara"))
                .run(context -> failsWith(context, "mobile-money.default-provider is tara, which is not enabled"));
        runner.withPropertyValues(plus(plus(CAMPAY, NOTCHPAY), "mobile-money.default-provider=notchpay",
                "mobile-money.failover=campay")).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MobileMoney.class).providerIds())
                            .containsExactlyInAnyOrder(ProviderId.CAMPAY, ProviderId.NOTCHPAY);
                });
    }

    @Test
    void failoverIsChecked() {
        runner.withPropertyValues(plus(CAMPAY, "mobile-money.failover=notchpay"))
                .run(context -> failsWith(context, "mobile-money.failover lists notchpay, which is not enabled"));
        runner.withPropertyValues(plus(CAMPAY, "mobile-money.failover=campay"))
                .run(context -> failsWith(context, "lists the default provider"));
        runner.withPropertyValues(plus(plus(CAMPAY, NOTCHPAY), "mobile-money.default-provider=campay",
                "mobile-money.failover=notchpay,notchpay")).run(context -> failsWith(context, "twice"));
    }

    @Test
    void simulationCannotBeMixedWithAProvider() {
        runner.withPropertyValues(plus(CAMPAY, "mobile-money.simulation.enabled=true"))
                .run(context -> failsWith(context, "cannot be combined"));
    }

    @Test
    void taraNeedsACallbackBaseUrl() {
        runner.withPropertyValues("mobile-money.tara.api-key=k", "mobile-money.tara.business-id=b")
                .run(context -> failsWith(context, "Tara needs mobile-money.callback-base-url"));
        runner.withPropertyValues("mobile-money.tara.api-key=k", "mobile-money.tara.business-id=b",
                "mobile-money.callback-base-url=https://shop.example/mobile-money/webhooks")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://localhost/mobile-money/webhooks", "https://127.0.0.1/mobile-money/webhooks",
        "https://192.168.1.10/mobile-money/webhooks", "https://10.0.0.2/mobile-money/webhooks",
        "https://[::1]/mobile-money/webhooks", "https://shop.local/mobile-money/webhooks",
        "http://shop.example/mobile-money/webhooks"})
    void localOrPlainCallbackUrlIsRefusedInProduction(String url) {
        runner.withPropertyValues("mobile-money.mtn-momo.environment=production",
                "mobile-money.mtn-momo.target-environment=mtncameroon",
                "mobile-money.mtn-momo.collection.subscription-key=s", "mobile-money.mtn-momo.collection.api-user=u",
                "mobile-money.mtn-momo.collection.api-key=k", "mobile-money.callback-base-url=" + url)
                .run(context -> failsWith(context, "must be a public HTTPS URL in production"));
    }

    @Test
    void localCallbackUrlIsAcceptedInTheSandbox() {
        runner.withPropertyValues(plus(MTN_SANDBOX, "mobile-money.callback-base-url=http://localhost:8080/mobile-money/webhooks"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void mtnProductionNeedsATargetEnvironment() {
        runner.withPropertyValues("mobile-money.mtn-momo.environment=production",
                "mobile-money.mtn-momo.collection.subscription-key=s", "mobile-money.mtn-momo.collection.api-user=u",
                "mobile-money.mtn-momo.collection.api-key=k")
                .run(context -> failsWith(context, "mobile-money.mtn-momo.target-environment"));
    }

    @Test
    void redisStoreWithoutRedisPreventsStartup() {
        runner.withPropertyValues("mobile-money.simulation.enabled=true", "mobile-money.store=redis")
                .run(context -> failsWith(context, "mobile-money.store=redis but no Redis connection"));
    }

    @Test
    void badSettingsPreventStartup() {
        runner.withPropertyValues("mobile-money.simulation.enabled=true", "mobile-money.webhook.path=hooks")
                .run(context -> failsWith(context, "mobile-money.webhook.path"));
        runner.withPropertyValues("mobile-money.simulation.enabled=true", "mobile-money.pending-check.interval=0s")
                .run(context -> failsWith(context, "mobile-money.pending-check"));
        runner.withPropertyValues("mobile-money.simulation.enabled=true", "mobile-money.http.request-timeout=0s")
                .run(context -> failsWith(context, "mobile-money.http"));
    }

    @Test
    void emptyValuesFromUnsetEnvironmentVariablesMeanUnset() {
        runner.withPropertyValues("mobile-money.simulation.enabled=true", "mobile-money.callback-base-url=",
                "mobile-money.campay.environment=", "mobile-money.campay.username=", "mobile-money.campay.password=",
                "mobile-money.mtn-momo.environment=", "mobile-money.mtn-momo.currency=",
                "mobile-money.mtn-momo.target-environment=sandbox", "mobile-money.notchpay.public-key=",
                "mobile-money.mtn-momo.collection.subscription-key=", "mobile-money.mtn-momo.collection.api-user=",
                "mobile-money.mtn-momo.collection.api-key=",
                "mobile-money.tara.api-key=", "mobile-money.tara.base-url=", "mobile-money.default-provider=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MobileMoney.class).providerIds()).containsExactly(ProviderId.SIMULATION);
                });
    }

    @Test
    void applicationProvidersAreUsed() {
        runner.withUserConfiguration(CustomProvider.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MobileMoney.class).providerIds()).containsExactly(TestProvider.ID);
        });
    }

    @Test
    void pendingCheckCreatesItsTaskAndStore() {
        runner.withUserConfiguration(CustomProvider.class)
                .withPropertyValues("mobile-money.pending-check.enabled=true").run(context ->
                        assertThat(context).hasSingleBean(PendingTransactionChecker.class)
                                .hasSingleBean(PendingTransactionStore.class));
    }

    @Test
    void webhookEndpointIsRegisteredInWebApplications() {
        new WebApplicationContextRunner().withConfiguration(AUTO)
                .withPropertyValues("mobile-money.simulation.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(WebhookController.class));
        new WebApplicationContextRunner().withConfiguration(AUTO)
                .withPropertyValues("mobile-money.simulation.enabled=true", "mobile-money.webhook.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(WebhookController.class));
    }

    @Test
    void propertiesNeverShowSecrets() {
        runner.withPropertyValues(plus(plus(CAMPAY, NOTCHPAY), MTN_SANDBOX))
                .withPropertyValues("mobile-money.default-provider=campay", "mobile-money.tara.api-key=CANARY-0010",
                        "mobile-money.tara.business-id=b", "mobile-money.callback-base-url=https://shop.example/x")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MobileMoneyProperties.class).toString()).doesNotContain("CANARY");
                    assertThat(context.getBean(MobileMoney.class).provider(ProviderId.CAMPAY).toString())
                            .doesNotContain("CANARY");
                });
    }

    @Test
    void failureAnalyzerExplainsTheProblem() {
        var analysis = new MobileMoneyFailureAnalyzer().analyze(new IllegalStateException("x",
                new ConfigurationException("mobile-money.campay.password is not set", ProviderId.CAMPAY)));
        assertThat(analysis.getDescription()).contains("mobile-money.campay.password is not set");
        assertThat(analysis.getAction()).contains("mobile-money.*");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "api.localhost", "printer.local", "10.1.2.3", "172.16.0.1", "192.168.0.1",
        "127.0.0.1", "169.254.1.1", "::1", "[::1]", "fd00::1", "fe80::1", "0.0.0.0"})
    void localAddressesAreRecognized(String host) {
        assertThat(ConfiguredProviders.isLocal(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"shop.example", "8.8.8.8", "172.32.0.1", "2001:db8::1", "local.shop.example"})
    void publicAddressesAreRecognized(String host) {
        assertThat(ConfiguredProviders.isLocal(host)).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomProvider {

        @Bean
        PaymentProvider testProvider() {
            return new TestProvider();
        }
    }
}
