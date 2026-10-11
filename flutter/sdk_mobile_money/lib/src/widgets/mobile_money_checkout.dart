import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../client/cancellation_token.dart';
import '../client/mobile_money_client.dart';
import '../client/payment.dart';
import '../client/payment_request.dart';
import '../exceptions.dart';
import '../phone/mobile_operator.dart';
import '../phone/phone_number.dart';
import '../phone/prefix_table.dart';
import 'checkout_strings.dart';

/// Builds the logo of a detected operator.
typedef OperatorLogoBuilder =
    Widget Function(BuildContext context, MobileOperator operator);

/// A simple, optional payment form: the phone number field, the logo of the
/// detected operator, the amount, the pay button, and the "confirm on your
/// phone" message while the customer approves the payment.
///
/// It starts the payment with [client], then follows it until it is settled,
/// for at most [timeout]. A payment still pending after that is shown as such,
/// never as failed, with a button to check it again.
///
/// It uses the colors of the current [Theme], so it works in light and dark
/// themes. It needs a [Material] ancestor, for example a [Scaffold].
///
/// Official operator logos are not bundled (they are trademarks): a badge
/// with the operator's name is shown instead. Pass [operatorLogoBuilder] to
/// show the logos you are allowed to use.
class MobileMoneyCheckout extends StatefulWidget {
  /// Creates the form.
  const MobileMoneyCheckout({
    super.key,
    required this.client,
    required this.amount,
    this.currency = 'XAF',
    this.description,
    this.extra = const {},
    this.initialPhoneNumber,
    this.prefixTable,
    this.strings = MobileMoneyCheckoutStrings.english,
    this.operatorLogoBuilder,
    this.timeout = const Duration(minutes: 3),
    this.pollInterval = const Duration(seconds: 3),
    this.onCompleted,
  });

  /// Client of the merchant's server.
  final MobileMoneyClient client;

  /// Amount shown and sent to the server, in [currency].
  final int amount;

  /// Currency code shown next to the amount.
  final String currency;

  /// Sent to the server, shown to the customer when the provider supports it.
  final String? description;

  /// More fields for the request body, for example an order identifier.
  final Map<String, Object?> extra;

  /// Number shown in the field at first.
  final String? initialPhoneNumber;

  /// Table used to detect the operator; [PrefixTable.cameroonDefault] when
  /// null.
  final PrefixTable? prefixTable;

  /// Texts of the form.
  final MobileMoneyCheckoutStrings strings;

  /// Builds the logo of the detected operator, instead of the default badge.
  final OperatorLogoBuilder? operatorLogoBuilder;

  /// How long to wait for the customer's confirmation.
  final Duration timeout;

  /// How often the payment is read while waiting.
  final Duration pollInterval;

  /// Called once the payment is settled, successful or failed.
  final ValueChanged<Payment>? onCompleted;

  @override
  State<MobileMoneyCheckout> createState() => _MobileMoneyCheckoutState();
}

enum _Phase { editing, starting, waiting, successful, failed, stillPending }

class _MobileMoneyCheckoutState extends State<MobileMoneyCheckout> {
  late final TextEditingController _phone = TextEditingController(
    text: widget.initialPhoneNumber,
  );
  _Phase _phase = _Phase.editing;
  bool _showPhoneError = false;
  String? _error;
  // Kept while a retry may still need the same idempotency key.
  PaymentRequest? _request;
  PhoneNumber? _sentTo;
  // The amount of the payment in progress, even if the app changes [amount].
  int? _sentAmount;
  String? _sentCurrency;
  Payment? _payment;
  CancellationToken? _watch;

  @override
  void initState() {
    super.initState();
    _phone.addListener(_onPhoneChanged);
  }

  @override
  void didUpdateWidget(MobileMoneyCheckout old) {
    super.didUpdateWidget(old);
    if (old.client != widget.client ||
        old.amount != widget.amount ||
        old.currency != widget.currency ||
        old.description != widget.description ||
        !mapEquals(old.extra, widget.extra)) {
      _request = null;
    }
  }

  @override
  void dispose() {
    _watch?.cancel();
    _phone.dispose();
    super.dispose();
  }

  bool get _editable => _phase == _Phase.editing || _phase == _Phase.failed;

  void _onPhoneChanged() => setState(() {});

