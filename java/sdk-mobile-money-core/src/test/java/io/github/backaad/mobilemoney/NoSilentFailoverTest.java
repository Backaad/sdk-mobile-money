package io.github.backaad.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.testing.StubProvider;
import org.junit.jupiter.api.Test;

/**
 * Rule 11: no silent failover to another provider. Configurable failover comes in a later step; until
 * then, an unavailable provider is reported as such, and another configured provider is never used in its
 * place.
 */
class NoSilentFailoverTest {

    @Test
    void unavailablePrimaryIsReportedAndNoOtherProviderIsUsed() {
        StubProvider primary = new StubProvider("campay").onCollect(request -> {
            throw new ProviderUnavailableException("campay could not be reached", ProviderId.CAMPAY, false, null);
        });
        StubProvider other = new StubProvider("notchpay");
        MobileMoney mobileMoney = MobileMoney.builder().provider(primary).provider(other).build();

        assertThatThrownBy(() -> mobileMoney.collect(PaymentRequest.builder().idempotencyKey("k").amount(100)
                .phoneNumber("670123456").merchantReference("order-1").description("d").build()))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.provider()).contains(ProviderId.CAMPAY));
        assertThat(primary.collects()).isEqualTo(1);
        assertThat(other.collects()).isZero();
    }

    @Test
    void resultSaysWhichProviderProcessedThePayment() {
        StubProvider primary = new StubProvider("campay");
        MobileMoney mobileMoney = MobileMoney.builder().provider(primary).provider(new StubProvider("notchpay")).build();

        Transaction transaction = mobileMoney.collect(PaymentRequest.builder().idempotencyKey("k").amount(100)
                .phoneNumber("670123456").merchantReference("order-1").description("d").build());

        assertThat(transaction.provider()).isEqualTo(ProviderId.CAMPAY);
    }

    @Test
    void statusIsReadFromTheProviderThatProcessedTheTransaction() {
        StubProvider campay = new StubProvider("campay");
        StubProvider notchpay = new StubProvider("notchpay");
        MobileMoney mobileMoney = MobileMoney.builder().provider(campay).provider(notchpay).build();
        Transaction processedByNotchPay = new Transaction(ProviderId.NOTCHPAY, TransactionType.COLLECTION, "ref-9",
                "order-1", 100, Currencies.XAF, TransactionStatus.PENDING, null);

        mobileMoney.status(processedByNotchPay);

        assertThat(notchpay.statusReads()).isEqualTo(1);
        assertThat(campay.statusReads()).isZero();
    }
}
