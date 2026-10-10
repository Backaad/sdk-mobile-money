import 'package:flutter_test/flutter_test.dart';
import 'package:sdk_mobile_money/sdk_mobile_money.dart';

void main() {
  test('null is a missing number', () {
    expect(
      () => PhoneNumber.parse(null),
      throwsA(
        isA<InvalidPhoneNumberException>().having(
          (e) => e.reason,
          'reason',
          InvalidPhoneNumberReason.missing,
        ),
      ),
    );
    expect(PhoneNumber.tryParse(null), isNull);
  });

  test('tryParse returns null instead of throwing', () {
    expect(PhoneNumber.tryParse('phone'), isNull);
    expect(PhoneNumber.tryParse('670123456')?.e164, '+237670123456');
  });

  test('toString masks the middle digits', () {
    final number = PhoneNumber.parse('+237 670 12 34 56');

    expect(number.masked, '+2376******56');
    expect(number.toString(), '+2376******56');
    expect('$number', isNot(contains('670123456')));
  });

  test('error messages never contain the number', () {
    for (final input in ['67012345', '+33612345678', '222123456', '67O123']) {
      try {
        PhoneNumber.parse(input);
        fail('$input should be refused');
      } on InvalidPhoneNumberException catch (e) {
        expect(e.message, isNot(contains(input)));
        expect(e.toString(), isNot(contains(input)));
      }
    }
  });

  test('numbers written differently are equal', () {
    final a = PhoneNumber.parse('+237 6 70 12 34 56');
    final b = PhoneNumber.parse('670123456');

    expect(a, b);
    expect(a.hashCode, b.hashCode);
    expect(a, isNot(PhoneNumber.parse('670123457')));
  });

  test('reasons and operators have the codes of the Java core', () {
    expect(InvalidPhoneNumberReason.values.map((r) => r.code), [
      'MISSING',
      'INVALID_CHARACTERS',
      'UNSUPPORTED_COUNTRY',
      'WRONG_LENGTH',
      'NOT_MOBILE',
    ]);
    expect(
      InvalidPhoneNumberReason.fromCode('NOT_MOBILE'),
      InvalidPhoneNumberReason.notMobile,
    );
    expect(InvalidPhoneNumberReason.fromCode('other'), isNull);
    expect(MobileOperator.fromCode('ORANGE'), MobileOperator.orange);
    expect(MobileOperator.fromCode('Orange'), isNull);
  });
}