  Future<void> _pay() async {
    if (!_editable) {
      return;
    }
    final PhoneNumber number;
    try {
      number = PhoneNumber.parse(_phone.text);
    } on InvalidPhoneNumberException {
      setState(() => _showPhoneError = true);
      return;
    }
    // The same request after an error, so that a retry uses the same key.
    final previous = _request;
    final request = previous != null && previous.phoneNumber == number
        ? previous
        : PaymentRequest(
            phoneNumber: number,
            amount: widget.amount,
            description: widget.description,
            extra: widget.extra,
          );
    _request = request;
    setState(() {
      _phase = _Phase.starting;
      _error = null;
      _sentAmount = widget.amount;
      _sentCurrency = widget.currency;
    });

    final Payment payment;
    try {
      payment = await widget.client.startPayment(request);
    } on MerchantServerException catch (e) {
      if (!e.retryable) {
        _request = null;
      }
      if (mounted) {
        setState(() {
          _phase = _Phase.editing;
          _error = _startError(e);
        });
      }
      return;
    } catch (e, stack) {
      // For example the app's headers callback: never leave the form stuck.
      if (mounted) {
        setState(() {
          _phase = _Phase.editing;
          _error = widget.strings.serverError;
        });
      }
      _report(e, stack);
      return;
    }
    // Started: a new attempt needs a new key.
    _request = null;
    _sentTo = number;
    _payment = payment;
    if (!mounted) {
      return;
    }
    if (payment.isFinal) {
      _finish(payment);
      return;
    }
    setState(() => _phase = _Phase.waiting);
    await _follow(payment.reference);
  }

  Future<void> _follow(String reference) async {
    _watch?.cancel();
    final token = _watch = CancellationToken();
    try {
      final payment = await widget.client.waitForOutcome(
        reference,
        timeout: widget.timeout,
        pollInterval: widget.pollInterval,
        cancellationToken: token,
        onUpdate: (payment) => _payment = payment,
      );
      if (mounted) {
        _finish(payment);
      }
    } on PaymentWatchCancelledException {
      // The form was removed.
    } on PaymentTimeoutException {
      if (mounted) {
        setState(() {
          _phase = _Phase.stillPending;
          _error = null;
        });
      }
    } catch (e, stack) {
      if (mounted) {
        setState(() {
          _phase = _Phase.stillPending;
          _error = widget.strings.statusError;
        });
      }
      // A server error is expected; anything else is a bug to report.
      if (e is! MerchantServerException) {
        _report(e, stack);
      }
    }
  }

  static void _report(Object error, StackTrace stack) {
    FlutterError.reportError(
      FlutterErrorDetails(
        exception: error,
        stack: stack,
        library: 'sdk_mobile_money',
        context: ErrorDescription('while processing a payment'),
      ),
    );
  }

  void _checkAgain() {
    final payment = _payment;
    if (payment == null) {
      return;
    }
    setState(() {
      _phase = _Phase.waiting;
      _error = null;
    });
    _follow(payment.reference);
  }

  void _finish(Payment payment) {
    setState(() {
      _payment = payment;
      _phase = payment.status == PaymentStatus.successful
          ? _Phase.successful
          : _Phase.failed;
      _error = null;
    });
    widget.onCompleted?.call(payment);
  }

