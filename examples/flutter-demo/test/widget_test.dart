import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:sdk_mobile_money/sdk_mobile_money.dart';
import 'package:sdk_mobile_money_demo/main.dart';

// Answers like the demo server in simulation mode: payments stay pending.
MobileMoneyClient _demoServer(List<http.Request> requests) => MobileMoneyClient(
  paymentsUrl: paymentsUrl(server: 'http://localhost:8080'),
  httpClient: MockClient((request) async {
    requests.add(request);
    final reference = request.method == 'POST'
        ? 'demo-${request.headers['Idempotency-Key']}'
        : request.url.pathSegments.last;
    return http.Response(
      jsonEncode({
        'reference': reference,
        'status': 'PENDING',
        'provider': 'simulation',
        'amount': 500,
        'currency': 'XAF',
        'outcomeUnknown': false,
      }),
      request.method == 'POST' ? 201 : 200,
      headers: {'content-type': 'application/json'},
    );
  }),
);

void main() {
  test('builds the payments URL of the demo server', () {
    expect(
      paymentsUrl(server: 'http://192.168.1.20:8080/'),
      Uri.parse('http://192.168.1.20:8080/api/payments'),
    );
    expect(
      paymentsUrl(server: 'https://demo.example/shop'),
      Uri.parse('https://demo.example/shop/api/payments'),
    );
  });

  testWidgets('starts a payment and asks to confirm it on the phone', (
    tester,
  ) async {
    final requests = <http.Request>[];
    await tester.pumpWidget(DemoApp(client: _demoServer(requests)));

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
    final post = requests.first;
    expect(post.url, Uri.parse('http://localhost:8080/api/payments'));
    expect(post.headers['Idempotency-Key'], isNotEmpty);
    expect(jsonDecode(post.body), {
      'phoneNumber': '+237670123456',
      'amount': 1000,
      'description': 'Demo payment',
    });

    await tester.scrollUntilVisible(
      find.text('New payment'),
      100,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('New payment'));
    await tester.pump();
    expect(find.text('Confirm the payment on your phone'), findsNothing);
    expect(find.text('Pay 1,000 XAF'), findsOneWidget);
  });

  testWidgets('switches the theme and the language', (tester) async {
    await tester.pumpWidget(DemoApp(client: _demoServer([])));
    final theme = Theme.of(tester.element(find.text('Amount')));

    await tester.tap(find.byType(IconButton));
    await tester.pumpAndSettle();
    final switched = Theme.of(tester.element(find.text('Amount')));
    expect(switched.brightness, isNot(theme.brightness));

    await tester.tap(find.text('FR'));
    await tester.pumpAndSettle();
    expect(find.text('Montant'), findsOneWidget);
    expect(find.text('Payer 500 XAF'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
