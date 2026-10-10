package io.github.backaad.mobilemoney.provider.mtnmomo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionNotFoundException;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.provider.StatusReport;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs against the public MTN MoMo sandbox (https://sandbox.momodeveloper.mtn.com) with the developer's own
 * collection credentials, read from the environment (see .env.example). Without them, the tests are
 * reported as skipped, never failed. The project has no keys of its own, so these tests have never been
 * run by the CI. Any error fails them.
 */
@EnabledIfEnvironmentVariable(named = "MTN_MOMO_COLLECTION_SUBSCRIPTION_KEY", matches = "\\S+")
@EnabledIfEnvironmentVariable(named = "MTN_MOMO_COLLECTION_API_USER", matches = "\\S+")
@EnabledIfEnvironmentVariable(named = "MTN_MOMO_COLLECTION_API_KEY", matches = "\\S+")
class MtnMomoSandboxTest {

    /** The sandbox only accepts EUR (to be confirmed); MTN_MOMO_CURRENCY overrides it. */
    private static final Currency CURRENCY = Currency.getInstance(
            Objects.requireNonNullElse(blankToNull(System.getenv("MTN_MOMO_CURRENCY")), "EUR"));

    private static MtnMomoProvider sandbox() {
        return MtnMomoProvider.builder()
                .environment(MtnMomoEnvironment.SANDBOX)
                .collection(System.getenv("MTN_MOMO_COLLECTION_SUBSCRIPTION_KEY"),
                        System.getenv("MTN_MOMO_COLLECTION_API_USER"), System.getenv("MTN_MOMO_COLLECTION_API_KEY"))
                .currency(CURRENCY)
                .build();
    }

    @Test
    void requestToPayIsAcceptedAndItsStatusCanBeRead() {
        MtnMomoProvider provider = sandbox();
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).build();
        String reference = "sdk-sandbox-" + UUID.randomUUID();

        // The sandbox does not debit anyone; the number only has to be well formed (to be confirmed).
        Transaction transaction = mobileMoney.collect(PaymentRequest.builder()
                .idempotencyKey(reference)
                .amount(5)
                .currency(CURRENCY)
                .phoneNumber("670000000")
                .merchantReference(reference)
                .description("sdk-mobile-money sandbox test")
                .build());

        assertThat(transaction.provider()).isEqualTo(ProviderId.MTN_MOMO);
        assertThat(transaction.outcomeUnknown()).as("the sandbox must answer").isFalse();
        StatusReport report = provider.fetchStatus(TransactionType.COLLECTION, transaction.providerReference());
        assertThat(report.providerReference()).isEqualTo(transaction.providerReference());
        assertThat(report.rawStatus()).isNotBlank();
    }

    @Test
    void unknownReferenceIsNotFound() {
        assertThatThrownBy(() -> sandbox().fetchStatus(TransactionType.COLLECTION, UUID.randomUUID().toString()))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
