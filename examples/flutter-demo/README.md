# Flutter demo

A Flutter app (Android and iOS) that pays through the
[Spring Boot demo server](../spring-boot-demo), with the payment form of the
[`sdk_mobile_money`](../../flutter/sdk_mobile_money) package: choose an
amount, type a number, pay, then confirm on the phone.

The app never talks to a Mobile Money provider and holds no key: it only calls
the demo server (`POST /api/payments`, then `GET /api/payments/{reference}`).
The buttons at the top switch between light and dark themes, and between
English and French.

> The demo server runs in **simulation mode** by default: it talks to nobody
> and never confirms a payment, so a payment always stays pending (after three
> minutes, the form says it is still waiting). To see a payment through, run
> the server against a provider's sandbox (see its README).

## Run it

1. Start the demo server (from the root of the repository):

   ```sh
   (cd examples/spring-boot-demo && docker compose up --build)
   ```

2. Start an Android emulator or the iOS simulator, then:

   ```sh
   cd examples/flutter-demo
   flutter pub get
   flutter run
   ```

The app calls `http://10.0.2.2:8080` on the Android emulator (the computer
that runs it) and `http://localhost:8080` elsewhere. For another address, for
example a phone on the same network as the computer:

```sh
flutter run --dart-define=DEMO_SERVER_URL=http://192.168.1.20:8080
```

The demo server is published on `127.0.0.1` only: to reach it from a phone,
change its port line as its README explains, for the time of the test.

Plain HTTP is allowed in Android **debug** builds only, and on iOS for local
addresses only, because the demo server runs on your computer without TLS. A
real app talks to its server over HTTPS.

## Tests

```sh
flutter analyze
flutter test
```

The tests replace the server with a fake one: no server is needed.
