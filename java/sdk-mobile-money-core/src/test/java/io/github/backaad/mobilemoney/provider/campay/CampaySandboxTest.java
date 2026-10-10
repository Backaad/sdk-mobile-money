package io.github.backaad.mobilemoney.provider.campay;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.internal.HttpCaller;
import io.github.backaad.mobilemoney.provider.StatusReport;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs against the real Campay sandbox (https://demo.campay.net) with the developer's own keys, read from
 * the environment (see .env.example). Without them, the tests are reported as skipped, never failed. The
 * project has no keys of its own, so these tests have never been run by the CI. Any error fails them.
 */
@EnabledIfEnvironmentVariable(named = "CAMPAY_USERNAME", matches = "\\S+")
@EnabledIfEnvironmentVariable(named = "CAMPAY_PASSWORD", matches = "\\S+")
class CampaySandboxTest {

    private static CampayProvider sandbox() {
        return CampayProvider.builder()
                .environment(CampayEnvironment.SANDBOX)
                .username(System.getenv("CAMPAY_USERNAME"))
                .password(System.getenv("CAMPAY_PASSWORD"))
                .build();
    }

    @Test
    void sandboxGivesAnAccessToken() {
        CampayTokens tokens = new CampayTokens(
                new HttpCaller(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                        ProviderId.CAMPAY),
                URI.create(CampayEnvironment.SANDBOX.baseUrl() + "/api/token/"),
                System.getenv("CAMPAY_USERNAME"), System.getenv("CAMPAY_PASSWORD"),
                Clock.systemUTC(), Duration.ofSeconds(30));

        assertThat(tokens.get()).isNotBlank();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CAMPAY_SANDBOX_PHONE_NUMBER", matches = "\\S+")
    void collectionStartsAndItsStatusCanBeRead() {
        CampayProvider provider = sandbox();
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).build();
        String reference = "sdk-sandbox-" + UUID.randomUUID();

        Transaction transaction = mobileMoney.collect(PaymentRequest.builder()
                .idempotencyKey(reference)
                .amount(5)
                .phoneNumber(System.getenv("CAMPAY_SANDBOX_PHONE_NUMBER"))
                .merchantReference(reference)
                .description("sdk-mobile-money sandbox test")
                .build());

        assertThat(transaction.provider()).isEqualTo(ProviderId.CAMPAY);
        assertThat(transaction.outcomeUnknown()).as("Campay answered with a reference").isFalse();
        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);

        // Read the status from the provider directly: any error must fail the test.
        StatusReport report = provider.fetchStatus(TransactionType.COLLECTION, transaction.providerReference());
        assertThat(report.providerReference()).isEqualTo(transaction.providerReference());
        assertThat(report.merchantReference()).isEqualTo(reference);
        assertThat(report.rawStatus()).isNotBlank();
    }
}
