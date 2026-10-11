/// Mobile Money payments for Flutter apps, through the merchant's server.
///
/// This package never talks to Mobile Money providers and never holds
/// provider keys: an app can be decompiled, so keys stay on the server.
///
/// * [PhoneNumber] and [PrefixTable] validate a number and detect its operator
///   with the same rules as the Java core.
/// * [MobileMoneyClient] starts a payment on the merchant's server and follows
///   it until it is settled.
/// * [MobileMoneyCheckout] is a ready-made, optional payment form.
library;

export 'src/client/cancellation_token.dart';
export 'src/client/mobile_money_client.dart';
export 'src/client/payment.dart';
export 'src/client/payment_request.dart';
export 'src/exceptions.dart';
export 'src/phone/mobile_operator.dart';
export 'src/phone/phone_number.dart';
export 'src/phone/prefix_table.dart';
export 'src/widgets/checkout_strings.dart';
export 'src/widgets/mobile_money_checkout.dart';
