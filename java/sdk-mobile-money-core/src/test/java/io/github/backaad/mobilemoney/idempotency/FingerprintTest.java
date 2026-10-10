package io.github.backaad.mobilemoney.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.PayoutRequest;
import org.junit.jupiter.api.Test;

class FingerprintTest {

    private static PaymentRequest.Builder payment() {
        return PaymentRequest.builder().idempotencyKey("k").amount(100).phoneNumber("670123456")
                .merchantReference("order-1").description("Order 1");
    }

    private static PayoutRequest.Builder payout() {
        return PayoutRequest.builder().idempotencyKey("k").amount(100).phoneNumber("670123456")
                .beneficiaryName("Jane").merchantReference("order-1").description("Order 1");
    }

    @Test
    void sameRequestWrittenDifferentlyHasTheSameFingerprint() {
        assertThat(Fingerprint.of(payment().phoneNumber("+237 6 70 12 34 56").description("Other text").build()))
                .isEqualTo(Fingerprint.of(payment().build()));
    }

    @Test
    void anyMeaningfulDifferenceChangesTheFingerprint() {
        String base = Fingerprint.of(payment().build());

        assertThat(Fingerprint.of(payment().amount(101).build())).isNotEqualTo(base);
        assertThat(Fingerprint.of(payment().phoneNumber("670123457").build())).isNotEqualTo(base);
        assertThat(Fingerprint.of(payment().merchantReference("order-2").build())).isNotEqualTo(base);
        assertThat(Fingerprint.of(payment().currency(io.github.backaad.mobilemoney.Currencies.XOF).build()))
                .isNotEqualTo(base);
        assertThat(Fingerprint.of(payout().build())).isNotEqualTo(base);
    }

    @Test
    void fieldsCannotBeShiftedIntoEachOther() {
        String a = Fingerprint.of(payout().merchantReference("A1").beneficiaryName("23").build());
        String b = Fingerprint.of(payout().merchantReference("A12").beneficiaryName("3").build());

        assertThat(a).isNotEqualTo(b);
    }
}
