package io.github.backaad.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.provider.campay.CampayEnvironment;
import io.github.backaad.mobilemoney.provider.campay.CampayProvider;
import io.github.backaad.mobilemoney.testing.StubProvider;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Rule 10: an incomplete or dangerous configuration is refused with a clear message. (Application startup
 * checks of the Spring Boot properties come with the starter.)
 */
class ConfigurationTest {

    private static CampayProvider.Builder campay() {
        return CampayProvider.builder().environment(CampayEnvironment.SANDBOX).username("user").password("pass");
    }

    @Test
    void campayNeedsAnEnvironment() {
        assertThatThrownBy(() -> campay().environment(null).build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("environment is required");
    }

    @Test
    void campayNeedsCredentials() {
        assertThatThrownBy(() -> campay().username(null).build())
                .isInstanceOf(ConfigurationException.class).hasMessage("Campay username is required");
        assertThatThrownBy(() -> campay().password("  ").build())
                .isInstanceOf(ConfigurationException.class).hasMessage("Campay password is required");
        assertThatThrownBy(() -> campay().webhookKey(" ").build())
                .isInstanceOf(ConfigurationException.class).hasMessage("Campay webhook key must not be blank");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://demo.campay.net",
        "http://localhost.attacker.example",
        "https://user:secret@demo.campay.net",
        "https://demo.campay.net/?debug=1",
        "ftp://demo.campay.net",
        "demo.campay.net"})
    void campayRefusesUnsafeBaseUrls(String url) {
        assertThatThrownBy(() -> campay().baseUrl(URI.create(url)).build())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageStartingWith("Campay base URL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://demo.campay.net/", "http://localhost:8089", "http://127.0.0.1:8089"})
    void campayAcceptsHttpsAndLocalTestServers(String url) {
        assertThat(campay().baseUrl(URI.create(url)).build()).isNotNull();
    }

    @Test
    void campayRefusesNonPositiveTimeouts() {
        assertThatThrownBy(() -> campay().requestTimeout(Duration.ZERO).build())
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void mobileMoneyNeedsAProvider() {
        assertThatThrownBy(() -> MobileMoney.builder().build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("At least one provider");
    }

    @Test
    void sameProviderCannotBeConfiguredTwice() {
        assertThatThrownBy(() -> MobileMoney.builder().provider(new StubProvider("campay"))
                .provider(new StubProvider("campay")))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("configured twice");
    }

    @Test
    void unsupportedCurrencyIsRefusedBeforeAnythingIsSent() {
        StubProvider stub = new StubProvider("stub");
        MobileMoney mobileMoney = MobileMoney.builder().provider(stub).build();

        assertThatThrownBy(() -> mobileMoney.collect(PaymentRequest.builder().idempotencyKey("k").amount(1)
                .currency(Currencies.XOF).phoneNumber("670123456").merchantReference("o").description("d").build()))
                .isInstanceOf(UnsupportedCapabilityException.class).hasMessageContaining("XOF");
        assertThat(stub.collects()).isZero();
    }
}
