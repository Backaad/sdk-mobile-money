import 'dart:convert';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:sdk_mobile_money/sdk_mobile_money.dart';

final Map<String, ThemeData> _themes = {
  'light': ThemeData(brightness: Brightness.light),
  'dark': ThemeData(brightness: Brightness.dark),
};

// The merchant's server, as the demo server answers.
class _FakeServer {
  final List<http.Request> posts = [];
  final List<http.Request> gets = [];
  http.Response Function(http.Request request)? onPost;
  String status = 'PENDING';

  late final MobileMoneyClient client = MobileMoneyClient(
    paymentsUrl: Uri.parse('https://shop.example/api/payments'),
    httpClient: MockClient((request) async {
      if (request.method == 'POST') {
        posts.add(request);
        final custom = onPost;
        if (custom != null) {
          return custom(request);
        }
        final key = request.headers['Idempotency-Key'];
        return _json(201, _payment('demo-$key', 'PENDING'));
      }
      gets.add(request);
      return _json(200, _payment(request.url.pathSegments.last, status));
    }),
  );

  List<String?> get keys =>
      posts.map((r) => r.headers['Idempotency-Key']).toList();
}

Map<String, Object?> _payment(String reference, String status) => {
  'reference': reference,
  'status': status,
  'provider': 'simulation',
  'amount': 1500,
  'currency': 'XAF',
  'outcomeUnknown': false,
};

http.Response _json(int status, Object body) => http.Response(
  jsonEncode(body),
  status,
  headers: {'content-type': 'application/json'},
);

Future<void> _pump(
  WidgetTester tester,
  _FakeServer server, {
  ThemeData? theme,
  MobileMoneyCheckoutStrings strings = MobileMoneyCheckoutStrings.english,
  ValueChanged<Payment>? onCompleted,
  OperatorLogoBuilder? operatorLogoBuilder,
  Duration timeout = const Duration(minutes: 3),
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: theme ?? _themes['light'],
      home: Scaffold(
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: MobileMoneyCheckout(
            client: server.client,
            amount: 1500,
            description: 'Order 42',
            extra: const {'orderId': 42},
            strings: strings,
            onCompleted: onCompleted,
            operatorLogoBuilder: operatorLogoBuilder,
            timeout: timeout,
          ),
        ),
      ),
    ),
  );
}

Future<void> _enterNumber(WidgetTester tester, String number) async {
  await tester.enterText(find.byType(TextField), number);
  await tester.pump();
}

Future<void> _tapPay(
  WidgetTester tester, [
  String label = 'Pay 1,500 XAF',
]) async {
  await tester.tap(find.widgetWithText(FilledButton, label));
  await tester.pump();
  await tester.pump();
}

double _contrast(Color a, Color b) {
  final la = a.computeLuminance();
  final lb = b.computeLuminance();
  return (max(la, lb) + 0.05) / (min(la, lb) + 0.05);
}

// The color of the box a text is drawn on.
Color _background(WidgetTester tester, Finder text) {
  final box = find.ancestor(of: text, matching: find.byType(DecoratedBox));
  return (tester.widget<DecoratedBox>(box.first).decoration as BoxDecoration)
      .color!;
}

// The text is readable on its box, or [on] (WCAG AA: 4.5:1). The color is
// read from what is drawn, after the theme and default styles apply.
void _expectReadable(WidgetTester tester, Finder text, {Color? on}) {
  final drawn = tester.widget<RichText>(
    find.descendant(of: text, matching: find.byType(RichText)).first,
  );
  final color = drawn.text.style!.color!;
  final background = on ?? _background(tester, text);
  expect(_contrast(color, background), greaterThanOrEqualTo(4.5));
}

