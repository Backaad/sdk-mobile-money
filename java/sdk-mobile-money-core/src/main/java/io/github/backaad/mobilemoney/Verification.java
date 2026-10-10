package io.github.backaad.mobilemoney;

/** How the content of a {@link WebhookEvent} was verified. */
public enum Verification {
    /**
     * The webhook passed the provider's authenticity check (signature when the provider signs its
     * webhooks), then its status was read again from the provider's API. Every field of the event comes
     * from that read.
     */
    STATUS_REREAD
}
