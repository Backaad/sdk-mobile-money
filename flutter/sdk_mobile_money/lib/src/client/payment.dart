/// Status of a payment, as reported by the merchant's server.
enum PaymentStatus {
  /// Not settled yet: the customer has not approved it, or the outcome is not
  /// known yet.
  pending,

  /// Paid.
  successful,

  /// Declined, cancelled or expired.
  failed;

  /// Reads the `status` field of the server's answer.
  ///
  /// Only `SUCCESSFUL` and `FAILED` settle a payment; any other value,
  /// including an unknown one, means it is still pending.
  static PaymentStatus parse(Object? value) => switch (value) {
    'SUCCESSFUL' => successful,
    'FAILED' => failed,
    _ => pending,
  };
}

/// A payment, as the merchant's server describes it.
final class Payment {
  /// Creates a payment.
  const Payment({
    required this.reference,
    required this.status,
    this.provider,
    this.amount,
    this.currency,
    this.outcomeUnknown = false,
    this.data = const {},
  });

  /// Reads the server's answer.
  ///
  /// Throws a [FormatException] when `reference` or `status` is missing.
  factory Payment.fromJson(Map<String, Object?> json) {
    final reference = json['reference'];
    final status = json['status'];
    if (reference is! String || reference.isEmpty || status is! String) {
      throw const FormatException('A payment needs a reference and a status');
    }
    final provider = json['provider'];
    final amount = json['amount'];
    final currency = json['currency'];
    return Payment(
      reference: reference,
      status: PaymentStatus.parse(status),
      provider: provider is String ? provider : null,
      amount: amount is int ? amount : null,
      currency: currency is String ? currency : null,
      outcomeUnknown: json['outcomeUnknown'] == true,
      data: Map.unmodifiable(json),
    );
  }

  /// The merchant reference, used to read the payment again.
  final String reference;

  /// The status.
  final PaymentStatus status;

  /// The provider that processed the payment, for example `campay`, or
  /// `simulation` when the server runs in simulation mode.
  final String? provider;

  /// The amount, in [currency].
  final int? amount;

  /// The currency code, for example `XAF`.
  final String? currency;

  /// True when the provider's answer was lost: the payment is never sent
  /// again, and its outcome comes later with the provider's webhook.
  final bool outcomeUnknown;

  /// The whole answer of the server, including the fields this class does not
  /// read.
  final Map<String, Object?> data;

  /// Whether the payment is settled: [PaymentStatus.successful] or
  /// [PaymentStatus.failed].
  bool get isFinal => status != PaymentStatus.pending;

  @override
  String toString() => 'Payment($reference, ${status.name})';
}
