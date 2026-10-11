import 'dart:async';

/// Stops `MobileMoneyClient.waitForOutcome` from waiting.
///
/// Cancelling only stops the waiting: the payment itself is not cancelled, and
/// the customer may still approve it.
final class CancellationToken {
  final Completer<void> _cancelled = Completer<void>();

  /// Whether [cancel] was called.
  bool get isCancelled => _cancelled.isCompleted;

  /// Completes when [cancel] is called.
  Future<void> get whenCancelled => _cancelled.future;

  /// Stops the waiting. Calling it again has no effect.
  void cancel() {
    if (!_cancelled.isCompleted) {
      _cancelled.complete();
    }
  }
}
