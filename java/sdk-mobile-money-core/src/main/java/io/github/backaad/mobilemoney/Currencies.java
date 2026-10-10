package io.github.backaad.mobilemoney;

import java.util.Currency;

/**
 * Currencies used with Mobile Money. Amounts are always whole units of the currency.
 */
public final class Currencies {

    /** Central African CFA franc (Cameroon). */
    public static final Currency XAF = Currency.getInstance("XAF");

    /** West African CFA franc. Planned: no provider supports it yet. */
    public static final Currency XOF = Currency.getInstance("XOF");

    private Currencies() {
    }
}
