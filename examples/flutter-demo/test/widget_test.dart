import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:sdk_mobile_money_demo/main.dart';

final Uri _server = Uri.parse('http://localhost:8080');

// Answers like the demo server in simulation mode: payments stay pending.
MockClient _demoServer(
  List<http.Request> requests, {
  String? currency = 'XAF',
}) => MockClient((request) async {
  requests.add(request);
  if (request.url.path == '/api/config') {
    return currency == null
        ? http.Response('Not Found', 404)
        : _json(200, {'currency': currency});
  }
  final reference = request.method == 'POST'
      ? 'demo-${request.headers['Idempotency-Key']}'
      : request.url.pathSegments.last;
  return _json(request.method == 'POST' ? 201 : 200, {
    'reference': reference,
    'status': 'PENDING',
    'provider': 'simulation',
    'amount': 500,
    'currency': currency ?? 'XAF',
    'outcomeUnknown': false,
  });
});

http.Response _json(int status, Object body) => http.Response(
  jsonEncode(body),
  status,
  headers: {'content-type': 'application/json'},
);

// A phone screen: 360 x 800.
void _phone(WidgetTester tester) {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
}

Future<void> _tapNewPayment(WidgetTester tester) async {
  await tester.scrollUntilVisible(
    find.text('New payment'),
    100,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.ensureVisible(find.text('New payment'));
  await tester.pump();
  await tester.tap(find.text('New payment'));
  await tester.pump();
  await tester.pump();
}

void main() {
  test('builds the address of the demo server', () {
    expect(
      serverUrl(server: 'http://192.168.1.20:8080/'),
      Uri.parse('http://192.168.1.20:8080'),
    );
    expect(
      route(serverUrl(server: 'https://demo.example/shop'), 'api/payments'),
      Uri.parse('https://demo.example/shop/api/payments'),
    );
    expect(
      route(serverUrl(server: 'http://10.0.2.2:8080'), 'api/config'),
      Uri.parse('http://10.0.2.2:8080/api/config'),
    );
  });

  testWidgets('starts a payment and asks to confirm it on the phone', (
    tester,
  ) async {
    _phone(tester);
    final requests = <http.Request>[];
    await tester.pumpWidget(
      DemoApp(server: _server, httpClient: _demoServer(requests)),
    );
    await tester.pump();

    expect(find.text('Pay 500 XAF'), findsOneWidget);
    await tester.tap(find.text('1000'));
    await tester.pump();
    expect(find.text('Pay 1,000 XAF'), findsOneWidget);

    await tester.enterText(find.byType(TextField), '670 12 34 56');
    await tester.pump();
    expect(find.text('MTN'), findsOneWidget);
    await tester.tap(find.text('Pay 1,000 XAF'));
    await tester.pump();
    await tester.pump();

    expect(find.text('Confirm the payment on your phone'), findsOneWidget);
    final post = requests.firstWhere((r) => r.method == 'POST');
    expect(post.url, Uri.parse('http://localhost:8080/api/payments'));
    expect(post.headers['Idempotency-Key'], isNotEmpty);
    expect(jsonDecode(post.body), {
      'phoneNumber': '+237670123456',
      'amount': 1000,
      'description': 'Demo payment',
    });

    await _tapNewPayment(tester);
    expect(find.text('Confirm the payment on your phone'), findsNothing);
    expect(find.text('Pay 1,000 XAF'), findsOneWidget);
  });

  testWidgets('offers 25, for the Campay sandbox', (tester) async {
    _phone(tester);
    await tester.pumpWidget(
      DemoApp(server: _server, httpClient: _demoServer([])),
    );
    await tester.pump();

    await tester.tap(find.text('25'));
    await tester.pump();

    expect(find.text('Pay 25 XAF'), findsOneWidget);
  });

  testWidgets('shows the currency of the server', (tester) async {
    _phone(tester);
    final requests = <http.Request>[];
    await tester.pumpWidget(
      DemoApp(
        server: _server,
        httpClient: _demoServer(requests, currency: 'EUR'),
      ),
    );
    await tester.pump();

    expect(requests.first.url, Uri.parse('http://localhost:8080/api/config'));
    expect(find.text('500 EUR'), findsOneWidget);
    expect(find.text('Pay 500 EUR'), findsOneWidget);
  });

  testWidgets('keeps XAF when the server does not say, and asks again', (
    tester,
  ) async {
    _phone(tester);
    final requests = <http.Request>[];
    await tester.pumpWidget(
      DemoApp(
        server: _server,
        httpClient: _demoServer(requests, currency: null),
      ),
    );
    await tester.pump();
    expect(find.text('Pay 500 XAF'), findsOneWidget);

    await _tapNewPayment(tester);

    expect(requests.where((r) => r.url.path == '/api/config'), hasLength(2));
    expect(find.text('Pay 500 XAF'), findsOneWidget);
  });

  testWidgets('switches the theme and the language', (tester) async {
    _phone(tester);
    await tester.pumpWidget(
      DemoApp(server: _server, httpClient: _demoServer([])),
    );
    await tester.pump();
    final theme = Theme.of(tester.element(find.text('Amount')));

    await tester.tap(find.byType(IconButton));
    await tester.pumpAndSettle();
    final switched = Theme.of(tester.element(find.text('Amount')));
    expect(switched.brightness, isNot(theme.brightness));

    await tester.tap(find.text('FR'));
    await tester.pumpAndSettle();
    expect(find.text('Montant'), findsOneWidget);
    expect(find.text('Payer 500\u00a0XAF'), findsOneWidget);
    expect(find.text('Nouveau paiement'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
