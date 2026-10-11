import 'package:flutter_test/flutter_test.dart';
import 'package:sdk_mobile_money/sdk_mobile_money.dart';

void main() {
  test('reads the answer of the demo server', () {
    final payment = Payment.fromJson({
      'reference': 'demo-1',
      'status': 'PENDING',
      'provider': 'simulation',
      'amount': 500,
      'currency': 'XAF',
      'outcomeUnknown': false,
      'orderId': 'A-1',
    });

    expect(payment.reference, 'demo-1');
    expect(payment.status, PaymentStatus.pending);
    expect(payment.isFinal, isFalse);
    expect(payment.provider, 'simulation');
    expect(payment.amount, 500);
    expect(payment.currency, 'XAF');
    expect(payment.outcomeUnknown, isFalse);
    expect(payment.data['orderId'], 'A-1');
  });

  test('only SUCCESSFUL and FAILED settle a payment', () {
    expect(PaymentStatus.parse('SUCCESSFUL'), PaymentStatus.successful);
    expect(PaymentStatus.parse('FAILED'), PaymentStatus.failed);
    for (final unknown in [
      'PENDING',
      'successful',
      'SUCCESS',
      'REFUNDED',
      '',
      null,
      1,
    ]) {
      expect(PaymentStatus.parse(unknown), PaymentStatus.pending);
    }
  });

  test('a payment needs a reference and a status', () {
    for (final json in <Map<String, Object?>>[
      {'status': 'PENDING'},
      {'reference': '', 'status': 'PENDING'},
      {'reference': 'demo-1'},
      {'reference': 1, 'status': 'PENDING'},
    ]) {
      expect(() => Payment.fromJson(json), throwsFormatException);
    }
  });

  test('fields of an unexpected type are ignored', () {
    final payment = Payment.fromJson({
      'reference': 'demo-1',
      'status': 'FAILED',
      'amount': '500',
      'provider': 3,
      'outcomeUnknown': 'yes',
    });

    expect(payment.status, PaymentStatus.failed);
    expect(payment.isFinal, isTrue);
    expect(payment.amount, isNull);
    expect(payment.provider, isNull);
    expect(payment.outcomeUnknown, isFalse);
  });
}
