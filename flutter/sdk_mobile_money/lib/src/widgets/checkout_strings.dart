import 'package:flutter/foundation.dart';

import '../exceptions.dart';
import '../phone/mobile_operator.dart';

/// The texts of `MobileMoneyCheckout`: use [english], [french], or adapt one
/// of them with [copyWith].
@immutable
final class MobileMoneyCheckoutStrings {
  /// Creates a set of texts.
  const MobileMoneyCheckoutStrings({
    required this.amountLabel,
    required this.phoneLabel,
    required this.phoneHint,
    required this.invalidPhone,
    required this.operatorName,
    required this.formatAmount,
    required this.payButton,
    required this.confirmTitle,
    required this.confirmBody,
    required this.successTitle,
    required this.failedTitle,
    required this.failedBody,
    required this.stillPendingTitle,
    required this.stillPendingBody,
    required this.checkAgainButton,
    required this.networkError,
    required this.serverError,
    required this.rejectedError,
    required this.statusError,
  });

  /// Label above the amount.
  final String amountLabel;

  /// Label of the phone number field.
  final String phoneLabel;

  /// Hint shown in the empty phone number field.
  final String phoneHint;

  /// Error shown under the phone number field.
  final String Function(InvalidPhoneNumberReason reason) invalidPhone;

  /// Name of a detected operator, read by screen readers.
  final String Function(MobileOperator operator) operatorName;

  /// Formats an amount with its currency, for example `1,500 XAF`.
  final String Function(int amount, String currency) formatAmount;

  /// Text of the pay button, given the formatted amount.
  final String Function(String amount) payButton;

  /// Title shown while the customer confirms on the phone.
  final String confirmTitle;

  /// Explanation shown while the customer confirms, given the number.
  final String Function(String phoneNumber) confirmBody;

  /// Title shown when the payment succeeded.
  final String successTitle;

  /// Title shown when the payment failed.
  final String failedTitle;

  /// Explanation shown when the payment failed.
  final String failedBody;

  /// Title shown when the payment is still pending after the waiting time.
  final String stillPendingTitle;

  /// Explanation shown when the payment is still pending.
  final String stillPendingBody;

  /// Button that reads a pending payment again.
  final String checkAgainButton;

  /// The server could not be reached.
  final String networkError;

  /// The server could not start the payment.
  final String serverError;

  /// The provider refused the payment.
  final String rejectedError;

  /// The status of the payment could not be read.
  final String statusError;

  /// English texts.
  static const MobileMoneyCheckoutStrings english = MobileMoneyCheckoutStrings(
    amountLabel: 'Amount',
    phoneLabel: 'Mobile Money number',
    phoneHint: '6XX XX XX XX',
    invalidPhone: _englishInvalidPhone,
    operatorName: _operatorName,
    formatAmount: _englishAmount,
    payButton: _englishPay,
    confirmTitle: 'Confirm the payment on your phone',
    confirmBody: _englishConfirmBody,
    successTitle: 'Payment successful',
    failedTitle: 'Payment not completed',
    failedBody:
        'It was declined, cancelled or not confirmed in time. '
        'You can try again.',
    stillPendingTitle: 'Waiting for the confirmation',
    stillPendingBody:
        'The payment is not confirmed yet. '
        'If you approved it, check again in a moment.',
    checkAgainButton: 'Check again',
    networkError:
        'The server could not be reached. '
        'Check your connection and try again.',
    serverError: 'The payment could not be started. Try again.',
    rejectedError: 'The payment was refused.',
    statusError: 'The status of the payment could not be checked.',
  );

  /// French texts.
  static const MobileMoneyCheckoutStrings french = MobileMoneyCheckoutStrings(
    amountLabel: 'Montant',
    phoneLabel: 'Numéro Mobile Money',
    phoneHint: '6XX XX XX XX',
    invalidPhone: _frenchInvalidPhone,
    operatorName: _operatorName,
    formatAmount: _frenchAmount,
    payButton: _frenchPay,
    confirmTitle: 'Confirmez le paiement sur votre téléphone',
    confirmBody: _frenchConfirmBody,
    successTitle: 'Paiement réussi',
    failedTitle: 'Paiement non abouti',
    failedBody:
        'Il a été refusé, annulé ou n’a pas été confirmé à temps. '
        'Vous pouvez réessayer.',
    stillPendingTitle: 'En attente de confirmation',
    stillPendingBody:
        'Le paiement n’est pas encore confirmé. '
        'Si vous l’avez validé, vérifiez à nouveau dans un instant.',
    checkAgainButton: 'Vérifier à nouveau',
    networkError:
        'Le serveur est injoignable. '
        'Vérifiez votre connexion et réessayez.',
    serverError: 'Le paiement n’a pas pu être lancé. Réessayez.',
    rejectedError: 'Le paiement a été refusé.',
    statusError: 'Le statut du paiement n’a pas pu être vérifié.',
  );

