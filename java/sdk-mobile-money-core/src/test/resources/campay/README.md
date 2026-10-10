# Campay fixtures

Responses used by the WireMock tests. Token values are fake test values, never real keys. What is
confirmed and what is still to be confirmed is listed in `docs/providers/campay.md`.

| Fixture | Source |
|---|---|
| `collect-200.json` | Official Python SDK README (`campay` 1.1.0 on PyPI) |
| `transaction-successful.json` | Official Python SDK README |
| `transaction-pending.json` | Third-party Go SDK stub (NdoleStudio/campay-go-sdk); `amount: 1.0` is third-party, to be confirmed |
| `transaction-failed.json` | Built from the official sample with `status: FAILED`; the `reason` field comes from Campay's own plugins (redirect handling), to be confirmed |
| `withdraw-200.json` | Third-party Go SDK stub; the `status` field is third-party, to be confirmed |
| `error-ER101.json` | `message` field: official SDK code reads it. `error_code` field name and the ER101 text come from search extracts of the Postman page: to be confirmed |
