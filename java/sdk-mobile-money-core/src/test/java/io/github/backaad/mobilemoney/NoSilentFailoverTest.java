package io.github.backaad.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.testing.StubProvider;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Rule 11: no silent failover to another provider. Without configuration, an unavailable provider is
 * reported as such. With {@code failover(...)} configured by the merchant, the next provider is tried only
 * when nothing reached the previous one, it is logged, and the result says which provider processed it.
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

    private static PaymentRequest request(String key) {
        return PaymentRequest.builder().idempotencyKey(key).amount(100).phoneNumber("670123456")
                .merchantReference("order-1").description("d").build();
    }

    private static StubProvider unreachable(String id) {
        return new StubProvider(id).onCollect(request -> {
            throw new ProviderUnavailableException(id + " could not be reached", new ProviderId(id), false, null);
        });
    }

    @Test
    void configuredFailoverIsUsedWhenNothingWasSentAndTheResultSaysWhichProviderProcessedIt() {
        StubProvider primary = unreachable("campay");
        StubProvider backup = new StubProvider("notchpay");
        MobileMoney mobileMoney = MobileMoney.builder().provider(primary).provider(backup)
                .failover(ProviderId.NOTCHPAY).build();

        Transaction transaction = mobileMoney.collect(request("k"));

        assertThat(transaction.provider()).isEqualTo(ProviderId.NOTCHPAY);
        assertThat(primary.collects()).isEqualTo(1);
        assertThat(backup.collects()).isEqualTo(1);
        // The replay returns the same result, from the same provider, without sending anything again.
        assertThat(mobileMoney.collect(request("k"))).isEqualTo(transaction);
        assertThat(backup.collects()).isEqualTo(1);
    }

    @Test
    void failoverNeverHappensWhenTheRequestMayHaveBeenProcessed() {
        StubProvider primary = new StubProvider("campay").onCollect(request -> {
            throw new ProviderUnavailableException("timeout", ProviderId.CAMPAY, true, null);
        });
        StubProvider backup = new StubProvider("notchpay");
        MobileMoney mobileMoney = MobileMoney.builder().provider(primary).provider(backup)
                .failover(ProviderId.NOTCHPAY).build();

        Transaction transaction = mobileMoney.collect(request("k"));

        assertThat(transaction.provider()).isEqualTo(ProviderId.CAMPAY);
        assertThat(transaction.outcomeUnknown()).isTrue();
        assertThat(backup.collects()).isZero();
    }

    @Test
    void failoverNeverHappensAfterARefusal() {
        StubProvider primary = new StubProvider("campay").onCollect(request -> {
            throw new PaymentRejectedException("Insufficient balance", ProviderId.CAMPAY, "ER101");
        });
        StubProvider backup = new StubProvider("notchpay");
        MobileMoney mobileMoney = MobileMoney.builder().provider(primary).provider(backup)
                .failover(ProviderId.NOTCHPAY).build();

        assertThatThrownBy(() -> mobileMoney.collect(request("k"))).isInstanceOf(PaymentRejectedException.class);
        assertThat(backup.collects()).isZero();
    }

    @Test
    void failoverProvidersAreTriedInTheConfiguredOrderAndTheLastErrorIsReported() {
        StubProvider primary = unreachable("campay");
        StubProvider second = unreachable("tara");
        StubProvider third = unreachable("notchpay");
        MobileMoney mobileMoney = MobileMoney.builder().provider(primary).provider(third).provider(second)
                .failover(ProviderId.TARA, ProviderId.NOTCHPAY).build();

        assertThatThrownBy(() -> mobileMoney.collect(request("k")))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.provider()).contains(ProviderId.NOTCHPAY));
        assertThat(second.collects()).isEqualTo(1);
        assertThat(third.collects()).isEqualTo(1);
        // Nothing was sent anywhere: the key is released, and a retry starts again with the default provider.
        assertThatThrownBy(() -> mobileMoney.collect(request("k"))).isInstanceOf(ProviderUnavailableException.class);
        assertThat(primary.collects()).isEqualTo(2);
    }

    @Test
    void failoverProviderThatCannotHandleTheCurrencyIsSkipped() {
        StubProvider primary = unreachable("campay");
        StubProvider xofOnly = new StubProvider("notchpay").capabilities(new ProviderCapabilities(true, true, true,
                WebhookSignature.NONE, Set.of(Currencies.XOF)));
        MobileMoney mobileMoney = MobileMoney.builder().provider(primary).provider(xofOnly)
                .failover(ProviderId.NOTCHPAY).build();

        assertThatThrownBy(() -> mobileMoney.collect(request("k"))).isInstanceOf(ProviderUnavailableException.class);
        assertThat(xofOnly.collects()).isZero();
    }

    @Test
    void failoverConfigurationIsChecked() {
        StubProvider campay = new StubProvider("campay");
        StubProvider notchpay = new StubProvider("notchpay");

        assertThatThrownBy(() -> MobileMoney.builder().provider(campay).failover(ProviderId.NOTCHPAY).build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("not configured");
        assertThatThrownBy(() -> MobileMoney.builder().provider(campay).failover(ProviderId.CAMPAY).build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("its own failover");
        assertThatThrownBy(() -> MobileMoney.builder().provider(campay).provider(notchpay)
                .failover(ProviderId.NOTCHPAY, ProviderId.NOTCHPAY).build())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("twice");
    }
}
