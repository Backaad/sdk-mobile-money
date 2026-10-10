# Spring Boot starter

`sdk-mobile-money-spring-boot-starter` configures the SDK from `mobile-money.*`
properties, checks them at startup, stores idempotency records in Redis when the
application has Redis, receives the providers' webhooks, and publishes a Spring
event when a payment succeeds or fails. It requires Spring Boot 4 and Java 17.

```xml
<dependency>
    <groupId>io.github.backaad</groupId>
    <artifactId>sdk-mobile-money-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Configuration

Keys come from environment variables or a secret store, never from a committed file.

```yaml
mobile-money:
  default-provider: campay           # required when several providers are enabled
  failover: [notchpay]               # optional; empty by default: no failover
  callback-base-url: https://shop.example/mobile-money/webhooks
  campay:
    environment: sandbox             # sandbox or production, never guessed
    username: ${CAMPAY_USERNAME}
    password: ${CAMPAY_PASSWORD}
    webhook-key: ${CAMPAY_WEBHOOK_KEY}
  notchpay:
    public-key: ${NOTCHPAY_PUBLIC_KEY}
    webhook-secret: ${NOTCHPAY_WEBHOOK_SECRET}
```

A provider is enabled as soon as one of its properties is set (an empty value, such
as an unset environment variable, counts as unset), or with `enabled: true`;
`enabled: false` turns it off.

| Property | Default | Meaning |
|---|---|---|
| `mobile-money.enabled` | `true` | `false` turns the whole integration off |
| `mobile-money.simulation.enabled` | `false` | simulation mode: talks to nobody, never confirms a payment; cannot be combined with a provider |
| `mobile-money.default-provider` | the only provider | provider used first |
| `mobile-money.failover` | empty | providers to try, in order, only when nothing reached the previous one |
| `mobile-money.callback-base-url` | none | public URL of the webhook endpoint; each request gets `<url>/<provider>`. Required for Tara |
| `mobile-money.callback-token-lifetime` | `24h` | validity of a Tara single-use callback URL |
| `mobile-money.store` | `auto` | `auto` (Redis if the application has a Redis connection), `memory` or `redis` |
| `mobile-money.redis.key-prefix` | `mobile-money:` | prefix of every Redis key |
| `mobile-money.redis.check-eviction-policy` | `true` | refuse a Redis server that may evict keys |
| `mobile-money.http.connect-timeout` / `request-timeout` | `10s` / `30s` | timeouts of the calls to providers |
| `mobile-money.webhook.enabled` / `path` | `true` / `/mobile-money/webhooks` | the webhook endpoint |
| `mobile-money.pending-check.enabled` | `false` | read pending payments again until they succeed or fail |
| `mobile-money.pending-check.interval` / `max-age` / `batch-size` | `1m` / `24h` / `50` | how often, for how long, how many per round |
| `mobile-money.campay.*` | | `environment`, `username`, `password`, `webhook-key` |
| `mobile-money.mtn-momo.*` | | `environment`, `target-environment`, `currency`, `collection.{subscription-key,api-user,api-key}`, `disbursement.{...}` |
| `mobile-money.notchpay.*` | | `public-key`, `private-key`, `webhook-secret` |
| `mobile-money.tara.*` | | `api-key`, `business-id`, `allowed-callback-addresses` |

An application can also declare its own `PaymentProvider` beans: they are added to
the configured providers.

### Checked at startup

The application does not start, and the report names the property to fix, when:

- no provider is configured, or a provider is enabled without its keys or its
  environment;
- the simulation is combined with a provider;
- several providers are enabled without `default-provider`, or `default-provider`
  or `failover` names a provider that is not enabled;
- MTN MoMo is in production without `target-environment`, or Tara has no
  `callback-base-url`;
- `callback-base-url` is not a public HTTPS URL (localhost, private address, plain
  HTTP) while MTN MoMo production or Tara would send it to the provider;
- `store=redis` without a Redis connection, Redis cannot be reached, or Redis may
  evict keys (`maxmemory-policy` other than `noeviction`);
- a timeout, interval or path is invalid.

## Stores and Redis

With `spring-boot-starter-data-redis` and `spring.data.redis.*`, idempotency
records, Tara callback tokens and pending payments are kept in Redis, shared by
every instance of the application. Without Redis they are kept in memory: lost on
restart, and not shared between instances, which is only safe with one instance.

Redis must be 6.2 or later, must not evict keys (`maxmemory-policy noeviction`), and
should persist its data (AOF): a lost idempotency record could let a payment through
twice.

## Webhooks and events

The endpoint `<mobile-money.webhook.path>/<provider>` (GET, POST or PUT) is registered
in Spring MVC applications. Configure `<callback-base-url>/campay` and
`<callback-base-url>/notchpay` in the Campay and NotchPay dashboards; MTN MoMo and
Tara receive their callback URL with each request.

For each webhook, the SDK checks the signature or the single-use token, reads the
status again from the provider when it can, and then publishes a
`PaymentConfirmedEvent` or a `PaymentFailedEvent` (both `PaymentOutcomeEvent`):

```java
@Component
class Orders {

    @EventListener
    void onOutcome(PaymentOutcomeEvent event) {
        // At least once: a provider may send the same webhook again. Record each
        // merchant reference's outcome once.
        if (event instanceof PaymentConfirmedEvent confirmed) {
            // mark the order confirmed.merchantReference() as paid
        }
    }
}
```

The endpoint answers without body: `200` once verified, `400` if the webhook is not
authentic, `404` for an unknown provider, `503` if the status could not be read (the
provider sends the webhook again later), `500` if a listener failed or the
configuration does not allow the verification.

The path must be reachable without authentication and without CSRF token: with
Spring Security, permit it explicitly. Behind a reverse proxy, set
`server.forward-headers-strategy` so that the remote address checked by the Tara IP
allowlist is the provider's. An application with its own endpoint can call the
`MobileMoneyWebhooks` bean instead, with the raw body and headers.

## Pending payments

With `mobile-money.pending-check.enabled=true`, every pending payment whose status
can be read is read again every `interval`, until it succeeds or fails, and the
outcome is published as a `PaymentOutcomeEvent` (origin `STATUS_CHECK`). A transient
error leaves it pending. After `max-age`, a payment still pending is no longer read
and is logged for manual reconciliation. With Redis, several instances share the
work without reading the same payment twice in a round.
