# Spring Boot demo

A small server that uses the SDK through its Spring Boot starter:

| Route | What it does |
|---|---|
| `POST /api/payments` | Starts a payment. Header `Idempotency-Key` (8 to 64 letters, digits or dashes; one per payment attempt, the same for retries), JSON body `{"phoneNumber": "...", "amount": 500, "description": "..."}`. Answers `201` with the payment, usually `PENDING`. |
| `GET /api/payments/{reference}` | Reads a payment; asks the provider when it is still pending. |
| `GET/POST/PUT /mobile-money/webhooks/{provider}` | Receives the providers' webhooks (from the starter). |

The [Flutter demo app](../flutter-demo) uses the first two routes.

It runs in **simulation mode** by default: it talks to nobody and never confirms a
payment, so a payment stays `PENDING`. To go further, use a provider's sandbox.

> **Demo only.** The routes have no authentication, and the app sends the amount.
> A real server authenticates its users and computes the amount from the order:
> never trust an amount sent by an app.

## Run it

With Docker (server and Redis):

```sh
cd examples/spring-boot-demo
docker compose up --build
```

Without Docker (in-memory stores), from the root of the repository:

```sh
(cd java && ./mvnw install -DskipTests)
(cd java && ./mvnw -f ../examples/spring-boot-demo/pom.xml spring-boot:run)
```

Then:

```sh
curl -X POST localhost:8080/api/payments \
  -H 'Idempotency-Key: 7c1e9d20-0000-4000-8000-000000000001' \
  -H 'Content-Type: application/json' \
  -d '{"phoneNumber": "+237 670 12 34 56", "amount": 500}'
curl localhost:8080/api/payments/demo-7c1e9d20-0000-4000-8000-000000000001
```

## Use a sandbox

Put your own sandbox keys in the `.env` file at the root of the repository (copy
`.env.example`), set `MOBILE_MONEY_SIMULATION=false`, and choose
`MOBILE_MONEY_DEFAULT_PROVIDER` if several providers are configured. For the MTN
MoMo public sandbox, which works in EUR (to be confirmed), also set
`MTN_MOMO_CURRENCY=EUR` and `DEMO_CURRENCY=EUR`. Docker Compose reads that file;
without Docker, load it first (`set -a; . ./.env; set +a`).

Docker Compose publishes the server on `127.0.0.1:8080` only, because the demo
API has no authentication. To reach it from a phone on the same network, change
the port line to `"8080:8080"` for the time of the test.

To receive webhooks, the server must be reachable from the internet, for example
through an HTTPS tunnel. Set `MOBILE_MONEY_CALLBACK_BASE_URL` to the public URL of
`/mobile-money/webhooks`, and configure `<that URL>/campay` or `<that URL>/notchpay`
in the Campay or NotchPay dashboard.

Each outcome (paid or failed) is logged by the `Payments` component, which listens
to the starter's `PaymentOutcomeEvent`.
