# sdk_mobile_money

Collect Mobile Money payments in Cameroon from a Flutter app, through your own
server. Provider keys never ship in the app: this package only talks to the
merchant's server, never to Mobile Money providers directly.

It contains:

- **Phone numbers**: validation, normalization and operator detection, with
  exactly the rules of the Java core of
  [sdk-mobile-money](https://github.com/Backaad/sdk-mobile-money) (both sides
  are tested against the same reference numbers).
- **A client of your server**: start a payment, then follow it until it is
  settled, with a maximum waiting time and the possibility to stop waiting.
- **An optional payment form**: number field, detected operator, amount, pay
  button and the "confirm on your phone" message, in light and dark themes.

## Why through your server

An app can be decompiled: a provider key shipped in it ends up stolen. Your
server holds the keys (for example with the
[Spring Boot starter](https://github.com/Backaad/sdk-mobile-money/blob/main/docs/spring-boot.md)),
talks to the providers, and verifies their webhooks. The app only asks your
server to start a payment and reads its status.

## Install

```yaml
dependencies:
  sdk_mobile_money: ^0.1.2
```

Or from the repository, at a release tag:

```yaml
dependencies:
  sdk_mobile_money:
    git:
      url: https://github.com/Backaad/sdk-mobile-money.git
      path: flutter/sdk_mobile_money
      ref: v0.1.2
```

## The payment form

```dart
import 'package:sdk_mobile_money/sdk_mobile_money.dart';

final client = MobileMoneyClient(
  paymentsUrl: Uri.parse('https://shop.example/api/payments'),
  // Your own authentication, never a provider key.
  headers: () async => {'Authorization': 'Bearer ${await session.token()}'},
);

MobileMoneyCheckout(
  client: client,
  amount: 1500,
  extra: {'orderId': order.id},
  strings: MobileMoneyCheckoutStrings.french, // or .english, the default
  onCompleted: (payment) {
    // Settled: PaymentStatus.successful or PaymentStatus.failed.
  },
)
```

The form needs a `Material` ancestor, such as a `Scaffold`. It takes its colors
from the current theme, so it follows light and dark themes; the tests check
that every state stays readable in both. Its texts come in English and French,
and each one can be replaced with `copyWith`.

While the customer approves the payment on the phone, the form reads the
payment every 3 seconds, for at most 3 minutes (`pollInterval`, `timeout`). A
payment still pending after that is shown as such, never as failed, with a
button to check it again: the customer may still approve it.

Operator detection only pre-selects a badge: a number keeps its prefix when its
owner changes operator, so it is never used to refuse a number. Official
operator logos are not bundled, since they are trademarks: the form shows a
badge with the operator's name, and `operatorLogoBuilder` lets you show the
logos you are allowed to use.

## The client

```dart
final request = PaymentRequest(
  phoneNumber: PhoneNumber.parse('+237 6 70 12 34 56'),
  amount: 1500,
  extra: {'orderId': order.id},
);

try {
  final started = await client.startPayment(request);
  final settled = await client.waitForOutcome(
    started.reference,
    timeout: const Duration(minutes: 3),
    cancellationToken: token, // token.cancel() stops waiting
    onUpdate: (payment) => print(payment.status),
  );
  print(settled.status); // successful or failed
} on MerchantServerException catch (e) {
  if (e.retryable) {
    // Send the same request again: same idempotency key, single payment.
  }
} on PaymentTimeoutException {
  // Still pending: not a failure. Read it again later with getPayment.
} on PaymentWatchCancelledException {
  // You stopped waiting. The payment itself is not cancelled.
}
```

- **Idempotency.** Each `PaymentRequest` carries its own key, a random UUID
  sent in the `Idempotency-Key` header. Sending the same request object again
  (after a network error, a double tap...) sends the same key: the server
  returns the first payment and the customer is asked to pay only once. Create
  a new request for a new attempt.
- **Errors.** `MerchantServerException.retryable` is true when no answer came
  back (network error, timeout), for `408`, `429`, `5xx`, for `409
  REQUEST_IN_PROGRESS`, and for a success the client could not read: the
  payment may exist, and the same request returns it. Other answers (`400`,
  `401`, `404`, `422`...) are not retryable as they are.
- **Following a payment.** A transient error while reading the status
  (network, `5xx`, timeout, unreadable answer) never settles the payment: it
  is read again at the next interval. Only `SUCCESSFUL` and `FAILED` settle
  it; any other status, known or not, means pending.
- **Timeouts and redirects.** Each HTTP call is abandoned after
  `requestTimeout` (20 seconds by default). Redirects are not followed, except
  on the web, where the browser decides.
- **Nothing is logged**: neither numbers nor answers. `PhoneNumber.toString()`
  masks the middle digits; error messages never contain the number or the
  server's answer.

## Phone numbers

```dart
final number = PhoneNumber.parse('00237 670.12.34.56');
number.e164;           // +237670123456
number.nationalNumber; // 670123456
number.masked;         // +2376******56
PrefixTable.cameroonDefault.operatorOf(number); // MobileOperator.mtn

PhoneNumber.tryParse('67012'); // null
// PhoneNumber.parse throws an InvalidPhoneNumberException whose reason is
// missing, invalidCharacters, unsupportedCountry, wrongLength or notMobile.
```

The default prefix table holds only the blocks on which every source agrees
(MTN 650-654, 670-679, 680-683; Orange 655-659, 690-699); it is still to be
confirmed against the numbering plan of ART, the Cameroon regulator. Numbers in
other blocks are accepted, but their operator is not guessed. Build your own
table with `PrefixTable([PrefixRange(MobileOperator.mtn, 650, 654), ...])`.

## The server contract

The client expects the routes of the
[demo server](https://github.com/Backaad/sdk-mobile-money/tree/main/examples/spring-boot-demo).
Your server can follow the same contract.

**Start a payment**: `POST <paymentsUrl>`, header `Idempotency-Key` (8 to 64
letters, digits or dashes), JSON body:

```json
{"phoneNumber": "+237670123456", "amount": 1500, "description": "Order 42", "orderId": 42}
```

`phoneNumber` is always in E.164 format; `amount` and `description` are sent
when the request has them; the fields of `extra` are added as they are. A real
server should compute the amount from the order, never trust the one sent by an
app.

**Read a payment**: `GET <paymentsUrl>/<reference>`.

Both answer `2xx` with the payment:

```json
{"reference": "demo-7c1e9d20-...", "status": "PENDING", "provider": "campay",
 "amount": 1500, "currency": "XAF", "outcomeUnknown": false}
```

`reference` and `status` (`PENDING`, `SUCCESSFUL` or `FAILED`) are required;
the other fields are optional, and every field stays available in
`Payment.data`. An error answers with its HTTP status and
`{"error": "CODE", "message": "..."}`; the form explains `PAYMENT_REJECTED` and
`INVALID_PHONE_NUMBER` (with the reason as message), and shows a generic
message for the others.

## Security notes

- The status shown in the app is for the customer. Deliver the order when your
  server learns the outcome from the provider (a verified webhook or a status
  read), never because the app says so.
- Use HTTPS in production. The demo app uses plain HTTP to a server on your
  computer, in debug builds only.
- Authenticate the calls to your server with your own session (`headers`).
  Never put a provider key in the app.
