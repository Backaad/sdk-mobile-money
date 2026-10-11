import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:sdk_mobile_money/sdk_mobile_money.dart';

// Runs the reference numbers of testdata/phone-numbers.json, which the Java
// tests read too: both sides must follow exactly the same rules.
void main() {
  final data = _readTestData('phone-numbers.json');
  final table = PrefixTable.cameroonDefault;

  group('accepts every valid reference number', () {
    for (final c in _cases(data, 'valid')) {
      test(jsonEncode(c['input']), () {
        final number = PhoneNumber.parse(c['input'] as String);

        expect(number.e164, c['e164']);
        expect(number.nationalNumber, c['national']);
        expect(number.countryCode, '237');
        expect(table.operatorOf(number)?.code, c['operator']);
      });
    }
  });

  group('refuses every invalid reference number', () {
    for (final c in _cases(data, 'invalid')) {
      test('${jsonEncode(c['input'])} (${c['note']})', () {
        expect(
          () => PhoneNumber.parse(c['input'] as String),
          throwsA(
            isA<InvalidPhoneNumberException>().having(
              (e) => e.reason.code,
              'reason',
              c['reason'],
            ),
          ),
        );
      });
    }
  });

  test('the default prefix table is the shared one', () {
    final expected = <String, MobileOperator>{};
    final ranges = data['defaultPrefixTable'] as Map<String, Object?>;
    ranges.forEach((operator, list) {
      for (final range in (list as List<Object?>).cast<String>()) {
        final bounds = range.split('-').map(int.parse).toList();
        for (var p = bounds[0]; p <= bounds[1]; p++) {
          expected['$p'] = MobileOperator.fromCode(operator)!;
        }
      }
    });

    expect(table.prefixes, expected);
  });
}

Iterable<Map<String, Object?>> _cases(Map<String, Object?> data, String kind) =>
    (data[kind] as List<Object?>).cast<Map<String, Object?>>();

// The repository folder testdata/, found from the package folder.
Map<String, Object?> _readTestData(String name) {
  var dir = Directory.current.absolute;
  while (true) {
    final file = File('${dir.path}/testdata/$name');
    if (file.existsSync()) {
      return jsonDecode(file.readAsStringSync()) as Map<String, Object?>;
    }
    if (dir.parent.path == dir.path) {
      throw StateError('testdata/$name not found above ${Directory.current}');
    }
    dir = dir.parent;
  }
}