  /// Returns a copy with the given texts replaced.
  MobileMoneyCheckoutStrings copyWith({
    String? amountLabel,
    String? phoneLabel,
    String? phoneHint,
    String Function(InvalidPhoneNumberReason reason)? invalidPhone,
    String Function(MobileOperator operator)? operatorName,
    String Function(int amount, String currency)? formatAmount,
    String Function(String amount)? payButton,
    String? confirmTitle,
    String Function(String phoneNumber)? confirmBody,
    String? successTitle,
    String? failedTitle,
    String? failedBody,
    String? stillPendingTitle,
    String? stillPendingBody,
    String? checkAgainButton,
    String? networkError,
    String? serverError,
    String? rejectedError,
    String? statusError,
  }) => MobileMoneyCheckoutStrings(
    amountLabel: amountLabel ?? this.amountLabel,
    phoneLabel: phoneLabel ?? this.phoneLabel,
    phoneHint: phoneHint ?? this.phoneHint,
    invalidPhone: invalidPhone ?? this.invalidPhone,
    operatorName: operatorName ?? this.operatorName,
    formatAmount: formatAmount ?? this.formatAmount,
    payButton: payButton ?? this.payButton,
    confirmTitle: confirmTitle ?? this.confirmTitle,
    confirmBody: confirmBody ?? this.confirmBody,
    successTitle: successTitle ?? this.successTitle,
    failedTitle: failedTitle ?? this.failedTitle,
    failedBody: failedBody ?? this.failedBody,
    stillPendingTitle: stillPendingTitle ?? this.stillPendingTitle,
    stillPendingBody: stillPendingBody ?? this.stillPendingBody,
    checkAgainButton: checkAgainButton ?? this.checkAgainButton,
    networkError: networkError ?? this.networkError,
    serverError: serverError ?? this.serverError,
    rejectedError: rejectedError ?? this.rejectedError,
    statusError: statusError ?? this.statusError,
  );

  static String _operatorName(MobileOperator operator) => switch (operator) {
    MobileOperator.mtn => 'MTN Mobile Money',
    MobileOperator.orange => 'Orange Money',
  };

  static String _englishInvalidPhone(InvalidPhoneNumberReason reason) =>
      switch (reason) {
        InvalidPhoneNumberReason.missing => 'Enter your Mobile Money number',
        InvalidPhoneNumberReason.invalidCharacters => 'Use digits only',
        InvalidPhoneNumberReason.unsupportedCountry =>
          'Only Cameroon numbers (+237) are supported',
        InvalidPhoneNumberReason.wrongLength =>
          'A number has 9 digits, for example 670 12 34 56',
        InvalidPhoneNumberReason.notMobile => 'Mobile numbers start with 6',
      };

  static String _frenchInvalidPhone(InvalidPhoneNumberReason reason) =>
      switch (reason) {
        InvalidPhoneNumberReason.missing =>
          'Saisissez votre numéro Mobile Money',
        InvalidPhoneNumberReason.invalidCharacters =>
          'Utilisez uniquement des chiffres',
        InvalidPhoneNumberReason.unsupportedCountry =>
          'Seuls les numéros du Cameroun (+237) sont acceptés',
        InvalidPhoneNumberReason.wrongLength =>
          'Un numéro a 9 chiffres, par exemple 670 12 34 56',
        InvalidPhoneNumberReason.notMobile =>
          'Les numéros mobiles commencent par 6',
      };

  static String _englishAmount(int amount, String currency) =>
      '${_group(amount, ',')} $currency';

  // French typography: narrow no-break space between thousands, no-break
  // space before the currency.
  static String _frenchAmount(int amount, String currency) =>
      '${_group(amount, ' ')} $currency';

  static String _englishPay(String amount) => 'Pay $amount';

  static String _frenchPay(String amount) => 'Payer $amount';

  static String _englishConfirmBody(String phoneNumber) =>
      'A payment request was sent to $phoneNumber. '
      'Approve it on your phone with your Mobile Money PIN.';

  static String _frenchConfirmBody(String phoneNumber) =>
      'Une demande de paiement a été envoyée au $phoneNumber. '
      'Validez-la sur votre téléphone avec votre code Mobile Money.';

  static String _group(int amount, String separator) {
    final digits = amount.abs().toString();
    final out = StringBuffer(amount < 0 ? '-' : '');
    for (var i = 0; i < digits.length; i++) {
      if (i > 0 && (digits.length - i) % 3 == 0) {
        out.write(separator);
      }
      out.write(digits[i]);
    }
    return out.toString();
  }
}
