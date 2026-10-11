import 'dart:collection';

import 'mobile_operator.dart';
import 'phone_number.dart';

/// A range of three-digit prefixes assigned to an operator, for example
/// `PrefixRange(MobileOperator.mtn, 650, 654)`.
final class PrefixRange {
  /// Creates a range from [first] to [last], inclusive.
  const PrefixRange(this.operator, this.first, this.last);

  /// The operator of the numbers in this range.
  final MobileOperator operator;

  /// First prefix, for example `650`.
  final int first;

  /// Last prefix, inclusive.
  final int last;
}

/// Maps the first three digits of a Cameroon mobile number to its operator.
///
/// Detection by prefix is only a hint: a number keeps its prefix when its
/// owner moves to another operator (number portability), and since 2023 an
/// Orange Money wallet can be opened on another operator's SIM card. It is
/// used to pre-select an operator, never to refuse a number.
///
/// The default table, [cameroonDefault], is the one of the Java core and must
/// be checked against the numbering plan published by ART (Agence de
/// Régulation des Télécommunications). Build your own table to replace it.
final class PrefixTable {
  PrefixTable._(this._operatorsByPrefix);

  /// Builds a table from [ranges].
  ///
  /// Throws an [ArgumentError] if a prefix is not a mobile prefix (600-699),
  /// if a range ends before it starts, or if a prefix is assigned to two
  /// operators.
  factory PrefixTable(Iterable<PrefixRange> ranges) {
    final operatorsByPrefix = SplayTreeMap<String, MobileOperator>();
    for (final range in ranges) {
      if (range.first < 600 || range.last > 699 || range.first > range.last) {
        throw ArgumentError(
          'Invalid prefix range ${range.first}-${range.last}: '
          'prefixes go from 600 to 699',
        );
      }
      for (var prefix = range.first; prefix <= range.last; prefix++) {
        final key = '$prefix';
        final previous = operatorsByPrefix[key];
        if (previous != null && previous != range.operator) {
          throw ArgumentError(
            'Prefix $key is assigned to both ${previous.code} '
            'and ${range.operator.code}',
          );
        }
        operatorsByPrefix[key] = range.operator;
      }
    }
    return PrefixTable._(UnmodifiableMapView(operatorsByPrefix));
  }

  /// The default table for Cameroon: MTN 650-654, 670-679, 680-683; Orange
  /// 655-659, 690-699.
  ///
  /// Only the blocks on which every source agrees are included. 684-689 (MTN,
  /// Nexttel or Orange depending on the source) and 640-642 (Orange according
  /// to some sources) are left out until they are confirmed against the
  /// numbering plan of ART, the Cameroon regulator: numbers in those blocks are
  /// accepted, but their operator is not guessed.
  static final PrefixTable cameroonDefault = PrefixTable(const [
    PrefixRange(MobileOperator.mtn, 650, 654),
    PrefixRange(MobileOperator.mtn, 670, 679),
    PrefixRange(MobileOperator.mtn, 680, 683),
    PrefixRange(MobileOperator.orange, 655, 659),
    PrefixRange(MobileOperator.orange, 690, 699),
  ]);

  final Map<String, MobileOperator> _operatorsByPrefix;

  /// Guesses the operator of [number] from its prefix, or returns null when
  /// the prefix is not in the table.
  MobileOperator? operatorOf(PhoneNumber number) {
    if (number.countryCode != PhoneNumber.cameroon) {
      return null;
    }
    return _operatorsByPrefix[number.nationalNumber.substring(0, 3)];
  }

  /// The table content: three-digit prefixes mapped to operators, sorted by
  /// prefix.
  Map<String, MobileOperator> get prefixes => _operatorsByPrefix;
}
