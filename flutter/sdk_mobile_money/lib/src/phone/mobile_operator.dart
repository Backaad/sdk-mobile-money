/// Mobile network operators whose subscribers can pay with Mobile Money.
enum MobileOperator {
  /// MTN (MTN Mobile Money).
  mtn('MTN'),

  /// Orange (Orange Money).
  orange('ORANGE');

  const MobileOperator(this.code);

  /// The name the Java core uses, for example `MTN`.
  final String code;

  /// Returns the operator with this [code], or null when there is none.
  static MobileOperator? fromCode(String? code) {
    for (final operator in values) {
      if (operator.code == code) {
        return operator;
      }
    }
    return null;
  }
}
