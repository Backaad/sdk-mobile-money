package io.github.backaad.mobilemoney.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Verifies JSON Web Tokens signed with HMAC-SHA256 (RFC 7515 and RFC 7519), without any JWT library.
 *
 * <p>Only {@code HS256} is accepted, so unsigned tokens ({@code alg: none}) and algorithm confusion are
 * rejected. Signatures are compared in constant time. Expired tokens are rejected.
 */
public final class Hs256Jwt {

    private static final Base64.Decoder BASE64URL = Base64.getUrlDecoder();
    private static final Base64.Encoder BASE64URL_NO_PADDING = Base64.getUrlEncoder().withoutPadding();
    private static final int MAX_TOKEN_LENGTH = 4096;

    private final byte[] key;
    private final Clock clock;
    private final Duration clockSkew;
    private final boolean expirationRequired;

    /**
     * Creates a verifier.
     *
     * @param key shared secret
     * @param clock clock used to check {@code exp} and {@code nbf}
     * @param clockSkew tolerance applied to {@code exp} and {@code nbf}
     * @param expirationRequired whether a token without {@code exp} is rejected
     */
    public Hs256Jwt(byte[] key, Clock clock, Duration clockSkew, boolean expirationRequired) {
        Objects.requireNonNull(key, "key");
        if (key.length == 0) {
            throw new IllegalArgumentException("JWT key must not be empty");
        }
        this.key = key.clone();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clockSkew = Objects.requireNonNull(clockSkew, "clockSkew");
        this.expirationRequired = expirationRequired;
    }

    /**
     * Verifies a token and returns its claims.
     *
     * @param token compact serialization {@code header.payload.signature}
     * @return the payload claims (a JSON object)
     * @throws InvalidJwtException if the token is missing, malformed, unsigned, badly signed,
     *     expired or not yet valid. The message never contains the token.
     */
    public JsonNode verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidJwtException("missing token");
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new InvalidJwtException("token too long");
        }
        String[] parts = token.trim().split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidJwtException("malformed token");
        }
        if (parts[2].isEmpty()) {
            throw new InvalidJwtException("unsigned token");
        }

        JsonNode header = decodeObject(parts[0], "header");
        String alg = Json.text(header, "alg");
        if (!"HS256".equals(alg)) {
            // Covers "none" (unsigned tokens) and any other algorithm.
            throw new InvalidJwtException("unsupported algorithm");
        }
        if (header.has("crit")) {
            throw new InvalidJwtException("unsupported critical header");
        }
        String typ = Json.text(header, "typ");
        if (header.has("typ") && !"JWT".equalsIgnoreCase(typ)) {
            throw new InvalidJwtException("unsupported token type");
        }

        byte[] expected = hmacSha256((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        byte[] provided = decode(parts[2], "signature");
        if (!BASE64URL_NO_PADDING.encodeToString(provided).equals(parts[2])) {
            // Non-canonical encodings decode to the same bytes: refuse them, so that a signature has
            // exactly one valid spelling.
            throw new InvalidJwtException("malformed signature");
        }
        if (!ConstantTime.equals(expected, provided)) {
            throw new InvalidJwtException("invalid signature");
        }

        JsonNode claims = decodeObject(parts[1], "payload");
        Instant now = clock.instant();
        JsonNode exp = claims.get("exp");
        if (exp == null || exp.isNull()) {
            if (expirationRequired) {
                throw new InvalidJwtException("missing expiration");
            }
        } else {
            Instant expiresAt = numericDate(exp, "exp");
            if (!now.isBefore(expiresAt.plus(clockSkew))) {
                throw new InvalidJwtException("expired token");
            }
        }
        JsonNode nbf = claims.get("nbf");
        if (nbf != null && !nbf.isNull()) {
            Instant notBefore = numericDate(nbf, "nbf");
            if (now.plus(clockSkew).isBefore(notBefore)) {
                throw new InvalidJwtException("token not yet valid");
            }
        }
        JsonNode iat = claims.get("iat");
        if (iat != null && !iat.isNull()) {
            Instant issuedAt = numericDate(iat, "iat");
            if (now.plus(clockSkew).isBefore(issuedAt)) {
                throw new InvalidJwtException("token issued in the future");
            }
        }
        return claims;
    }

    private byte[] hmacSha256(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static JsonNode decodeObject(String part, String name) {
        JsonNode node;
        try {
            node = Json.parse(decode(part, name));
        } catch (JacksonException e) {
            throw new InvalidJwtException("malformed " + name);
        }
        if (node == null || !node.isObject()) {
            throw new InvalidJwtException("malformed " + name);
        }
        return node;
    }

    private static byte[] decode(String part, String name) {
        if (part.indexOf('=') >= 0) {
            // JWTs use base64url without padding.
            throw new InvalidJwtException("malformed " + name);
        }
        try {
            return BASE64URL.decode(part);
        } catch (IllegalArgumentException e) {
            throw new InvalidJwtException("malformed " + name);
        }
    }

    private static Instant numericDate(JsonNode value, String claim) {
        if (!value.isNumber()) {
            throw new InvalidJwtException("malformed " + claim + " claim");
        }
        return Instant.ofEpochSecond(value.asLong());
    }

    /** The token was rejected. The message describes why, never the token itself. */
    public static final class InvalidJwtException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InvalidJwtException(String reason) {
            super(reason);
        }
    }
}
