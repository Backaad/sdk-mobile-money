package io.github.backaad.mobilemoney;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifies a payment provider, for example {@link #CAMPAY}.
 *
 * <p>A value type rather than an enum, so that providers for other countries can be added
 * without changing this class.
 *
 * @param value lowercase identifier, such as {@code "campay"}
 */
public record ProviderId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    /** Campay (Cameroon). */
    public static final ProviderId CAMPAY = new ProviderId("campay");
    /** NotchPay. */
    public static final ProviderId NOTCHPAY = new ProviderId("notchpay");
    /** MTN Mobile Money, direct API. */
    public static final ProviderId MTN_MOMO = new ProviderId("mtn-momo");
    /** Tara Money (experimental). */
    public static final ProviderId TARA = new ProviderId("tara");
    /** Simulation mode: talks to nobody and never confirms a payment. */
    public static final ProviderId SIMULATION = new ProviderId("simulation");

    /**
     * Validates the identifier.
     *
     * @throws IllegalArgumentException if the value is not a lowercase identifier
     */
    public ProviderId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid provider id: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
