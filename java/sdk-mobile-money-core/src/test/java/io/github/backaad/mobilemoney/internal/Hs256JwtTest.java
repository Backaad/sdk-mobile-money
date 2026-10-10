package io.github.backaad.mobilemoney.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.testing.MutableClock;
import io.github.backaad.mobilemoney.testing.TestJwt;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class Hs256JwtTest {

    private static final String KEY = "test-webhook-key";
    // 2026-10-10T12:00:00Z
    private static final long NOW = 1791633600L;

    private final MutableClock clock = MutableClock.at("2026-10-10T12:00:00Z");
    private final Hs256Jwt verifier = verifier(false);

    private Hs256Jwt verifier(boolean expirationRequired) {
        return new Hs256Jwt(KEY.getBytes(StandardCharsets.UTF_8), clock, Duration.ofSeconds(30), expirationRequired);
    }

    @Test
    void acceptsAValidToken() {
        String token = TestJwt.hs256("{\"ref\":\"abc\",\"exp\":" + (NOW + 300) + "}", KEY);

        assertThat(Json.text(verifier.verify(token), "ref")).isEqualTo("abc");
    }

    @Test
    void rejectsUnsignedTokens() {
        assertRejected(TestJwt.unsigned("{\"exp\":" + (NOW + 300) + "}"), "unsigned token");
    }

    @Test
    void rejectsAlgNoneEvenWithASignaturePart() {
        String token = TestJwt.unsigned("{\"exp\":" + (NOW + 300) + "}") + "c2ln";

        assertRejected(token, "unsupported algorithm");
    }

    @Test
    void rejectsOtherAlgorithms() {
        String token = TestJwt.hs256("{\"alg\":\"HS512\",\"typ\":\"JWT\"}", "{}", KEY);

        assertRejected(token, "unsupported algorithm");
    }

    @Test
    void rejectsAlgorithmNameWithDifferentCase() {
        String token = TestJwt.hs256("{\"alg\":\"hs256\"}", "{}", KEY);

        assertRejected(token, "unsupported algorithm");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        assertRejected(TestJwt.hs256("{}", "another-key"), "invalid signature");
    }

    @Test
    void rejectsATamperedPayload() {
        String token = TestJwt.hs256("{\"amount\":100}", KEY);
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + TestJwt.encode("{\"amount\":1}") + "." + parts[2];

        assertRejected(tampered, "invalid signature");
    }

    @Test
    void rejectsExpiredTokens() {
        assertRejected(TestJwt.hs256("{\"exp\":" + (NOW - 31) + "}", KEY), "expired token");
    }

    @Test
    void toleratesSmallClockSkewOnExpiration() {
        String token = TestJwt.hs256("{\"exp\":" + (NOW - 29) + "}", KEY);

        assertThat(verifier.verify(token)).isNotNull();
    }

    @Test
    void rejectsTokensNotYetValid() {
        assertRejected(TestJwt.hs256("{\"nbf\":" + (NOW + 60) + "}", KEY), "token not yet valid");
    }

    @Test
    void rejectsNonNumericExpiration() {
        assertRejected(TestJwt.hs256("{\"exp\":\"tomorrow\"}", KEY), "malformed exp claim");
    }

    @Test
    void canRequireAnExpiration() {
        String token = TestJwt.hs256("{\"ref\":\"abc\"}", KEY);

        assertThat(verifier(false).verify(token)).isNotNull();
        assertThatThrownBy(() -> verifier(true).verify(token)).hasMessage("missing expiration");
    }

    @Test
    void rejectsCriticalHeaders() {
        assertRejected(TestJwt.hs256("{\"alg\":\"HS256\",\"crit\":[\"x\"],\"x\":1}", "{}", KEY),
                "unsupported critical header");
    }

    @Test
    void rejectsDuplicateHeaderKeys() {
        String token = TestJwt.hs256("{\"alg\":\"none\",\"alg\":\"HS256\"}", "{}", KEY);

        assertRejected(token, "malformed header");
    }

    @Test
    void rejectsOtherTokenTypes() {
        assertRejected(TestJwt.hs256("{\"alg\":\"HS256\",\"typ\":\"at+jwt\"}", "{}", KEY), "unsupported token type");
        assertThat(verifier.verify(TestJwt.hs256("{\"alg\":\"HS256\",\"typ\":\"jwt\"}", "{}", KEY))).isNotNull();
    }

    @Test
    void rejectsRs256() {
        assertRejected(TestJwt.hs256("{\"alg\":\"RS256\"}", "{}", KEY), "unsupported algorithm");
    }

    @Test
    void rejectsASignatureWithOnlyItsLastByteChanged() {
        String token = TestJwt.hs256("{}", KEY);
        byte[] signature = java.util.Base64.getUrlDecoder().decode(token.split("\\.")[2]);
        signature[signature.length - 1] ^= 1;
        String tampered = token.substring(0, token.lastIndexOf('.') + 1)
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        assertRejected(tampered, "invalid signature");
    }

    @Test
    void rejectsPaddedOrNonUrlSafeBase64() {
        String token = TestJwt.hs256("{}", KEY);
        assertRejected(token + "=", "malformed signature");
        assertRejected(token + "+/", "malformed signature");
    }

    @Test
    void rejectsNonCanonicalSignatureEncodings() {
        String token = TestJwt.hs256("{}", KEY);
        String signature = token.substring(token.lastIndexOf('.') + 1);
        // 32 bytes = 43 base64url characters; the last one carries 2 unused bits.
        char last = signature.charAt(signature.length() - 1);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char sibling = alphabet.charAt(alphabet.indexOf(last) ^ 1);
        String variant = token.substring(0, token.length() - 1) + sibling;

        assertThat(java.util.Base64.getUrlDecoder().decode(variant.substring(variant.lastIndexOf('.') + 1)))
                .isEqualTo(java.util.Base64.getUrlDecoder().decode(signature));
        assertRejected(variant, "malformed signature");
    }

    @Test
    void rejectsVeryLongTokens() {
        assertRejected("a".repeat(5000), "token too long");
    }

    @Test
    void rejectsTokensIssuedInTheFuture() {
        assertRejected(TestJwt.hs256("{\"iat\":" + (NOW + 120) + "}", KEY), "token issued in the future");
    }

    @Test
    void acceptsFractionalExpiration() {
        assertThat(verifier.verify(TestJwt.hs256("{\"exp\":" + (NOW + 60) + ".5}", KEY))).isNotNull();
    }

    @Test
    void rejectsMalformedTokens() {
        assertRejected(null, "missing token");
        assertRejected("  ", "missing token");
        assertRejected("a.b", "malformed token");
        assertRejected("a.b.c.d", "malformed token");
        assertRejected("%%%.e30.c2ln", "malformed header");
        assertRejected(TestJwt.encode("[1]") + ".e30.c2ln", "malformed header");
        assertRejected(TestJwt.encode("{\"alg\":\"HS256\"}") + ".e30.%%%", "malformed signature");
    }

    @Test
    void errorMessagesNeverContainTheToken() {
        String token = TestJwt.hs256("{\"exp\":" + (NOW - 3600) + "}", KEY);

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(Hs256Jwt.InvalidJwtException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(token.split("\\.")[2]));
    }

    private void assertRejected(String token, String reason) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(Hs256Jwt.InvalidJwtException.class)
                .hasMessage(reason);
    }
}
