import 'dart:async';
import 'dart:convert';

import 'package:fake_async/fake_async.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:sdk_mobile_money/sdk_mobile_money.dart';

final Uri _url = Uri.parse('https://shop.example/api/payments');

http.Response _json(int status, Object body) => http.Response(
  jsonEncode(body),
  status,
  headers: {'content-type': 'application/json'},
);

http.Response _payment(String status, {String reference = 'demo-1'}) =>
    _json(200, {
      'reference': reference,
      'status': status,
      'provider': 'campay',
      'amount': 500,
      'currency': 'XAF',
      'outcomeUnknown': false,
    });

PaymentRequest _request() =>
    PaymentRequest(phoneNumber: PhoneNumber.parse('670123456'), amount: 500);

TypeMatcher<MerchantServerException> _serverError({
  int? status,
  String? code,
  required bool retryable,
}) => isA<MerchantServerException>()
    .having((e) => e.statusCode, 'statusCode', status)
    .having((e) => e.errorCode, 'errorCode', code)
    .having((e) => e.retryable, 'retryable', retryable);

void main() {
  group('startPayment', () {
    test('posts the payment with its idempotency key', () async {
      final requests = <http.Request>[];
      final client = MobileMoneyClient(
        paymentsUrl: _url,
        httpClient: MockClient((request) async {
          requests.add(request);
          return _json(201, {
            'reference': 'demo-1',
            'status': 'PENDING',
            'provider': 'simulation',
            'amount': 500,
            'currency': 'XAF',
            'outcomeUnknown': false,
          });
        }),
      );
      final request = _request();

      final payment = await client.startPayment(request);

      expect(payment.reference, 'demo-1');
      expect(payment.status, PaymentStatus.pending);
      expect(payment.provider, 'simulation');
      final sent = requests.single;
      expect(sent.method, 'POST');
      expect(sent.url, _url);
      expect(sent.headers['Idempotency-Key'], request.idempotencyKey);
      expect(sent.headers['Content-Type'], startsWith('application/json'));
      expect(sent.headers['Accept'], 'application/json');
      expect(sent.followRedirects, isFalse);
      expect(jsonDecode(sent.body), {
        'phoneNumber': '+237670123456',
        'amount': 500,
      });
    });

    test('a retry of the same request sends the same key', () async {
      final keys = <String?>[];
      var calls = 0;
      final client = MobileMoneyClient(
        paymentsUrl: _url,
        httpClient: MockClient((request) async {
          keys.add(request.headers['Idempotency-Key']);
          if (++calls == 1) {
            throw http.ClientException('Connection reset', request.url);
          }
          return _payment('PENDING');
        }),
      );
      final request = _request();

      await expectLater(
        client.startPayment(request),
        throwsA(_serverError(retryable: true)),
      );
      await client.startPayment(request);
      await client.startPayment(_request());

      expect(keys[0], request.idempotencyKey);
      expect(keys[1], request.idempotencyKey);
      expect(keys[2], isNot(request.idempotencyKey));
    });

    test('sends the app headers but keeps its own', () async {
      late http.Request sent;
      final client = MobileMoneyClient(
        paymentsUrl: _url,
        headers: () async => {
          'Authorization': 'Bearer session-of-the-user',
          'Idempotency-Key': 'overridden',
        },
        httpClient: MockClient((request) async {
          sent = request;
          return _payment('PENDING');
        }),
      );
      final request = _request();

      await client.startPayment(request);

      expect(sent.headers['Authorization'], 'Bearer session-of-the-user');
      expect(sent.headers['Idempotency-Key'], request.idempotencyKey);
    });

    test('a refusal is not retryable and carries the error code', () async {
      final client = MobileMoneyClient(
        paymentsUrl: _url,
        httpClient: MockClient(
          (_) async => _json(400, {
            'error': 'INVALID_AMOUNT',
            'message': 'amount must be between 1 and 1000000',
          }),
        ),
      );

      await expectLater(
        client.startPayment(_request()),
        throwsA(
          _serverError(
            status: 400,
            code: 'INVALID_AMOUNT',
            retryable: false,
          ).having(
            (e) => e.serverMessage,
            'serverMessage',
            'amount must be between 1 and 1000000',
          ),
        ),
      );
    });

    test('sorts errors into retryable or not', () async {
      final cases = <(int, String?, bool)>[
        (422, 'PAYMENT_REJECTED', false),
        (409, 'IDEMPOTENCY_KEY_REUSED', false),
        (409, 'REQUEST_IN_PROGRESS', true),
        (503, 'PROVIDER_UNAVAILABLE', true),
        (500, null, true),
        (502, null, true),
        (408, null, true),
        (429, null, true),
        (401, null, false),
        (404, 'UNKNOWN_PAYMENT', false),
        (302, null, false),
      ];
      for (final (status, code, retryable) in cases) {
        final client = MobileMoneyClient(
          paymentsUrl: _url,
          httpClient: MockClient(
            (_) async => code == null
                ? http.Response('<html>error</html>', status)
                : _json(status, {'error': code, 'message': 'details'}),
          ),
        );

        await expectLater(
          client.startPayment(_request()),
          throwsA(
            _serverError(status: status, code: code, retryable: retryable),
          ),
          reason: '$status $code',
        );
      }
    });

    test('a network error is retryable, without status', () async {
      final client = MobileMoneyClient(
        paymentsUrl: _url,
        httpClient: MockClient(
          (request) => throw http.ClientException('Failed host lookup'),
        ),
      );

      await expectLater(
        client.startPayment(_request()),
        throwsA(_serverError(retryable: true)),
      );
    });

    test('an unreadable success is retryable: the payment may exist', () async {
      for (final body in ['', 'not json', '[]', '{"status":"PENDING"}']) {
        final client = MobileMoneyClient(
          paymentsUrl: _url,
          httpClient: MockClient((_) async => http.Response(body, 201)),
        );

        await expectLater(
          client.startPayment(_request()),
          throwsA(_serverError(status: 201, retryable: true)),
          reason: body,
        );
      }
    });

    test('a request that takes too long is abandoned, retryable', () {
      fakeAsync((async) {
        final client = MobileMoneyClient(
          paymentsUrl: _url,
          requestTimeout: const Duration(seconds: 5),
          httpClient: MockClient(
            (_) => Completer<http.Response>().future, // never answers
          ),
        );
        Object? error;
        client.startPayment(_request()).catchError((Object e) {
          error = e;
          return _dummy;
        });

        async.elapse(const Duration(seconds: 4));
        expect(error, isNull);
        async.elapse(const Duration(seconds: 2));
        expect(error, _serverError(retryable: true));
      });
    });

    test('reads UTF-8 even without charset', () async {
      final client = MobileMoneyClient(
        paymentsUrl: _url,
        httpClient: MockClient(
          (_) async => http.Response.bytes(
            utf8.encode(
              '{"reference":"demo-1","status":"PENDING","note":"Payé"}',
            ),
            201,
            headers: {'content-type': 'application/json'},
          ),
        ),
      );

      final payment = await client.startPayment(_request());

      expect(payment.data['note'], 'Payé');
    });

    test('messages never contain the answer', () async {
      final client = MobileMoneyClient(
        paymentsUrl: _url,
        httpClient: MockClient(
          (_) async => _json(400, {
            'error': 'INVALID_REQUEST',
            'message': 'secret-looking details',
          }),
        ),
      );

      try {
        await client.startPayment(_request());
        fail('should throw');
      } on MerchantServerException catch (e) {
        expect(e.message, isNot(contains('secret-looking')));
        expect(e.toString(), isNot(contains('secret-looking')));
        expect(e.toString(), isNot(contains('670123456')));
      }
    });
  });

  group('getPayment', () {
    test('reads the payment under the payments URL', () async {
      final urls = <Uri>[];
      for (final base in [
        'https://shop.example/api/payments',
        'https://shop.example/api/payments/',
      ]) {
        final client = MobileMoneyClient(
          paymentsUrl: Uri.parse(base),
          httpClient: MockClient((request) async {
            urls.add(request.url);
            expect(request.method, 'GET');
            return _payment('SUCCESSFUL', reference: 'demo-a/b c');
          }),
        );

        final payment = await client.getPayment('demo-a/b c');

        expect(payment.status, PaymentStatus.successful);
      }
      expect(urls, [
        Uri.parse('https://shop.example/api/payments/demo-a%2Fb%20c'),
        Uri.parse('https://shop.example/api/payments/demo-a%2Fb%20c'),
      ]);
    });

    test('an unknown reference is not retryable', () async {
      final client = MobileMoneyClient(
        paymentsUrl: _url,
        httpClient: MockClient(
          (_) async => _json(404, {
            'error': 'UNKNOWN_PAYMENT',
            'message': 'No payment with this reference',
          }),
        ),
      );

      await expectLater(
        client.getPayment('demo-unknown'),
        throwsA(
          _serverError(status: 404, code: 'UNKNOWN_PAYMENT', retryable: false),
        ),
      );
      expect(() => client.getPayment(''), throwsArgumentError);
    });
  });

  group('waitForOutcome', () {
    // Answers the GETs with [answers] in turn, then repeats the last one.
    MobileMoneyClient polling(
      List<FutureOr<http.Response> Function()> answers, {
      List<DateTime>? times,
      FakeAsync? async,
    }) {
      var i = 0;
      return MobileMoneyClient(
        paymentsUrl: _url,
        httpClient: MockClient((request) async {
          times?.add(async!.getClock(DateTime(2026)).now());
          final answer = answers[i < answers.length ? i : answers.length - 1];
          i++;
          return answer();
        }),
      );
    }

    test('reads the payment until it is settled', () {
      fakeAsync((async) {
        final updates = <PaymentStatus>[];
        final times = <DateTime>[];
        final client = polling(
          [
            () => _payment('PENDING'),
            () => _payment('PENDING'),
            () => _payment('SUCCESSFUL'),
          ],
          times: times,
          async: async,
        );
        Payment? result;
        client
            .waitForOutcome(
              'demo-1',
              pollInterval: const Duration(seconds: 3),
              onUpdate: (p) => updates.add(p.status),
            )
            .then((p) => result = p);

        async.elapse(const Duration(seconds: 10));

        expect(result?.status, PaymentStatus.successful);
        expect(updates, [
          PaymentStatus.pending,
          PaymentStatus.pending,
          PaymentStatus.successful,
        ]);
        expect(times.map((t) => t.second), [0, 3, 6]);
        expect(async.pendingTimers, isEmpty);
      });
    });

    test('returns a failed payment as failed', () {
      fakeAsync((async) {
        final client = polling([() => _payment('FAILED')]);
        Payment? result;
        client.waitForOutcome('demo-1').then((p) => result = p);

        async.flushMicrotasks();

        expect(result?.status, PaymentStatus.failed);
      });
    });

    test('transient errors never settle the payment', () {
      fakeAsync((async) {
        final client = polling([
          () => throw http.ClientException('Network is unreachable'),
          () => _json(500, {'error': 'INTERNAL'}),
          () => http.Response('', 200),
          () => _json(503, {'error': 'UNAVAILABLE'}),
          () => Completer<http.Response>().future, // times out
          () => _payment('UNKNOWN_STATUS'),
          () => _payment('SUCCESSFUL'),
        ]);
        final updates = <PaymentStatus>[];
        Payment? result;
        Object? error;
        client
            .waitForOutcome('demo-1', onUpdate: (p) => updates.add(p.status))
            .then<void>((p) => result = p, onError: (Object e) => error = e);

        async.elapse(const Duration(minutes: 1));

        expect(error, isNull);
        expect(result?.status, PaymentStatus.successful);
        expect(updates, [PaymentStatus.pending, PaymentStatus.successful]);
      });
    });

    test('a pending payment times out, never fails', () {
      fakeAsync((async) {
        final client = polling([() => _payment('PENDING')]);
        Object? error;
        client
            .waitForOutcome(
              'demo-1',
              timeout: const Duration(seconds: 30),
              pollInterval: const Duration(seconds: 3),
            )
            .catchError((Object e) {
              error = e;
              return _dummy;
            });

        async.elapse(const Duration(seconds: 29));
        expect(error, isNull);
        async.elapse(const Duration(seconds: 2));

        expect(
          error,
          isA<PaymentTimeoutException>()
              .having((e) => e.reference, 'reference', 'demo-1')
              .having(
                (e) => e.lastKnown?.status,
                'lastKnown',
                PaymentStatus.pending,
              ),
        );
        expect(async.pendingTimers, isEmpty);
      });
    });

    test('times out without a last payment when no read succeeds', () {
      fakeAsync((async) {
        final client = polling([() => _json(502, {})]);
        Object? error;
        client
            .waitForOutcome('demo-1', timeout: const Duration(seconds: 10))
            .catchError((Object e) {
              error = e;
              return _dummy;
            });

        async.elapse(const Duration(seconds: 11));

        expect(
          error,
          isA<PaymentTimeoutException>().having(
            (e) => e.lastKnown,
            'lastKnown',
            isNull,
          ),
        );
      });
    });

    test('stops at once when cancelled, even during a read', () {
      fakeAsync((async) {
        final answer = Completer<http.Response>();
        final client = polling([
          () => _payment('PENDING'),
          () => answer.future, // still reading when cancelled
        ]);
        final token = CancellationToken();
        Object? error;
        client.waitForOutcome('demo-1', cancellationToken: token).catchError((
          Object e,
        ) {
          error = e;
          return _dummy;
        });

        async.elapse(const Duration(seconds: 4));
        expect(error, isNull);
        token.cancel();
        async.flushMicrotasks();

        expect(
          error,
          isA<PaymentWatchCancelledException>().having(
            (e) => e.lastKnown?.status,
            'lastKnown',
            PaymentStatus.pending,
          ),
        );
        answer.complete(_payment('SUCCESSFUL'));
        async.elapse(const Duration(minutes: 1));
        expect(async.pendingTimers, isEmpty);
      });
    });

    test('stops at once when cancelled between two reads', () {
      fakeAsync((async) {
        final client = polling([() => _payment('PENDING')]);
        final token = CancellationToken();
        Object? error;
        client
            .waitForOutcome(
              'demo-1',
              pollInterval: const Duration(seconds: 10),
              cancellationToken: token,
            )
            .catchError((Object e) {
              error = e;
              return _dummy;
            });

        async.elapse(const Duration(seconds: 1));
        token.cancel();
        token.cancel();
        async.flushMicrotasks();

        expect(error, isA<PaymentWatchCancelledException>());
        expect(async.pendingTimers, isEmpty);
      });
    });

    test('an already cancelled token stops before reading', () async {
      var reads = 0;
      final client = MobileMoneyClient(
        paymentsUrl: _url,
        httpClient: MockClient((_) async {
          reads++;
          return _payment('PENDING');
        }),
      );
      final token = CancellationToken()..cancel();

      await expectLater(
        client.waitForOutcome('demo-1', cancellationToken: token),
        throwsA(isA<PaymentWatchCancelledException>()),
      );
      expect(token.isCancelled, isTrue);
      expect(reads, lessThanOrEqualTo(1));
    });

    test('an error that will not go away is thrown', () {
      fakeAsync((async) {
        final client = polling([
          () => _payment('PENDING'),
          () => _json(404, {'error': 'UNKNOWN_PAYMENT'}),
        ]);
        Object? error;
        client.waitForOutcome('demo-1').catchError((Object e) {
          error = e;
          return _dummy;
        });

        async.elapse(const Duration(seconds: 4));

        expect(
          error,
          _serverError(status: 404, code: 'UNKNOWN_PAYMENT', retryable: false),
        );
        expect(async.pendingTimers, isEmpty);
      });
    });

    test('refuses a timeout or an interval that is not positive', () {
      final client = polling([() => _payment('PENDING')]);

      expect(
        () => client.waitForOutcome('demo-1', timeout: Duration.zero),
        throwsArgumentError,
      );
      expect(
        () => client.waitForOutcome('demo-1', pollInterval: Duration.zero),
        throwsArgumentError,
      );
    });
  });

  test('refuses a URL that is not http or https', () {
    expect(
      () => MobileMoneyClient(paymentsUrl: Uri.parse('ftp://shop.example/p')),
      throwsArgumentError,
    );
    expect(
      () => MobileMoneyClient(paymentsUrl: Uri.parse('/api/payments')),
      throwsArgumentError,
    );
    expect(
      () => MobileMoneyClient(
        paymentsUrl: Uri.parse('https://shop.example/api/payments?x=1'),
      ),
      throwsArgumentError,
    );
    expect(
      () => MobileMoneyClient(paymentsUrl: _url, requestTimeout: Duration.zero),
      throwsArgumentError,
    );
  });

  test('close leaves a client passed by the app open', () async {
    var closed = false;
    final inner = _CloseTracking(() => closed = true);
    MobileMoneyClient(paymentsUrl: _url, httpClient: inner).close();
    expect(closed, isFalse);
    MobileMoneyClient(paymentsUrl: _url).close();
  });
}

const Payment _dummy = Payment(
  reference: 'dummy',
  status: PaymentStatus.pending,
);

class _CloseTracking extends http.BaseClient {
  _CloseTracking(this.onClose);

  final void Function() onClose;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) =>
      throw UnimplementedError();

  @override
  void close() => onClose();
}
