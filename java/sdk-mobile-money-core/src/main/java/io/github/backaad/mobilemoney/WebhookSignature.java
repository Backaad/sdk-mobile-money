package io.github.backaad.mobilemoney;

/** How a provider authenticates its webhooks. */
public enum WebhookSignature {
    /** JSON Web Token signed with HMAC-SHA256 (Campay). */
    JWT_HS256,
    /** HMAC-SHA256 of the raw body, in hexadecimal (NotchPay). */
    HMAC_SHA256,
    /** Not signed: the status is read again from the provider before it is trusted (MTN MoMo). */
    NONE,
    /**
     * Not signed and no status query: each payment gets a single-use, expiring, unpredictable callback
     * URL, and the callback must match the expected payment (Tara).
     */
    CALLBACK_TOKEN
}
