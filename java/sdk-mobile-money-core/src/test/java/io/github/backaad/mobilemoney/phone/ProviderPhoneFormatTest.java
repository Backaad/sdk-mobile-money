package io.github.backaad.mobilemoney.phone;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.backaad.mobilemoney.ProviderId;
import org.junit.jupiter.api.Test;

/** Rule 6: each provider's number format is defined in one place and tested. */
class ProviderPhoneFormatTest {

    @Test
    void campayUsesCountryCodeWithoutPlus() {
        assertThat(ProviderPhoneFormat.CAMPAY.format(PhoneNumber.parse("+237 6 70 12 34 56")))
                .isEqualTo("237670123456");
        assertThat(ProviderPhoneFormat.CAMPAY.provider()).isEqualTo(ProviderId.CAMPAY);
    }

    @Test
    void everyFormatProducesOnlyDigitsOrPlus() {
        PhoneNumber number = PhoneNumber.parse("690123456");
        for (ProviderPhoneFormat format : ProviderPhoneFormat.values()) {
            assertThat(format.format(number)).matches("\\+?[0-9]+");
        }
    }
}
