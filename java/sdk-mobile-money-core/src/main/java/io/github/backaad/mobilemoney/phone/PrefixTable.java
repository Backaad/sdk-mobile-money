package io.github.backaad.mobilemoney.phone;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Maps the first three digits of a Cameroon mobile number to its operator.
 *
 * <p>Detection by prefix is only a hint: a number keeps its prefix when its owner moves to another
 * operator (number portability), and since 2023 an Orange Money wallet can be opened on another
 * operator's SIM card. It is used to pre-select an operator, never to refuse a number.
 *
 * <p>The default table must be checked against the numbering plan published by ART (Agence de
 * Régulation des Télécommunications); it can be replaced with {@link #builder()}.
 */
public final class PrefixTable {

    private final Map<String, MobileOperator> operatorsByPrefix;

    private PrefixTable(Map<String, MobileOperator> operatorsByPrefix) {
        this.operatorsByPrefix = Collections.unmodifiableMap(new TreeMap<>(operatorsByPrefix));
    }

    /**
     * Returns the default table for Cameroon: MTN 650-654, 670-679, 680-683; Orange 655-659, 690-699.
     *
     * <p>Only the blocks on which every source agrees are included. 684-689 (MTN, Nexttel or Orange
     * depending on the source) and 640-642 (Orange according to some sources) are left out until they are
     * confirmed against the numbering plan of ART, the Cameroon regulator: numbers in those blocks are
     * accepted, but their operator is not guessed.
     *
     * @return the default table
     */
    public static PrefixTable cameroonDefault() {
        return builder()
                .range(MobileOperator.MTN, 650, 654)
                .range(MobileOperator.MTN, 670, 679)
                .range(MobileOperator.MTN, 680, 683)
                .range(MobileOperator.ORANGE, 655, 659)
                .range(MobileOperator.ORANGE, 690, 699)
                .build();
    }

    /**
     * Starts an empty table.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Guesses the operator of a number from its prefix.
     *
     * @param number a normalized number
     * @return the operator, or empty when the prefix is not in the table
     */
    public Optional<MobileOperator> operatorOf(PhoneNumber number) {
        Objects.requireNonNull(number, "number");
        if (!PhoneNumber.CAMEROON.equals(number.countryCode())) {
            return Optional.empty();
        }
        return Optional.ofNullable(operatorsByPrefix.get(number.nationalNumber().substring(0, 3)));
    }

    /**
     * Returns the table content.
     *
     * @return three-digit prefixes mapped to operators, sorted by prefix
     */
    public Map<String, MobileOperator> prefixes() {
        return operatorsByPrefix;
    }

    /** Builds a {@link PrefixTable}. */
    public static final class Builder {

        private final Map<String, MobileOperator> operatorsByPrefix = new TreeMap<>();

        private Builder() {
        }

        /**
         * Assigns a range of three-digit prefixes to an operator.
         *
         * @param operator the operator
         * @param first first prefix, for example {@code 650}
         * @param last last prefix, inclusive
         * @return this builder
         * @throws IllegalArgumentException if a prefix is not a mobile prefix (600-699), if
         *     {@code first > last}, or if a prefix is already assigned to another operator
         */
        public Builder range(MobileOperator operator, int first, int last) {
            Objects.requireNonNull(operator, "operator");
            if (first < 600 || last > 699 || first > last) {
                throw new IllegalArgumentException(
                        "Invalid prefix range " + first + "-" + last + ": prefixes go from 600 to 699");
            }
            for (int prefix = first; prefix <= last; prefix++) {
                String key = Integer.toString(prefix);
                MobileOperator previous = operatorsByPrefix.get(key);
                if (previous != null && previous != operator) {
                    throw new IllegalArgumentException(
                            "Prefix " + key + " is assigned to both " + previous + " and " + operator);
                }
                operatorsByPrefix.put(key, operator);
            }
            return this;
        }

        /**
         * Builds the table.
         *
         * @return an immutable table
         */
        public PrefixTable build() {
            return new PrefixTable(operatorsByPrefix);
        }
    }
}
