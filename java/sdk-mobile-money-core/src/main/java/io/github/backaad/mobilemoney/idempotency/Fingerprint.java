package io.github.backaad.mobilemoney.idempotency;

import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.PayoutRequest;
import io.github.backaad.mobilemoney.TransactionType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Digest of what makes two requests "the same request" for idempotency: operation, amount, currency,
 * normalized phone number, merchant reference and, for payouts, the beneficiary's name. The description
 * and callback URL are left out.
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * Computes the fingerprint of a collection request.
     *
     * @param request the request
     * @return a hexadecimal SHA-256 digest
     */
    public static String of(PaymentRequest request) {
        return digest("v1", TransactionType.COLLECTION.name(), Long.toString(request.amount()),
                request.currency().getCurrencyCode(), request.phoneNumber().e164(), request.merchantReference());
    }

    /**
     * Computes the fingerprint of a payout request.
     *
     * @param request the request
     * @return a hexadecimal SHA-256 digest
     */
    public static String of(PayoutRequest request) {
        return digest("v1", TransactionType.PAYOUT.name(), Long.toString(request.amount()),
                request.currency().getCurrencyCode(), request.phoneNumber().e164(), request.merchantReference(),
                request.beneficiaryName());
    }

    private static String digest(String... fields) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                // Length prefix: "A1" + "23" and "A12" + "3" must not collide.
                sha256.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                sha256.update((byte) ':');
                sha256.update(bytes);
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
