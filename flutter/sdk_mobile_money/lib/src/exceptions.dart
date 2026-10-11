import 'client/payment.dart';

/// Base class of the errors this package throws.
sealed class MobileMoneyException implements Exception {
  const MobileMoneyException(this.message);

  /// What went wrong. Never contains a phone number or a server answer.
  final String message;
}

/// Why a phone number was refused. The Java core uses the same values.
enum InvalidPhoneNumberReason {
  /// Nothing was given.
  missing('MISSING'),

  /// Letters or other characters that are not digits or separators.
  invalidCharacters('INVALID_CHARACTERS'),

  /// A country code other than a supported one.
  unsupportedCountry('UNSUPPORTED_COUNTRY'),

  /// Too few or too many digits.
  wrongLength('WRONG_LENGTH'),

  /// A valid number, but not a mobile number.
  notMobile('NOT_MOBILE');

  const InvalidPhoneNumberReason(this.code);

  /// The name the Java core and the servers use, for example `WRONG_LENGTH`.
  final String code;

  /// Returns the reason with this [code], or null when there is none.
  static InvalidPhoneNumberReason? fromCode(String? code) {
    for (final reason in values) {
      if (reason.code == code) {
        return reason;
      }
    }
    return null;
  }
}

/// A phone number is not a valid mobile number for the supported countries.
final class InvalidPhoneNumberException extends MobileMoneyException {
  /// Creates the exception. The [message] must not contain the number.
  const InvalidPhoneNumberException(this.reason, super.message);

  /// Why the number was refused.
  final InvalidPhoneNumberReason reason;

  @override
  String toString() => 'InvalidPhoneNumberException(${reason.code}): $message';
}

/// The merchant's server did not answer, or answered with an error.
final class MerchantServerException extends MobileMoneyException {
  /// Creates the exception.
  const MerchantServerException(
    super.message, {
    this.statusCode,
    this.errorCode,
    this.serverMessage,
    required this.retryable,
  });

  /// The HTTP status of the answer, or null when no answer came back (network
  /// error or timeout).
  final int? statusCode;

  /// The `error` field of the server's answer, for example `INVALID_AMOUNT`.
  final String? errorCode;

  /// The `message` field of the server's answer, meant for developers.
  final String? serverMessage;

  /// Whether sending the same request again may succeed.
  ///
  /// Sending the same `PaymentRequest` again is always safe: it carries the
  /// same idempotency key, so the server returns the first payment instead of
  /// starting a second one, even when the first answer was lost.
  final bool retryable;

  @override
  String toString() => 'MerchantServerException: $message';
}

/// The payment was still pending when the time allowed to wait for its
/// outcome ran out.
///
/// It is not a failure: the customer may still approve the payment, and the
/// server will learn its outcome later. Read it again with
/// `MobileMoneyClient.getPayment`.
final class PaymentTimeoutException extends MobileMoneyException {
  /// Creates the exception.
  const PaymentTimeoutException(this.reference, this.lastKnown)
    : super('The payment is still pending');

  /// The merchant reference of the payment.
  final String reference;

  /// The payment as last read, or null if no read succeeded.
  final Payment? lastKnown;

  @override
  String toString() => 'PaymentTimeoutException: $message';
}

/// Waiting for the outcome of a payment was cancelled.
///
/// Only the waiting stops: the payment itself is not cancelled, and the
/// customer may still approve it.
final class PaymentWatchCancelledException extends MobileMoneyException {
  /// Creates the exception.
  const PaymentWatchCancelledException(this.reference, this.lastKnown)
    : super('Waiting for the payment was cancelled');

  /// The merchant reference of the payment.
  final String reference;

  /// The payment as last read, or null if no read succeeded.
  final Payment? lastKnown;

  @override
  String toString() => 'PaymentWatchCancelledException: $message';
}
