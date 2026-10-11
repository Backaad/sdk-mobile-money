import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:sdk_mobile_money/sdk_mobile_money.dart';

/// Address of the demo server, for example `http://192.168.1.20:8080`.
///
/// Set it with `--dart-define=DEMO_SERVER_URL=...`. By default, the app calls
/// the demo server on the computer that runs the emulator or the simulator.
const String demoServerUrl = String.fromEnvironment('DEMO_SERVER_URL');

void main() {
  runApp(DemoApp(client: MobileMoneyClient(paymentsUrl: paymentsUrl())));
}

/// The payments route of the demo server.
Uri paymentsUrl({String server = demoServerUrl}) {
  var base = server.trim();
  if (base.isEmpty) {
    // The Android emulator reaches the computer at 10.0.2.2.
    base = !kIsWeb && defaultTargetPlatform == TargetPlatform.android
        ? 'http://10.0.2.2:8080'
        : 'http://localhost:8080';
  }
  return Uri.parse('${base.replaceAll(RegExp(r'/+$'), '')}/api/payments');
}

/// The demo app: one screen with the payment form of `sdk_mobile_money`.
class DemoApp extends StatefulWidget {
  /// Creates the app.
  const DemoApp({super.key, required this.client});

  /// Client of the demo server.
  final MobileMoneyClient client;

  @override
  State<DemoApp> createState() => _DemoAppState();
}

class _DemoAppState extends State<DemoApp> {
  ThemeMode _themeMode = ThemeMode.system;
  bool _french = false;

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
            client: widget.client,
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
    required this.french,
    required this.onToggleLanguage,
    required this.dark,
    required this.onToggleTheme,
  });

  /// Client of the demo server.
  final MobileMoneyClient client;

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
  static const List<int> _amounts = [100, 500, 1000, 5000];

  int _amount = 500;
  // Changing it gives a fresh form, for a new payment.
  int _attempt = 0;

  void _onCompleted(Payment payment) {
    final paid = payment.status == PaymentStatus.successful;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          paid
              ? 'Payment ${payment.reference} received'
              : 'Payment ${payment.reference} failed',
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
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
                  'Server: ${widget.client.paymentsUrl}',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  'In simulation mode, the default of the demo server, a '
                  'payment always stays pending: nothing is sent to a '
                  'provider. Use a sandbox to see a payment through.',
                  style: theme.textTheme.bodyMedium,
                ),
                const SizedBox(height: 24),
                SegmentedButton<int>(
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
                  description: 'Demo payment',
                  strings: widget.french
                      ? MobileMoneyCheckoutStrings.french
                      : MobileMoneyCheckoutStrings.english,
                  onCompleted: _onCompleted,
                ),
                const SizedBox(height: 8),
                TextButton(
                  onPressed: () => setState(() => _attempt++),
                  child: const Text('New payment'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
