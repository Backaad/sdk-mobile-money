/**
 * Idempotency: every operation that debits or pays out goes through a key valid for 24 hours, so that a
 * repeated request (double tap, network retry) returns the first result instead of moving money twice.
 */
package io.github.backaad.mobilemoney.idempotency;
