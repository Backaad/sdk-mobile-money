import 'package:flutter_test/flutter_test.dart';
import 'package:sdk_mobile_money/sdk_mobile_money.dart';

void main() {
  final number = PhoneNumber.parse('670123456');

  test('each request gets its own random UUID as idempotency key', () {
    final keys = List.generate(
      100,
      (_) => PaymentRequest(phoneNumber: number, amount: 500).idempotencyKey,
    );

    for (final key in keys) {
      expect(
        key,
        matches(
          RegExp(
            r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-'
            r'[0-9a-f]{12}$',
          ),
        ),
      );
    }
    expect(keys.toSet(), hasLength(100));
  });

  test('accepts a key of its own', () {
    final request = PaymentRequest(
      phoneNumber: number,
      idempotencyKey: 'order-42-attempt-1',
    );

    expect(request.idempotencyKey, 'order-42-attempt-1');
  });

  test('refuses keys the server would refuse', () {
    for (final key in ['short', 'a' * 65, 'has space-123', 'accent-é-123']) {
      expect(
        () => PaymentRequest(phoneNumber: number, idempotencyKey: key),
        throwsArgumentError,
        reason: key,
      );
    }
  });

  test('refuses an amount that is not positive', () {
    expect(
      () => PaymentRequest(phoneNumber: number, amount: 0),
      throwsArgumentError,
    );
    expect(
      () => PaymentRequest(phoneNumber: number, amount: -5),
      throwsArgumentError,
    );
  });

  test('the body has the E.164 number, the amount and the extra fields', () {
    final request = PaymentRequest(
      phoneNumber: PhoneNumber.parse('+237 6 70 12 34 56'),
      amount: 1500,
      description: 'Order 42',
      extra: {'orderId': 42},
    );

    expect(request.toJson(), {
      'orderId': 42,
      'phoneNumber': '+237670123456',
      'amount': 1500,
      'description': 'Order 42',
    });
    expect(PaymentRequest(phoneNumber: number).toJson(), {
      'phoneNumber': '+237670123456',
    });
  });

  test('extra fields cannot replace the payment fields', () {
    for (final field in ['phoneNumber', 'amount', 'description']) {
      expect(
        () => PaymentRequest(phoneNumber: number, extra: {field: 'x'}),
        throwsArgumentError,
      );
    }
  });

  test('extra fields cannot be changed afterwards', () {
    final extra = <String, Object?>{'orderId': 1};
    final request = PaymentRequest(phoneNumber: number, extra: extra);
    extra['orderId'] = 2;

    expect(request.toJson()['orderId'], 1);
    expect(() => request.extra['orderId'] = 3, throwsUnsupportedError);
  });
}