  String _startError(MerchantServerException e) {
    final strings = widget.strings;
    if (e.statusCode == null) {
      return strings.networkError;
    }
    if (e.errorCode == 'PAYMENT_REJECTED') {
      return strings.rejectedError;
    }
    if (e.errorCode == 'INVALID_PHONE_NUMBER') {
      final reason = InvalidPhoneNumberReason.fromCode(e.serverMessage);
      if (reason != null) {
        return strings.invalidPhone(reason);
      }
    }
    return strings.serverError;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final strings = widget.strings;

    PhoneNumber? number;
    InvalidPhoneNumberReason? phoneError;
    try {
      number = PhoneNumber.parse(_phone.text);
    } on InvalidPhoneNumberException catch (e) {
      phoneError = e.reason;
    }
    final table = widget.prefixTable ?? PrefixTable.cameroonDefault;
    final operator = number == null ? null : table.operatorOf(number);
    final amount = _editable
        ? strings.formatAmount(widget.amount, widget.currency)
        : strings.formatAmount(
            _sentAmount ?? widget.amount,
            _sentCurrency ?? widget.currency,
          );
    final panel = _panel(context);

    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          strings.amountLabel,
          style: theme.textTheme.labelLarge?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          amount,
          style: theme.textTheme.headlineMedium?.copyWith(
            color: theme.colorScheme.onSurface,
          ),
        ),
        const SizedBox(height: 16),
        TextField(
          controller: _phone,
          enabled: _editable,
          keyboardType: TextInputType.phone,
          autofillHints: const [AutofillHints.telephoneNumber],
          textInputAction: TextInputAction.done,
          onSubmitted: (_) => _pay(),
          decoration: InputDecoration(
            labelText: strings.phoneLabel,
            hintText: strings.phoneHint,
            border: const OutlineInputBorder(),
            errorText: _showPhoneError && phoneError != null
                ? strings.invalidPhone(phoneError)
                : null,
            suffixIcon: operator == null
                ? null
                : Padding(
                    padding: const EdgeInsetsDirectional.only(end: 12),
                    child: Semantics(
                      label: strings.operatorName(operator),
                      excludeSemantics: true,
                      child:
                          widget.operatorLogoBuilder?.call(context, operator) ??
                          _OperatorBadge(operator),
                    ),
                  ),
            suffixIconConstraints: const BoxConstraints(minHeight: 24),
          ),
        ),
        if (panel != null) ...[const SizedBox(height: 16), panel],
        if (_editable || _phase == _Phase.starting) ...[
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _phase == _Phase.starting ? null : _pay,
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(48),
            ),
            child: _phase == _Phase.starting
                ? const SizedBox.square(
                    dimension: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Text(strings.payButton(amount)),
          ),
        ],
      ],
    );
  }

  Widget? _panel(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final strings = widget.strings;
    final sentTo = _sentTo;
    switch (_phase) {
      case _Phase.waiting:
        return _StatusPanel(
          background: scheme.secondaryContainer,
          foreground: scheme.onSecondaryContainer,
          icon: Icons.phone_android,
          title: strings.confirmTitle,
          body: sentTo == null ? null : strings.confirmBody(_display(sentTo)),
          progress: true,
        );
      case _Phase.successful:
        return _StatusPanel(
          background: scheme.primaryContainer,
          foreground: scheme.onPrimaryContainer,
          icon: Icons.check_circle_outline,
          title: strings.successTitle,
        );
      case _Phase.failed:
        return _StatusPanel(
          background: scheme.errorContainer,
          foreground: scheme.onErrorContainer,
          icon: Icons.error_outline,
          title: strings.failedTitle,
          body: strings.failedBody,
        );
      case _Phase.stillPending:
        return _StatusPanel(
          background: scheme.surfaceContainerHighest,
          foreground: scheme.onSurface,
          icon: Icons.hourglass_empty,
          title: strings.stillPendingTitle,
          body: _error ?? strings.stillPendingBody,
          action: OutlinedButton(
            onPressed: _checkAgain,
            style: OutlinedButton.styleFrom(foregroundColor: scheme.onSurface),
            child: Text(strings.checkAgainButton),
          ),
        );
      case _Phase.editing:
      case _Phase.starting:
        final error = _error;
        if (error == null) {
          return null;
        }
        return _StatusPanel(
          background: scheme.errorContainer,
          foreground: scheme.onErrorContainer,
          icon: Icons.error_outline,
          title: error,
        );
    }
  }

  // +237 670 12 34 56, with no-break spaces.
  static String _display(PhoneNumber number) {
    final n = number.nationalNumber;
    return [
      '+${number.countryCode}',
      n.substring(0, 3),
      n.substring(3, 5),
      n.substring(5, 7),
      n.substring(7),
    ].join(' ');
  }
}

class _StatusPanel extends StatelessWidget {
  const _StatusPanel({
    required this.background,
    required this.foreground,
    required this.icon,
    required this.title,
    this.body,
    this.progress = false,
    this.action,
  });

  final Color background;
  final Color foreground;
  final IconData icon;
  final String title;
  final String? body;
  final bool progress;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final body = this.body;
    final action = this.action;
    return Semantics(
      container: true,
      liveRegion: true,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: background,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(icon, color: foreground),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      title,
                      style: textTheme.titleMedium?.copyWith(color: foreground),
                    ),
                  ),
                ],
              ),
              if (body != null) ...[
                const SizedBox(height: 8),
                Text(
                  body,
                  style: textTheme.bodyMedium?.copyWith(color: foreground),
                ),
              ],
              if (progress) ...[
                const SizedBox(height: 12),
                LinearProgressIndicator(
                  color: foreground,
                  backgroundColor: foreground.withValues(alpha: 0.2),
                ),
              ],
              if (action != null) ...[
                const SizedBox(height: 12),
                Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: action,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

// A neutral stand-in for the operator's logo: its name on its usual color,
// in black, readable in light and dark themes.
class _OperatorBadge extends StatelessWidget {
  const _OperatorBadge(this.operator);

  final MobileOperator operator;

  @override
  Widget build(BuildContext context) {
    final (String name, Color color) = switch (operator) {
      MobileOperator.mtn => ('MTN', const Color(0xFFFFCC00)),
      MobileOperator.orange => ('Orange', const Color(0xFFFF7900)),
    };
    return DecoratedBox(
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        child: Text(
          name,
          style: const TextStyle(
            color: Color(0xFF000000),
            fontSize: 12,
            fontWeight: FontWeight.w700,
          ),
        ),
      ),
    );
  }
}
