# Changelog

## Unreleased

- Phone numbers: `PhoneNumber` (validation and normalization), `PrefixTable`
  (operator detection, configurable), with exactly the rules of the Java core,
  tested against the shared reference numbers of `testdata/phone-numbers.json`.
- `MobileMoneyClient`: starts a payment on the merchant's server with an
  idempotency key, reads it, and follows it until it is settled
  (`waitForOutcome`), with a maximum waiting time and a `CancellationToken`.
  Transient errors never settle a payment. Nothing is logged.
- `MobileMoneyCheckout`: optional payment form (number field, detected
  operator badge, amount, pay button, "confirm on your phone" message), in
  light and dark themes, with English and French texts.
