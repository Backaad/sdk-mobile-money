# Example

The app talks only to **your server**, never to a Mobile Money provider: see
the [server contract](https://github.com/Backaad/sdk-mobile-money/tree/main/flutter/sdk_mobile_money#the-server-contract).
A complete app that pays through the demo server of the repository is in
[examples/flutter-demo](https://github.com/Backaad/sdk-mobile-money/tree/main/examples/flutter-demo).

## The payment form

```dart
import 'package:flutter/material.dart';
import 'package:sdk_mobile_money/sdk_mobile_money.dart';

final client = MobileMoneyClient(
  // The payments route of your own server.
  paymentsUrl: Uri.parse('https://shop.example/api/payments'),
);

void main() {
  runApp(
    MaterialApp(
      home: Scaffold(
        body: Padding(
          padding: const EdgeInsets.all(16),
          child: MobileMoneyCheckout(
            client: client,
            amount: 1500,
            extra: const {'orderId': '42'},
            strings: MobileMoneyCheckoutStrings.french,
            onCompleted: (payment) {
              // PaymentStatus.successful or PaymentStatus.failed. Deliver the
              // order when your server confirms it, not because the app says so.
              debugPrint('${payment.reference}: ${payment.status.name}');
            },
          ),
        ),
      ),
    ),
  );
}
```

## The client, without the form

```dart
Future<void> pay(MobileMoneyClient client, String number) async {
  final request = PaymentRequest(
    phoneNumber: PhoneNumber.parse(number), // e.g. '+237 6 70 12 34 56'
    amount: 1500,
  );
  try {
    final started = await client.startPayment(request);
    final settled = await client.waitForOutcome(started.reference);
    debugPrint('Settled: ${settled.status.name}');
  } on InvalidPhoneNumberException catch (e) {
    debugPrint('Invalid number: ${e.reason.code}');
  } on PaymentTimeoutException {
    debugPrint('Still pending: not a failure, check again later');
  } on MerchantServerException catch (e) {
    if (e.retryable) {
      // Send the same request again: same idempotency key, one payment.
    }
  }
}
```
