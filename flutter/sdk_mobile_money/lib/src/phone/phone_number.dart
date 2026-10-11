import '../exceptions.dart';

/// A normalized mobile phone number.
///
/// Only Cameroon (country code 237) is supported for now: a national number
/// has 9 digits and mobile numbers start with 6. [parse] accepts the usual
/// written forms, for example `+237 6 70 12 34 56`, `00237670123456`,
/// `237670123456` or `670123456`, with spaces, dots, dashes, slashes or
/// parentheses.
///
/// The rules are exactly those of the Java core (`PhoneNumber.parse`): both
/// sides are tested against the same reference numbers.
///
/// [toString] masks the middle digits, so that a number logged by mistake does
/// not leak in full. Use [e164] to get the complete number.
final class PhoneNumber {
  const PhoneNumber._(this.countryCode, this.nationalNumber);

  /// Country code of Cameroon.
  static const String cameroon = '237';

  static const int _maxInputLength = 40;
  static const int _nationalLength = 9;
  static const String _expected =
      'expected a Cameroon mobile number: 9 digits starting with 6, '
      'optionally preceded by +237 or 00237';

  /// The country calling code, without `+`, for example `237`.
  final String countryCode;

  /// The national number, for example `670123456`.
  final String nationalNumber;

  /// Parses and normalizes a phone number typed by a user.
  ///
  /// Throws an [InvalidPhoneNumberException] if [raw] is not a valid Cameroon
  /// mobile number.
  static PhoneNumber parse(String? raw) {
    if (raw == null || _isBlank(raw)) {
      throw _invalid(
        InvalidPhoneNumberReason.missing,
        'Phone number is missing',
      );
    }
    // Same unit as Java's String.length(): UTF-16 code units.
    if (raw.length > _maxInputLength) {
      throw _invalid(
        InvalidPhoneNumberReason.wrongLength,
        'Phone number is too long',
      );
    }
    final compact = _stripSeparators(raw);

    final String digits;
    final bool international;
    if (compact.startsWith('+')) {
      digits = compact.substring(1);
      international = true;
    } else if (compact.startsWith('00')) {
      digits = compact.substring(2);
      international = true;
    } else {
      digits = compact;
      international = false;
    }
    if (digits.isEmpty || !_isAsciiDigits(digits)) {
      throw _invalid(
        InvalidPhoneNumberReason.invalidCharacters,
        'Phone number contains invalid characters',
      );
    }

    final String national;
    if (international) {
      if (!digits.startsWith(cameroon)) {
        throw _invalid(
          InvalidPhoneNumberReason.unsupportedCountry,
          'Unsupported country code',
        );
      }
      national = digits.substring(cameroon.length);
    } else if (digits.length == cameroon.length + _nationalLength &&
        digits.startsWith(cameroon)) {
      national = digits.substring(cameroon.length);
    } else {
      national = digits;
    }

    if (national.length != _nationalLength) {
      throw _invalid(
        InvalidPhoneNumberReason.wrongLength,
        'Phone number has the wrong length',
      );
    }
    if (national[0] != '6') {
      throw _invalid(InvalidPhoneNumberReason.notMobile, 'Not a mobile number');
    }
    return PhoneNumber._(cameroon, national);
  }

  /// Like [parse], but returns null instead of throwing.
  static PhoneNumber? tryParse(String? raw) {
    try {
      return parse(raw);
    } on InvalidPhoneNumberException {
      return null;
    }
  }

  static InvalidPhoneNumberException _invalid(
    InvalidPhoneNumberReason reason,
    String problem,
  ) => InvalidPhoneNumberException(reason, '$problem: $_expected');

  // Java's String.isBlank(): only Character.isWhitespace code points. Dart's
  // trim() would also remove no-break spaces, U+0085 and U+FEFF.
  static bool _isBlank(String s) => s.codeUnits.every(_isJavaWhitespace);

  // Separators removed by the Java core: Character.isWhitespace or
  // Character.isSpaceChar, and . - / ( ).
  static String _stripSeparators(String raw) {
    final out = StringBuffer();
    for (final c in raw.codeUnits) {
      if (_isJavaWhitespace(c) ||
          _isJavaSpaceChar(c) ||
          c == 0x2E || // .
          c == 0x2D || // -
          c == 0x2F || // /
          c == 0x28 || // (
          c == 0x29) {
        // )
        continue;
      }
      out.writeCharCode(c);
    }
    return out.toString();
  }

  // Java's Character.isWhitespace: the space characters other than no-break
  // spaces, plus the control characters tab to carriage return and U+001C to
  // U+001F. All of them are in the Basic Multilingual Plane, so code units
  // can be compared directly.
  static bool _isJavaWhitespace(int c) =>
      (c >= 0x09 && c <= 0x0D) ||
      (c >= 0x1C && c <= 0x1F) ||
      (_isJavaSpaceChar(c) && c != 0x00A0 && c != 0x2007 && c != 0x202F);

  // Java's Character.isSpaceChar: Unicode categories Zs, Zl and Zp.
  static bool _isJavaSpaceChar(int c) =>
      c == 0x20 ||
      c == 0xA0 ||
      c == 0x1680 ||
      (c >= 0x2000 && c <= 0x200A) ||
      c == 0x2028 ||
      c == 0x2029 ||
      c == 0x202F ||
      c == 0x205F ||
      c == 0x3000;

  static bool _isAsciiDigits(String s) =>
      s.codeUnits.every((c) => c >= 0x30 && c <= 0x39);

  /// The number in E.164 format, for example `+237670123456`.
  String get e164 => '+$countryCode$nationalNumber';

  /// The number with its middle digits hidden, for logs and diagnostics, for
  /// example `+2376******56`.
  String get masked {
    final n = nationalNumber.length;
    return '+$countryCode${nationalNumber[0]}${'*' * (n - 3)}'
        '${nationalNumber.substring(n - 2)}';
  }

  @override
  bool operator ==(Object other) =>
      other is PhoneNumber &&
      other.countryCode == countryCode &&
      other.nationalNumber == nationalNumber;

  @override
  int get hashCode => Object.hash(countryCode, nationalNumber);

  /// Returns the masked number, see [masked].
  @override
  String toString() => masked;
}
