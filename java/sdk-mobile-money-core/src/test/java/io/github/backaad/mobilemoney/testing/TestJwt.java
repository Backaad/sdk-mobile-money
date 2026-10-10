package io.github.backaad.mobilemoney.testing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Builds JSON Web Tokens for tests. */
public final class TestJwt {

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private TestJwt() {
    }

    /** Signs {@code headerJson.payloadJson} with HMAC-SHA256. */
    public static String hs256(String headerJson, String payloadJson, String key) {
        String signingInput = encode(headerJson) + "." + encode(payloadJson);
        return signingInput + "." + BASE64URL.encodeToString(hmac(signingInput, key));
    }

    /** A standard HS256 token with the given payload. */
    public static String hs256(String payloadJson, String key) {
        return hs256("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", payloadJson, key);
    }

    /** An unsigned token ({@code alg: none}, empty signature). */
    public static String unsigned(String payloadJson) {
        return encode("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + encode(payloadJson) + ".";
    }

    public static String encode(String json) {
        return BASE64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
