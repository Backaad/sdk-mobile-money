import 'dart:math';

import '../phone/phone_number.dart';

/// A payment to start through the merchant's server.
///
/// Each request carries its own idempotency key. Send the same request object
/// again to retry (after a network error, for example): the server recognizes
/// the key and returns the first payment, so the customer is asked to pay only
/// once. Create a new request for a new payment attempt.
final class PaymentRequest {
  /// Creates a request.
  ///
  /// [extra] adds fields to the JSON body, for example an order identifier.
  /// A real server should compute the amount from the order itself, never
  /// trust the [amount] sent by an app.
  ///
  /// [idempotencyKey] is generated (a random UUID) when omitted; a key of your
  /// own must have 8 to 64 letters, digits or dashes.
  PaymentRequest({
    required this.phoneNumber,
    this.amount,
    this.description,
    Map<String, Object?> extra = const {},
    String? idempotencyKey,
  }) : extra = Map.unmodifiable(extra),
       idempotencyKey = idempotencyKey ?? _newKey() {
    if (amount != null && amount! <= 0) {
      throw ArgumentError.value(amount, 'amount', 'must be positive');
    }
    if (!_keyPattern.hasMatch(this.idempotencyKey)) {
      throw ArgumentError(
        'idempotencyKey must have 8 to 64 letters, digits or dashes',
      );
    }
    for (final field in _bodyFields) {
      if (extra.containsKey(field)) {
        throw ArgumentError('extra must not contain "$field"');
      }
    }
  }

  static final RegExp _keyPattern = RegExp(r'^[A-Za-z0-9-]{8,64}$');
  static const List<String> _bodyFields = [
    'phoneNumber',
    'amount',
    'description',
  ];
  static final Random _random = Random.secure();

  /// The customer's number, already validated.
  final PhoneNumber phoneNumber;

  /// The amount, if the server expects the app to send it.
  final int? amount;

  /// Shown to the customer, when the provider supports it.
  final String? description;

  /// More fields for the JSON body.
  final Map<String, Object?> extra;

  /// Sent in the `Idempotency-Key` header, the same for every retry of this
  /// request.
  final String idempotencyKey;

  /// The JSON body sent to the server.
  Map<String, Object?> toJson() => {
    ...extra,
    'phoneNumber': phoneNumber.e164,
    if (amount != null) 'amount': amount,
    if (description != null) 'description': description,
  };

  // A random (version 4) UUID.
  static String _newKey() {
    final bytes = List<int>.generate(16, (_) => _random.nextInt(256));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
        '${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
  }
}
