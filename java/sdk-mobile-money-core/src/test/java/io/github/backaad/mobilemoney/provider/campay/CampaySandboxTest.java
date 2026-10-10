package io.github.backaad.mobilemoney.provider.campay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.ProviderAuthenticationException;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs against the real Campay sandbox (https://demo.campay.net) with the developer's own keys, read from
 * the environment (see .env.example). Without them, the tests are reported as skipped, never failed. The
 * project has no keys of its own, so these tests have never been run by the CI.
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
    void credentialsAreAccepted() {
        CampayProvider provider = sandbox();

        // Reading an unknown transaction needs a valid token; the answer itself does not matter.
        assertThatCode(() -> {
            try {
                provider.fetchStatus(null, UUID.randomUUID().toString());
            } catch (ProviderAuthenticationException e) {
                throw e;
            } catch (RuntimeException expected) {
                // Not found or unavailable: the credentials were accepted.
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CAMPAY_SANDBOX_PHONE_NUMBER", matches = "\\S+")
    void collectionStartsAndItsStatusCanBeRead() {
        MobileMoney mobileMoney = MobileMoney.builder().provider(sandbox()).build();
        String reference = "sdk-sandbox-" + UUID.randomUUID();

        Transaction transaction = mobileMoney.collect(PaymentRequest.builder()
                .idempotencyKey(reference)
                .amount(5)
                .phoneNumber(System.getenv("CAMPAY_SANDBOX_PHONE_NUMBER"))
                .merchantReference(reference)
                .description("sdk-mobile-money sandbox test")
                .build());

        assertThat(transaction.provider()).isEqualTo(ProviderId.CAMPAY);
        assertThat(transaction.providerReference()).isNotBlank();
        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);

        Transaction current = mobileMoney.status(transaction);
        assertThat(current.status()).isIn(TransactionStatus.PENDING, TransactionStatus.SUCCESSFUL,
                TransactionStatus.FAILED);
    }
}
