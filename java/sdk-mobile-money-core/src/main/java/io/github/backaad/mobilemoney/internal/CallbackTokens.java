package io.github.backaad.mobilemoney.internal;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Creates unpredictable callback tokens and their digests. */
public final class CallbackTokens {

    /** Query parameter that carries the token in a callback URL. */
    public static final String PARAMETER = "mm_token";

    private static final SecureRandom RANDOM = new SecureRandom();

    private CallbackTokens() {
    }

    /**
     * Creates a 128-bit random token.
     *
     * @return the token, in base64url without padding
     */
    public static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Computes the digest under which a token is stored.
     *
     * @param token the token
     * @return the SHA-256 digest, in hexadecimal
     */
    public static String digest(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Adds the token to a URL as a query parameter.
     *
     * @param base the merchant's callback URL
     * @param token the token
     * @return the URL with the token
     */
    public static URI withToken(URI base, String token) {
        String text = base.toString();
        String separator = base.getRawQuery() == null ? "?" : "&";
        return URI.create(text + separator + PARAMETER + "=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
    }
}