void main() {
  for (final MapEntry(key: name, value: theme) in _themes.entries) {
    group('in the $name theme', () {
      testWidgets('shows the amount, the field and the pay button', (
        tester,
      ) async {
        await _pump(tester, _FakeServer(), theme: theme);

        expect(find.text('Amount'), findsOneWidget);
        expect(find.text('1,500 XAF'), findsOneWidget);
        expect(find.text('Mobile Money number'), findsOneWidget);
        expect(find.text('Pay 1,500 XAF'), findsOneWidget);
        _expectReadable(
          tester,
          find.text('1,500 XAF'),
          on: theme.colorScheme.surface,
        );
        _expectReadable(
          tester,
          find.text('Amount'),
          on: theme.colorScheme.surface,
        );
        expect(tester.takeException(), isNull);
      });

      testWidgets('asks to confirm on the phone, then shows the success', (
        tester,
      ) async {
        final server = _FakeServer();
        final completed = <Payment>[];
        await _pump(tester, server, theme: theme, onCompleted: completed.add);

        await _enterNumber(tester, '670 12 34 56');
        expect(find.text('MTN'), findsOneWidget);
        await _tapPay(tester);

        expect(server.posts, hasLength(1));
        expect(jsonDecode(server.posts.single.body), {
          'orderId': 42,
          'phoneNumber': '+237670123456',
          'amount': 1500,
          'description': 'Order 42',
        });
        final title = find.text('Confirm the payment on your phone');
        expect(title, findsOneWidget);
        expect(find.textContaining('+237 670 12 34 56'), findsOneWidget);
        expect(find.byType(LinearProgressIndicator), findsOneWidget);
        expect(find.byType(FilledButton), findsNothing);
        _expectReadable(tester, title);
        _expectReadable(
          tester,
          find.textContaining('A payment request was sent'),
        );

        server.status = 'SUCCESSFUL';
        await tester.pump(const Duration(seconds: 3));
        await tester.pump();

        expect(find.text('Payment successful'), findsOneWidget);
        expect(title, findsNothing);
        _expectReadable(tester, find.text('Payment successful'));
        expect(completed.single.status, PaymentStatus.successful);
        expect(completed.single.reference, 'demo-${server.keys.single}');
        expect(tester.takeException(), isNull);
      });

      testWidgets('shows a failure, then starts a new attempt', (tester) async {
        final server = _FakeServer()..status = 'FAILED';
        final completed = <Payment>[];
        await _pump(tester, server, theme: theme, onCompleted: completed.add);

        await _enterNumber(tester, '690123456');
        await _tapPay(tester);
        await tester.pump();

        expect(find.text('Payment not completed'), findsOneWidget);
        _expectReadable(tester, find.text('Payment not completed'));
        _expectReadable(tester, find.textContaining('It was declined'));
        expect(completed.single.status, PaymentStatus.failed);

        server.status = 'PENDING';
        await _tapPay(tester);

        expect(server.keys, hasLength(2));
        expect(server.keys[0], isNot(server.keys[1]));
        expect(find.text('Confirm the payment on your phone'), findsOneWidget);
      });

      testWidgets('a payment still pending is never shown as failed', (
        tester,
      ) async {
        final server = _FakeServer();
        final completed = <Payment>[];
        await _pump(
          tester,
          server,
          theme: theme,
          onCompleted: completed.add,
          timeout: const Duration(seconds: 30),
        );

        await _enterNumber(tester, '650123456');
        await _tapPay(tester);
        await tester.pump(const Duration(seconds: 31));
        await tester.pump();

        expect(find.text('Waiting for the confirmation'), findsOneWidget);
        expect(find.text('Payment not completed'), findsNothing);
        expect(completed, isEmpty);
        _expectReadable(tester, find.text('Waiting for the confirmation'));
        _expectReadable(
          tester,
          find.text('Check again'),
          on: _background(tester, find.text('Waiting for the confirmation')),
        );
        final reads = server.gets.length;

        server.status = 'SUCCESSFUL';
        await tester.tap(find.text('Check again'));
        await tester.pump();
        await tester.pump();

        expect(server.gets.length, reads + 1);
        expect(find.text('Payment successful'), findsOneWidget);
        expect(completed.single.status, PaymentStatus.successful);
        expect(server.posts, hasLength(1));
      });

      testWidgets('an error to start is shown, and the retry keeps the key', (
        tester,
      ) async {
        final server = _FakeServer()
          ..onPost = (request) =>
              throw http.ClientException('Network is unreachable');
        await _pump(tester, server, theme: theme);

        await _enterNumber(tester, '670123456');
        await _tapPay(tester);

        final error = find.text(
          'The server could not be reached. '
          'Check your connection and try again.',
        );
        expect(error, findsOneWidget);
        _expectReadable(tester, error);

        server.onPost = null;
        await _tapPay(tester);

        expect(server.keys, hasLength(2));
        expect(server.keys[1], server.keys[0]);
        expect(error, findsNothing);
        expect(find.text('Confirm the payment on your phone'), findsOneWidget);
      });
    });
  }

  testWidgets('shows the detected operator, when it is known', (tester) async {
    await _pump(tester, _FakeServer());

    await _enterNumber(tester, '+237 670 12 34 56');
    expect(find.text('MTN'), findsOneWidget);
    expect(find.bySemanticsLabel('MTN Mobile Money'), findsOneWidget);

    await _enterNumber(tester, '699999999');
    expect(find.text('Orange'), findsOneWidget);
    expect(find.text('MTN'), findsNothing);
    expect(find.bySemanticsLabel('Orange Money'), findsOneWidget);

    // 684-689 are not in the default table: no guess.
    await _enterNumber(tester, '684000000');
    expect(find.text('Orange'), findsNothing);
    expect(find.text('MTN'), findsNothing);

    await _enterNumber(tester, '6701');
    expect(find.text('MTN'), findsNothing);
  });

  testWidgets('the badge is readable in both themes', (tester) async {
    await _pump(tester, _FakeServer(), theme: _themes['dark']);

    await _enterNumber(tester, '670123456');
    _expectReadable(tester, find.text('MTN'));
    await _enterNumber(tester, '690123456');
    _expectReadable(tester, find.text('Orange'));
  });

  testWidgets('uses the logos the app provides', (tester) async {
    await _pump(
      tester,
      _FakeServer(),
      operatorLogoBuilder: (context, operator) =>
          Text('logo of ${operator.code}'),
    );

    await _enterNumber(tester, '655123456');

    expect(find.text('logo of ORANGE'), findsOneWidget);
    expect(find.text('Orange'), findsNothing);
  });

  testWidgets('an invalid number never reaches the server', (tester) async {
    final server = _FakeServer();
    await _pump(tester, server);

    await _tapPay(tester);
    expect(find.text('Enter your Mobile Money number'), findsOneWidget);

    await _enterNumber(tester, '67012');
    expect(
      find.text('A number has 9 digits, for example 670 12 34 56'),
      findsOneWidget,
    );
    await _enterNumber(tester, '222123456');
    await _tapPay(tester);
    expect(find.text('Mobile numbers start with 6'), findsOneWidget);
    expect(server.posts, isEmpty);

    await _enterNumber(tester, '670123456');
    expect(find.text('Mobile numbers start with 6'), findsNothing);
  });

  testWidgets('a refusal is explained, and the next attempt gets a new key', (
    tester,
  ) async {
    final server = _FakeServer()
      ..onPost = (_) => _json(422, {
        'error': 'PAYMENT_REJECTED',
        'message': 'Refused by the provider',
      });
    await _pump(tester, server);

    await _enterNumber(tester, '670123456');
    await _tapPay(tester);
    expect(find.text('The payment was refused.'), findsOneWidget);

    await _tapPay(tester);
    expect(server.keys, hasLength(2));
    expect(server.keys[1], isNot(server.keys[0]));
  });

  testWidgets('a number refused by the server is explained', (tester) async {
    final server = _FakeServer()
      ..onPost = (_) => _json(400, {
        'error': 'INVALID_PHONE_NUMBER',
        'message': 'NOT_MOBILE',
      });
    await _pump(tester, server);

    await _enterNumber(tester, '670123456');
    await _tapPay(tester);

    expect(find.text('Mobile numbers start with 6'), findsOneWidget);
  });

  testWidgets('a new number gets a new key', (tester) async {
    final server = _FakeServer()
      ..onPost = (_) => _json(503, {'error': 'PROVIDER_UNAVAILABLE'});
    await _pump(tester, server);

    await _enterNumber(tester, '670123456');
    await _tapPay(tester);
    expect(
      find.text('The payment could not be started. Try again.'),
      findsOneWidget,
    );
    await _enterNumber(tester, '690123456');
    await _tapPay(tester);

    expect(server.keys, hasLength(2));
    expect(server.keys[1], isNot(server.keys[0]));
  });

  testWidgets('shows the amount of the payment in progress', (tester) async {
    final server = _FakeServer();
    Widget form(int amount) => MaterialApp(
      home: Scaffold(
        body: MobileMoneyCheckout(client: server.client, amount: amount),
      ),
    );
    await tester.pumpWidget(form(1500));
    await _enterNumber(tester, '670123456');
    await _tapPay(tester);

    await tester.pumpWidget(form(2000));
    expect(find.text('1,500 XAF'), findsOneWidget);

    server.status = 'FAILED';
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    expect(find.text('2,000 XAF'), findsOneWidget);
    expect(find.text('Pay 2,000 XAF'), findsOneWidget);
  });

  testWidgets('an error of the app headers never leaves the form stuck', (
    tester,
  ) async {
    final server = _FakeServer();
    final client = MobileMoneyClient(
      paymentsUrl: Uri.parse('https://shop.example/api/payments'),
      headers: () => throw StateError('no session'),
      httpClient: MockClient((_) async => http.Response('', 500)),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: MobileMoneyCheckout(client: client, amount: 1500)),
      ),
    );

    await _enterNumber(tester, '670123456');
    await _tapPay(tester);

    expect(tester.takeException(), isA<StateError>());
    expect(
      find.text('The payment could not be started. Try again.'),
      findsOneWidget,
    );
    expect(find.text('Pay 1,500 XAF'), findsOneWidget);
    expect(server.posts, isEmpty);
  });

  testWidgets('removing the form stops following the payment', (tester) async {
    final server = _FakeServer();
    await _pump(tester, server);
    await _enterNumber(tester, '670123456');
    await _tapPay(tester);
    expect(find.text('Confirm the payment on your phone'), findsOneWidget);
    final reads = server.gets.length;

    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(minutes: 5));

    expect(server.gets.length, reads);
  });

  testWidgets('speaks French', (tester) async {
    await _pump(
      tester,
      _FakeServer(),
      strings: MobileMoneyCheckoutStrings.french,
    );

    expect(find.text('Montant'), findsOneWidget);
    expect(find.text('1 500 XAF'), findsOneWidget);
    await _enterNumber(tester, '6701');
    await _tapPay(tester, 'Payer 1 500 XAF');
    expect(
      find.text('Un numéro a 9 chiffres, par exemple 670 12 34 56'),
      findsOneWidget,
    );

    await _enterNumber(tester, '670123456');
    await _tapPay(tester, 'Payer 1 500 XAF');
    expect(
      find.text('Confirmez le paiement sur votre téléphone'),
      findsOneWidget,
    );
  });

  test('strings can be adapted one by one', () {
    final strings = MobileMoneyCheckoutStrings.english.copyWith(
      payButton: (amount) => 'Buy for $amount',
    );

    expect(strings.payButton('500 XAF'), 'Buy for 500 XAF');
    expect(strings.amountLabel, 'Amount');
    expect(strings.formatAmount(1234567, 'XAF'), '1,234,567 XAF');
    expect(strings.formatAmount(999, 'XAF'), '999 XAF');
  });
}
