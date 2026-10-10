package io.github.backaad.mobilemoney;

/**
 * Status of a transaction, common to all providers. The provider's own status is always kept next to
 * it for diagnostics.
 */
public enum TransactionStatus {
    /** Not finished, or not known yet. A transient error never turns into another status. */
    PENDING,
    /** Money moved. */
    SUCCESSFUL,
    /** The provider reported a definitive failure. */
    FAILED
}
