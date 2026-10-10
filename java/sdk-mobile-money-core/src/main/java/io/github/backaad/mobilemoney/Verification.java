package io.github.backaad.mobilemoney;

/** How the content of a {@link WebhookEvent} was verified. */
public enum Verification {
    /**
     * The webhook passed the provider's authenticity check (signature when the provider signs its
     * webhooks), then its status was read again from the provider's API. Every field of the event comes
     * from that read.
     */
    STATUS_REREAD,
    /**
     * The provider has no status query: the callback came to the single-use, unpredictable URL created for
     * this payment, and it matched the expected payment (reference, amount, number). The status is the
     * one the callback announced (Tara, experimental).
     */
    CALLBACK_TOKEN
}
