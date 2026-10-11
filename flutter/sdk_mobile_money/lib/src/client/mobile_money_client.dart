import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../exceptions.dart';
import 'cancellation_token.dart';
import 'payment.dart';
import 'payment_request.dart';

/// Calls the merchant's server to start a payment and follow it.
///
/// This client never talks to a Mobile Money provider and never holds a
/// provider key: an app can be decompiled, so keys stay on the server. The
/// server exposes two routes (see the package README for the full contract):
///
/// * `POST <paymentsUrl>`, with an `Idempotency-Key` header, starts a payment;
/// * `GET <paymentsUrl>/<reference>` reads it.
///
/// Nothing is logged: neither numbers nor answers.
final class MobileMoneyClient {
  /// Creates a client for the payments route of the merchant's server, for
  /// example `https://shop.example/api/payments`.
  ///
  /// [headers] is called before each request, for example to send the user's
  /// session token. It must never return a provider key.
  ///
  /// Each HTTP call is abandoned after [requestTimeout]. Redirects are not
  /// followed (except on the web, where the browser decides).
  MobileMoneyClient({
    required this.paymentsUrl,
    http.Client? httpClient,
    this.headers,
    this.requestTimeout = const Duration(seconds: 20),
  }) : _http = httpClient ?? http.Client(),
       _ownsHttpClient = httpClient == null {
    if (!paymentsUrl.isScheme('https') && !paymentsUrl.isScheme('http')) {
      throw ArgumentError.value(
        paymentsUrl,
        'paymentsUrl',
        'must be an http or https URL',
      );
    }
    if (paymentsUrl.hasQuery || paymentsUrl.hasFragment) {
      throw ArgumentError.value(
        paymentsUrl,
        'paymentsUrl',
        'must not have a query or a fragment',
      );
    }
    if (requestTimeout <= Duration.zero) {
      throw ArgumentError.value(
        requestTimeout,
        'requestTimeout',
        'must be positive',
      );
    }
  }

  /// URL of the payments route of the merchant's server.
  final Uri paymentsUrl;

  /// Extra headers for each request, for example `Authorization` with the
  /// user's session token.
  final FutureOr<Map<String, String>> Function()? headers;

  /// How long each HTTP call may take.
  final Duration requestTimeout;

  final http.Client _http;
  final bool _ownsHttpClient;

  /// Starts a payment. The customer then confirms it on the phone.
  ///
  /// Returns the payment, usually [PaymentStatus.pending]. Throws a
  /// [MerchantServerException] when the server cannot be reached or refuses
  /// the request; when it is [MerchantServerException.retryable], send the
  /// same [request] again: its idempotency key guarantees that the customer is
  /// asked to pay only once.
  Future<Payment> startPayment(PaymentRequest request) async {
    final response = await _send(
      'POST',
      paymentsUrl,
      requestHeaders: {
        'Content-Type': 'application/json',
        'Idempotency-Key': request.idempotencyKey,
      },
      body: jsonEncode(request.toJson()),
    );
    return _readPayment(response);
  }

  /// Reads a payment. The server asks the provider when it is still pending.
  ///
  /// Throws a [MerchantServerException] when the server cannot be reached or
  /// answers with an error, for example `404` for an unknown reference.
  Future<Payment> getPayment(String reference) async {
    if (reference.isEmpty) {
      throw ArgumentError.value(reference, 'reference', 'must not be empty');
    }
    final url = paymentsUrl.replace(
      pathSegments: [
        ...paymentsUrl.pathSegments.where((s) => s.isNotEmpty),
        reference,
      ],
    );
    return _readPayment(await _send('GET', url));
  }

  /// Reads a payment every [pollInterval] until it is settled, then returns
  /// it.
  ///
  /// [onUpdate] receives each successful read. A transient error (network
  /// error, timeout, 5xx, unreadable answer) never settles the payment: it is
  /// read again at the next interval. Other errors, such as `404`, are thrown
  /// as a [MerchantServerException].
  ///
  /// Throws a [PaymentTimeoutException] when the payment is still pending
  /// after [timeout] (counted from the call, the last read may end up to
  /// [requestTimeout] later). It is not a failure: the customer may still
  /// approve the payment.
  ///
  /// Throws a [PaymentWatchCancelledException] as soon as [cancellationToken]
  /// is cancelled. The payment itself is not cancelled.
  Future<Payment> waitForOutcome(
    String reference, {
    Duration timeout = const Duration(minutes: 3),
    Duration pollInterval = const Duration(seconds: 3),
    CancellationToken? cancellationToken,
    void Function(Payment payment)? onUpdate,
  }) async {
    if (timeout <= Duration.zero) {
      throw ArgumentError.value(timeout, 'timeout', 'must be positive');
    }
    if (pollInterval <= Duration.zero) {
      throw ArgumentError.value(
        pollInterval,
        'pollInterval',
        'must be positive',
      );
    }
    final token = cancellationToken ?? CancellationToken();
    final deadline = Completer<void>();
    final deadlineTimer = Timer(timeout, deadline.complete);
    Payment? last;
    try {
      while (true) {
        final read = await _raceCancellation(_tryRead(reference), token);
        if (token.isCancelled) {
          throw PaymentWatchCancelledException(reference, last);
        }
        if (read != null) {
          last = read;
          onUpdate?.call(read);
          if (read.isFinal) {
            return read;
          }
        }
        if (deadline.isCompleted) {
          throw PaymentTimeoutException(reference, last);
        }
        await _pause(pollInterval, [deadline.future, token.whenCancelled]);
        if (token.isCancelled) {
          throw PaymentWatchCancelledException(reference, last);
        }
      }
    } finally {
      deadlineTimer.cancel();
    }
  }

