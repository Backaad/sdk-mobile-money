package io.github.backaad.mobilemoney.phone;

import io.github.backaad.mobilemoney.ProviderId;
import java.util.Objects;

/**
 * The phone number format each provider expects on the wire. Every provider adapter formats numbers
 * through this single place, so that the formats are defined and tested together.
 */
public enum ProviderPhoneFormat {

    /** Campay: country code and national number, without {@code +}, for example {@code 237670123456}. */
    CAMPAY(ProviderId.CAMPAY) {
        @Override
        public String format(PhoneNumber number) {
            return Objects.requireNonNull(number, "number").countryCode() + number.nationalNumber();
        }
    };

    private final ProviderId provider;

    ProviderPhoneFormat(ProviderId provider) {
        this.provider = provider;
    }

    /**
     * Returns the provider this format belongs to.
     *
     * @return the provider
     */
    public ProviderId provider() {
        return provider;
    }

    /**
     * Formats a number for this provider.
     *
     * @param number a normalized number
     * @return the number as the provider expects it
     */
    public abstract String format(PhoneNumber number);
}
