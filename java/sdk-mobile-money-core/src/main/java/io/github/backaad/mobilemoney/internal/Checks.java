package io.github.backaad.mobilemoney.internal;

import java.util.regex.Pattern;

/** Argument checks shared by the model types. */
public final class Checks {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,255}");

    private Checks() {
    }

    /**
     * Checks that a text is present, not too long and free of control characters.
     *
     * @param value the value
     * @param name name used in the error message
     * @param maxLength maximum length
     * @return the value
     * @throws IllegalArgumentException if the value is null, blank, too long or contains control characters
     */
    public static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException(name + " must not contain control characters");
            }
        }
        return value;
    }

    /**
     * Checks an idempotency key: 1 to 255 characters among letters, digits and {@code . _ : -}, so that
     * it can be logged and used as a storage key safely.
     *
     * @param key the key
     * @return the key
     * @throws IllegalArgumentException if the key is missing or has another character
     */
    public static String requireIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "idempotencyKey is required: 1 to 255 characters among letters, digits and . _ : -");
        }
        return key;
    }
}