  /// Closes the HTTP client this client created. A client passed to the
  /// constructor is left open.
  void close() {
    if (_ownsHttpClient) {
      _http.close();
    }
  }

  // Null when the read failed with a transient error.
  Future<Payment?> _tryRead(String reference) async {
    try {
      return await getPayment(reference);
    } on MerchantServerException catch (e) {
      if (e.retryable) {
        return null;
      }
      rethrow;
    }
  }

  static Future<T?> _raceCancellation<T>(
    Future<T> work,
    CancellationToken token,
  ) {
    if (token.isCancelled) {
      // The result no longer matters, but its error must not go unhandled.
      work.ignore();
      return Future.value();
    }
    final result = Completer<T?>();
    work.then(
      (value) {
        if (!result.isCompleted) result.complete(value);
      },
      onError: (Object error, StackTrace stackTrace) {
        if (!result.isCompleted) result.completeError(error, stackTrace);
      },
    );
    token.whenCancelled.then((_) {
      if (!result.isCompleted) result.complete(null);
    });
    return result.future;
  }

  // Waits for [duration], or less if one of [interruptions] completes.
  static Future<void> _pause(
    Duration duration,
    List<Future<void>> interruptions,
  ) {
    final done = Completer<void>();
    final timer = Timer(duration, () {
      if (!done.isCompleted) done.complete();
    });
    for (final interruption in interruptions) {
      interruption.then((_) {
        timer.cancel();
        if (!done.isCompleted) done.complete();
      });
    }
    return done.future;
  }

  Future<http.Response> _send(
    String method,
    Uri url, {
    Map<String, String> requestHeaders = const {},
    String? body,
  }) async {
    final custom = await headers?.call() ?? const <String, String>{};
    final abort = Completer<void>();
    final request =
        http.AbortableRequest(method, url, abortTrigger: abort.future)
          ..followRedirects = false
          ..headers.addAll(custom)
          ..headers['Accept'] = 'application/json'
          ..headers.addAll(requestHeaders);
    if (body != null) {
      request.body = body;
    }
    try {
      return await _http
          .send(request)
          .then(http.Response.fromStream)
          .timeout(
            requestTimeout,
            onTimeout: () {
              abort.complete();
              throw const MerchantServerException(
                'The server did not answer in time',
                retryable: true,
              );
            },
          );
    } on MerchantServerException {
      rethrow;
    } on Exception {
      // ClientException, and the TLS errors some clients do not wrap. The
      // request may have reached the server.
      throw const MerchantServerException(
        'The server could not be reached',
        retryable: true,
      );
    }
  }

  Payment _readPayment(http.Response response) {
    final status = response.statusCode;
    final json = _decode(response);
    if (status >= 200 && status < 300) {
      if (json != null) {
        try {
          return Payment.fromJson(json);
        } on FormatException {
          // Handled below.
        }
      }
      // The server may have started the payment: the same request returns it.
      throw MerchantServerException(
        'The server answered $status without a readable payment',
        statusCode: status,
        retryable: true,
      );
    }
    final errorCode = json?['error'];
    final message = json?['message'];
    throw MerchantServerException(
      'The server answered $status'
      '${errorCode is String ? ' ($errorCode)' : ''}',
      statusCode: status,
      errorCode: errorCode is String ? errorCode : null,
      serverMessage: message is String ? message : null,
      retryable: _isRetryable(status, errorCode),
    );
  }

  static bool _isRetryable(int status, Object? errorCode) =>
      status >= 500 ||
      status == 408 ||
      status == 429 ||
      (status == 409 && errorCode == 'REQUEST_IN_PROGRESS');

  // JSON is UTF-8 whatever the Content-Type says: Response.body would fall
  // back to Latin-1 when there is no charset.
  static Map<String, Object?>? _decode(http.Response response) {
    try {
      final decoded = jsonDecode(utf8.decode(response.bodyBytes));
      return decoded is Map<String, Object?> ? decoded : null;
    } on FormatException {
      return null;
    }
  }
}
