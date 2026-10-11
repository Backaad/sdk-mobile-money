import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:sdk_mobile_money/sdk_mobile_money.dart';

/// Address of the demo server, for example `http://192.168.1.20:8080`.
///
/// Set it with `--dart-define=DEMO_SERVER_URL=...`. By default, the app calls
/// the demo server on the computer that runs the emulator or the simulator.
const String demoServerUrl = String.fromEnvironment('DEMO_SERVER_URL');

void main() {
  runApp(DemoApp(server: serverUrl()));
}

/// The address of the demo server.
Uri serverUrl({String server = demoServerUrl}) {
  var base = server.trim();
  if (base.isEmpty) {
    // The Android emulator reaches the computer at 10.0.2.2.
    base = !kIsWeb && defaultTargetPlatform == TargetPlatform.android
        ? 'http://10.0.2.2:8080'
        : 'http://localhost:8080';
  }
  return Uri.parse(base.replaceAll(RegExp(r'/+$'), ''));
}

/// A route of the demo server, for example `api/payments`.
Uri route(Uri server, String path) =>
    server.replace(path: '${server.path}/$path');

/// The demo app: one screen with the payment form of `sdk_mobile_money`.
class DemoApp extends StatefulWidget {
  /// Creates the app for the demo server at [server].
  const DemoApp({super.key, required this.server, this.httpClient});

  /// Address of the demo server.
  final Uri server;

  /// HTTP client, replaced in tests.
  final http.Client? httpClient;

  @override
  State<DemoApp> createState() => _DemoAppState();
}

class _DemoAppState extends State<DemoApp> {
  late final http.Client _http = widget.httpClient ?? http.Client();
  late final MobileMoneyClient _client = MobileMoneyClient(
    paymentsUrl: route(widget.server, 'api/payments'),
    httpClient: _http,
  );
  ThemeMode _themeMode = ThemeMode.system;
  bool _french = false;
  // The currency the server charges in: XAF by default, EUR for the MTN MoMo
  // public sandbox. Shown with the amount.
  String _currency = 'XAF';

  @override
  void initState() {
    super.initState();
    _loadCurrency();
  }

  @override
  void dispose() {
    if (widget.httpClient == null) {
      _http.close();
    }
    super.dispose();
  }

  // Asks the server which currency it uses. Without an answer (server not
  // started yet), XAF stays, and the next "New payment" asks again.
  Future<void> _loadCurrency() async {
    try {
      final response = await _http
          .get(route(widget.server, 'api/config'))
          .timeout(const Duration(seconds: 10));
      final json = jsonDecode(utf8.decode(response.bodyBytes));
      if (response.statusCode == 200 &&
          json is Map<String, Object?> &&
          json['currency'] is String &&
          mounted) {
        setState(() => _currency = json['currency'] as String);
      }
    } on Exception {
      // Keep the current currency.
    }
  }

  @override
  Widget build(BuildContext context) {
    const seed = Color(0xFF00695C);
    return MaterialApp(
      title: 'Mobile Money demo',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: seed, brightness: Brightness.light),
      darkTheme: ThemeData(colorSchemeSeed: seed, brightness: Brightness.dark),
      themeMode: _themeMode,
      home: Builder(
        builder: (context) {
          final dark = Theme.of(context).brightness == Brightness.dark;
          return DemoPage(
            client: _client,
            currency: _currency,
            onNewPayment: _loadCurrency,
            french: _french,
            onToggleLanguage: () => setState(() => _french = !_french),
            dark: dark,
            onToggleTheme: () => setState(
              () => _themeMode = dark ? ThemeMode.light : ThemeMode.dark,
            ),
          );
        },
      ),
    );
  }
}

/// The screen: an amount to choose, then the payment form.
class DemoPage extends StatefulWidget {
  /// Creates the screen.
  const DemoPage({
    super.key,
    required this.client,
    required this.currency,
    required this.onNewPayment,
    required this.french,
    required this.onToggleLanguage,
    required this.dark,
    required this.onToggleTheme,
  });

  /// Client of the demo server.
  final MobileMoneyClient client;

  /// The currency the server charges in.
  final String currency;

  /// Called when a new payment is asked for.
  final VoidCallback onNewPayment;

  /// Whether the payment form speaks French.
  final bool french;

  /// Switches the language of the payment form.
  final VoidCallback onToggleLanguage;

  /// Whether the dark theme is shown.
  final bool dark;

  /// Switches between the light and dark themes.
  final VoidCallback onToggleTheme;

  @override
  State<DemoPage> createState() => _DemoPageState();
}

class _DemoPageState extends State<DemoPage> {
  // 25: the Campay sandbox probably refuses more (to be confirmed).
  static const List<int> _amounts = [25, 100, 500, 1000, 5000];

  int _amount = 500;
  // Changing it gives a fresh form, for a new payment.
  int _attempt = 0;

  void _onCompleted(Payment payment) {
    final paid = payment.status == PaymentStatus.successful;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(switch ((widget.french, paid)) {
          (false, true) => 'Payment ${payment.reference} received',
          (false, false) => 'Payment ${payment.reference} failed',
          (true, true) => 'Paiement ${payment.reference} reçu',
          (true, false) => 'Paiement ${payment.reference} échoué',
        }),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final french = widget.french;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Mobile Money demo'),
        actions: [
          TextButton(
            onPressed: widget.onToggleLanguage,
            child: Text(widget.french ? 'EN' : 'FR'),
          ),
          IconButton(
            tooltip: widget.dark ? 'Light theme' : 'Dark theme',
            onPressed: widget.onToggleTheme,
            icon: Icon(widget.dark ? Icons.light_mode : Icons.dark_mode),
          ),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Text(
                  'Server: ${widget.client.paymentsUrl} (${widget.currency})',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  french
                      ? 'En mode simulation, celui du serveur de démo par '
                            'défaut, un paiement reste toujours en attente : '
                            'rien n’est envoyé à un opérateur. Utilisez un bac '
                            'à sable pour voir un paiement aboutir.'
                      : 'In simulation mode, the default of the demo server, '
                            'a payment always stays pending: nothing is sent '
                            'to a provider. Use a sandbox to see a payment '
                            'through.',
                  style: theme.textTheme.bodyMedium,
                ),
                const SizedBox(height: 24),
                SegmentedButton<int>(
                  // Five amounts fit on a phone without the check mark.
                  showSelectedIcon: false,
                  segments: [
                    for (final amount in _amounts)
                      ButtonSegment(value: amount, label: Text('$amount')),
                  ],
                  selected: {_amount},
                  onSelectionChanged: (selected) =>
                      setState(() => _amount = selected.single),
                ),
                const SizedBox(height: 24),
                MobileMoneyCheckout(
                  key: ValueKey((_attempt, _amount)),
                  client: widget.client,
                  amount: _amount,
                  currency: widget.currency,
                  description: 'Demo payment',
                  strings: widget.french
                      ? MobileMoneyCheckoutStrings.french
                      : MobileMoneyCheckoutStrings.english,
                  onCompleted: _onCompleted,
                ),
                const SizedBox(height: 8),
                TextButton(
                  onPressed: () {
                    setState(() => _attempt++);
                    widget.onNewPayment();
                  },
                  child: Text(french ? 'Nouveau paiement' : 'New payment'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
