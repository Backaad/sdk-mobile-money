package io.github.backaad.mobilemoney.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Constant-time comparisons. Every signature or token comparison in the SDK goes through this class, so
 * that the time taken does not reveal how many leading bytes matched.
 */
public final class ConstantTime {

    private ConstantTime() {
    }

    /**
     * Compares two byte arrays in time that depends only on their lengths.
     *
     * @param a first value, may be {@code null}
     * @param b second value, may be {@code null}
     * @return {@code true} if both are non-null and equal
     */
    public static boolean equals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a, b);
    }

    /**
     * Compares two strings, as UTF-8 bytes, in time that depends only on their lengths.
     *
     * @param a first value, may be {@code null}
     * @param b second value, may be {@code null}
     * @return {@code true} if both are non-null and equal
     */
    public static boolean equals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return equals(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
