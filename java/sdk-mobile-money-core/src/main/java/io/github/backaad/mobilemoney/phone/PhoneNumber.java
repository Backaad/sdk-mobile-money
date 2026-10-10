package io.github.backaad.mobilemoney.phone;

import io.github.backaad.mobilemoney.InvalidPhoneNumberException;
import io.github.backaad.mobilemoney.InvalidPhoneNumberException.Reason;
import java.util.Objects;

/**
 * A normalized mobile phone number.
 *
 * <p>Only Cameroon (country code 237) is supported for now: a national number has 9 digits and mobile
 * numbers start with 6. {@link #parse(String)} accepts the usual written forms, for example
 * {@code +237 6 70 12 34 56}, {@code 00237670123456}, {@code 237670123456} or {@code 670123456}, with
 * spaces, dots, dashes, slashes or parentheses.
 *
 * <p>{@link #toString()} masks the middle digits, so that a number logged by mistake does not leak in
 * full. Use {@link #e164()} to get the complete number.
 */
public final class PhoneNumber {

    /** Country code of Cameroon. */
    public static final String CAMEROON = "237";

    private static final int MAX_INPUT_LENGTH = 40;
    private static final int NATIONAL_LENGTH = 9;
    private static final String EXPECTED =
            "expected a Cameroon mobile number: 9 digits starting with 6, optionally preceded by +237 or 00237";

    private final String countryCode;
    private final String nationalNumber;

    private PhoneNumber(String countryCode, String nationalNumber) {
        this.countryCode = countryCode;
        this.nationalNumber = nationalNumber;
    }

    /**
     * Parses and normalizes a phone number.
     *
     * @param raw the number as typed by a user
     * @return the normalized number
     * @throws InvalidPhoneNumberException if it is not a valid Cameroon mobile number
     */
    public static PhoneNumber parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid(Reason.MISSING, "Phone number is missing");
        }
        if (raw.length() > MAX_INPUT_LENGTH) {
            throw invalid(Reason.WRONG_LENGTH, "Phone number is too long");
        }
        String compact = stripSeparators(raw);

        String digits;
        boolean international;
        if (compact.startsWith("+")) {
            digits = compact.substring(1);
            international = true;
        } else if (compact.startsWith("00")) {
            digits = compact.substring(2);
            international = true;
        } else {
            digits = compact;
            international = false;
        }
        if (digits.isEmpty() || !isAsciiDigits(digits)) {
            throw invalid(Reason.INVALID_CHARACTERS, "Phone number contains invalid characters");
        }

        String national;
        if (international) {
            if (!digits.startsWith(CAMEROON)) {
                throw invalid(Reason.UNSUPPORTED_COUNTRY, "Unsupported country code");
            }
            national = digits.substring(CAMEROON.length());
        } else if (digits.length() == CAMEROON.length() + NATIONAL_LENGTH && digits.startsWith(CAMEROON)) {
            national = digits.substring(CAMEROON.length());
        } else {
            national = digits;
        }

        if (national.length() != NATIONAL_LENGTH) {
            throw invalid(Reason.WRONG_LENGTH, "Phone number has the wrong length");
        }
        if (national.charAt(0) != '6') {
            throw invalid(Reason.NOT_MOBILE, "Not a mobile number");
        }
        return new PhoneNumber(CAMEROON, national);
    }

    private static InvalidPhoneNumberException invalid(Reason reason, String problem) {
        return new InvalidPhoneNumberException(reason, problem + ": " + EXPECTED);
    }

    private static String stripSeparators(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)
                    || c == '.' || c == '-' || c == '/' || c == '(' || c == ')') {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isAsciiDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the country calling code, without {@code +}.
     *
     * @return for example {@code "237"}
     */
    public String countryCode() {
        return countryCode;
    }

    /**
     * Returns the national number.
     *
     * @return for example {@code "670123456"}
     */
    public String nationalNumber() {
        return nationalNumber;
    }

    /**
     * Returns the number in E.164 format.
     *
     * @return for example {@code "+237670123456"}
     */
    public String e164() {
        return "+" + countryCode + nationalNumber;
    }

    /**
     * Returns the number with its middle digits hidden, for logs and diagnostics.
     *
     * @return for example {@code "+2376******56"}
     */
    public String masked() {
        int n = nationalNumber.length();
        return "+" + countryCode + nationalNumber.charAt(0) + "*".repeat(n - 3) + nationalNumber.substring(n - 2);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PhoneNumber other
                && countryCode.equals(other.countryCode)
                && nationalNumber.equals(other.nationalNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(countryCode, nationalNumber);
    }

    /** Returns the masked number, see {@link #masked()}. */
    @Override
    public String toString() {
        return masked();
    }
}
