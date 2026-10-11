import 'package:flutter_test/flutter_test.dart';
import 'package:sdk_mobile_money/sdk_mobile_money.dart';

void main() {
  test('a custom table replaces the default one', () {
    final table = PrefixTable(const [
      PrefixRange(MobileOperator.orange, 684, 686),
      PrefixRange(MobileOperator.mtn, 670, 670),
    ]);

    expect(
      table.operatorOf(PhoneNumber.parse('684000000')),
      MobileOperator.orange,
    );
    expect(
      table.operatorOf(PhoneNumber.parse('670000000')),
      MobileOperator.mtn,
    );
    expect(table.operatorOf(PhoneNumber.parse('671000000')), isNull);
    expect(table.prefixes.keys, ['670', '684', '685', '686']);
  });

  test('an unknown prefix guesses no operator', () {
    expect(
      PrefixTable.cameroonDefault.operatorOf(PhoneNumber.parse('684000000')),
      isNull,
    );
    expect(
      PrefixTable(const []).operatorOf(PhoneNumber.parse('670000000')),
      isNull,
    );
  });

  test('refuses prefixes outside 600-699 and reversed ranges', () {
    for (final range in const [
      PrefixRange(MobileOperator.mtn, 599, 650),
      PrefixRange(MobileOperator.mtn, 650, 700),
      PrefixRange(MobileOperator.mtn, 660, 650),
    ]) {
      expect(() => PrefixTable([range]), throwsArgumentError);
    }
  });

  test('refuses a prefix assigned to two operators', () {
    expect(
      () => PrefixTable(const [
        PrefixRange(MobileOperator.mtn, 650, 655),
        PrefixRange(MobileOperator.orange, 655, 659),
      ]),
      throwsA(
        isA<ArgumentError>().having(
          (e) => e.message,
          'message',
          'Prefix 655 is assigned to both MTN and ORANGE',
        ),
      ),
    );
  });

  test('accepts overlapping ranges of the same operator', () {
    final table = PrefixTable(const [
      PrefixRange(MobileOperator.mtn, 650, 655),
      PrefixRange(MobileOperator.mtn, 653, 657),
    ]);

    expect(table.prefixes.length, 8);
  });

  test('the table cannot be changed', () {
    expect(
      () => PrefixTable.cameroonDefault.prefixes['640'] = MobileOperator.mtn,
      throwsUnsupportedError,
    );
  });
}
