package io.github.backaad.mobilemoney;

import java.util.Objects;

/** A phone number is not a valid mobile number for the supported countries. */
public class InvalidPhoneNumberException extends MobileMoneyException {

    private static final long serialVersionUID = 1L;

    /** Why a number was refused. The Dart package uses the same values. */
    public enum Reason {
        /** Nothing was given. */
        MISSING,
        /** Letters or other characters that are not digits or separators. */
        INVALID_CHARACTERS,
        /** A country code other than a supported one. */
        UNSUPPORTED_COUNTRY,
        /** Too few or too many digits. */
        WRONG_LENGTH,
        /** A valid number, but not a mobile number. */
        NOT_MOBILE
    }

    /** Why the number was refused. */
    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason why the number was refused
     * @param message what is wrong, without the number itself
     */
    public InvalidPhoneNumberException(Reason reason, String message) {
        super(message, null, null);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns why the number was refused.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }
}
