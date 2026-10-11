# sdk-mobile-money

Encaissez et versez de l'argent par Mobile Money au Cameroun avec une seule
interface, quel que soit l'opérateur derrière : MTN MoMo, Campay, NotchPay ou
Tara.

[![CI](https://github.com/Backaad/sdk-mobile-money/actions/workflows/ci.yml/badge.svg)](https://github.com/Backaad/sdk-mobile-money/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/Backaad/sdk-mobile-money/graph/badge.svg)](https://codecov.io/gh/Backaad/sdk-mobile-money)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.backaad/sdk-mobile-money-core)](https://central.sonatype.com/artifact/io.github.backaad/sdk-mobile-money-core)
[![pub.dev](https://img.shields.io/pub/v/sdk_mobile_money)](https://pub.dev/packages/sdk_mobile_money)
[![Licence : MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

*[Read in English](README.md)*

> **Publié** sur Maven Central et pub.dev : les badges ci-dessus indiquent les
> dernières versions.

<p align="center">
  <img src="flutter/sdk_mobile_money/screenshots/demo.gif" width="300"
       alt="L'application de démo : un numéro est saisi, le badge MTN apparaît, le client doit confirmer sur son téléphone, puis le paiement réussit ; de même en thème sombre et en français avec un numéro Orange.">
  <br>
  <sub>L'application de démo Flutter, rendue par le moteur de test de Flutter
  face à un faux serveur qui confirme le paiement, comme le ferait un bac à
  sable. Ce n'est pas un enregistrement d'écran.</sub>
</p>

## Pourquoi ce SDK

- **Une seule interface** pour MTN MoMo, Campay, NotchPay et Tara : on change d'opérateur par la configuration, pas par le code.
- **Les clés restent sur votre serveur** : le paquet Flutter ne parle qu'à votre serveur, jamais à un opérateur.
- **Les webhooks sont vérifiés**, puis le statut est relu chez l'opérateur avant de confirmer un paiement.
- **Rien n'est débité deux fois** : chaque paiement a une clé d'idempotence, et une demande dont la réponse s'est perdue n'est jamais renvoyée.

## Les opérateurs

| Opérateur | Collecte | Versement | Statut interrogeable | Webhooks | Bac à sable | Vérifié avec |
|---|---|---|---|---|---|---|
| [Campay](docs/providers/campay.md) | oui | oui | oui | JWT HS256, puis statut relu | oui | les SDK de Campay ; détails à confirmer |
| [MTN MoMo](docs/providers/mtn-momo.md) | oui | oui | oui | non signés, statut relu | oui, public | à confirmer |
| [NotchPay](docs/providers/notchpay.md) | oui | oui | oui | HMAC-SHA256, puis statut relu | clés de test | à confirmer |
| [Tara](docs/providers/tara.md) (expérimental) | oui | non | non | URL de rappel à usage unique | à confirmer | à confirmer |
| Orange Money en direct | prévu | prévu | | | | demande un partenariat |

Le projet n'a encore lancé aucun opérateur contre son bac à sable : il n'a pas
de clés à lui. En attendant, chaque opérateur est testé contre un serveur
simulé, construit d'après sa documentation. La bascule vers un autre opérateur
n'a lieu que si vous la configurez, et seulement quand rien n'a atteint le
premier. Tant qu'Orange Money en direct n'existe pas, les clients Orange Money
passent par un agrégateur : NotchPay a un canal Orange ; Campay et Tara sont à
confirmer.

## Démarrage rapide

### Votre serveur, avec Spring Boot

1. Ajoutez le starter (Spring Boot 4, Java 17 ou plus) :

   ```xml
   <dependency>
       <groupId>io.github.backaad</groupId>
       <artifactId>sdk-mobile-money-spring-boot-starter</artifactId>
       <version>0.1.2</version>
   </dependency>
   ```

2. Configurez un opérateur. Les clés viennent de l'environnement, jamais d'un
   fichier versionné :

   ```yaml
   mobile-money:
     callback-base-url: https://shop.example/mobile-money/webhooks
     campay:
       environment: sandbox
       username: ${CAMPAY_USERNAME}
       password: ${CAMPAY_PASSWORD}
       webhook-key: ${CAMPAY_WEBHOOK_KEY}
   ```

   Pas encore de compte ? `mobile-money.simulation.enabled: true` ne parle à
   personne et ne confirme jamais un paiement. Une configuration incomplète ou
   dangereuse empêche le démarrage, avec un message qui nomme la propriété à
   corriger.

3. Lancez un paiement :

   ```java
   @RestController
   class PaymentController {

       private final MobileMoney mobileMoney;
       private final Orders orders;

       PaymentController(MobileMoney mobileMoney, Orders orders) {
           this.mobileMoney = mobileMoney;
           this.orders = orders;
       }

       @PostMapping("/api/payments")
       Map<String, Object> pay(@RequestHeader("Idempotency-Key") String key, @RequestBody PayBody body) {
           Order order = orders.get(body.orderId()); // le montant vient de la commande, jamais de l'application
           Transaction transaction = mobileMoney.collect(PaymentRequest.builder()
                   .idempotencyKey(key)
                   .amount(order.amount())
                   .phoneNumber(body.phoneNumber())
                   .merchantReference(order.reference())
                   .description("Order " + order.reference())
                   .build());
           return Map.of("reference", transaction.merchantReference(), "status", transaction.status().name());
       }
   }
   ```

4. Recevez l'issue. Le starter reçoit les webhooks sur
   `/mobile-money/webhooks/<opérateur>`, les vérifie, relit le statut, puis
   publie un événement :

   ```java
   @EventListener
   void onOutcome(PaymentOutcomeEvent event) {
       if (event instanceof PaymentConfirmedEvent) {
           // marquer la commande event.merchantReference() comme payée, une seule fois
       }
   }
   ```

Plus de détails dans [docs/spring-boot.md](docs/spring-boot.md) (en anglais).
Sans Spring, utilisez directement la bibliothèque du cœur : commencez par
`MobileMoney.builder()` (voir sa Javadoc).

### Votre application, avec Flutter

```yaml
dependencies:
  sdk_mobile_money: ^0.1.2
```

```dart
final client = MobileMoneyClient(
  paymentsUrl: Uri.parse('https://shop.example/api/payments'),
);

MobileMoneyCheckout(
  client: client,
  amount: 1500,
  strings: MobileMoneyCheckoutStrings.french,
  extra: {'orderId': '42'},
  onCompleted: (payment) {
    // PaymentStatus.successful ou PaymentStatus.failed
  },
)
```

Le formulaire valide le numéro avec les mêmes règles que le serveur, affiche
l'opérateur détecté, demande au client de confirmer sur son téléphone, puis
suit le paiement. Le README du paquet décrit le
[contrat avec le serveur](flutter/sdk_mobile_money/README.md#the-server-contract)
et l'usage du client sans le formulaire.

### Essayer les démos

```sh
(cd examples/spring-boot-demo && docker compose up --build)   # serveur et Redis, en simulation
(cd examples/flutter-demo && flutter run)                     # application, sur un émulateur
```

Voir [le serveur de démo](examples/spring-boot-demo) et
[l'application de démo](examples/flutter-demo).

## Sécurité

- Les clés des opérateurs ne vivent que sur le serveur, dans des variables
  d'environnement ou un coffre à secrets. Le paquet Flutter n'en contient
  jamais.
- Un webhook n'est cru qu'après la vérification de sa signature (comparée en
  temps constant) ou de son URL à usage unique, puis la relecture du statut
  chez l'opérateur qui l'a envoyé, quand l'opérateur le permet.
- Une erreur passagère pendant la lecture d'un statut laisse le paiement en
  attente ; elle ne le marque jamais comme échoué. La simulation ne confirme
  jamais un paiement.
- Les journaux ne contiennent jamais de clé, de jeton, de signature, ni de
  corps qui en contient.

Chacune des onze règles derrière ces points a ses tests : voir
[docs/security.md](docs/security.md) (en anglais). Pour signaler une faille,
suivez [SECURITY.md](SECURITY.md), jamais une issue publique.

## Feuille de route

- **0.1.x** : premières versions, sur Maven Central et pub.dev.
- Confirmer tout ce qui est marqué « à confirmer » avec les bacs à sable et la
  documentation officielle : les détails des opérateurs, et la table des
  préfixes par défaut avec le plan de numérotation de l'ART, le régulateur
  camerounais.
- Le `XOF` et d'autres pays ; l'architecture est prête pour eux.
- Orange Money en direct, quand un partenariat le permettra.
- Pas prévu pour l'instant, peut-être plus tard : le paiement par carte, les
  abonnements, les remboursements automatiques, un tableau de bord web, des SDK
  pour d'autres langages (Node, PHP, Python).

## Contribuer

Les contributions sont les bienvenues, en particulier les nouveaux opérateurs :
lisez [CONTRIBUTING.md](CONTRIBUTING.md) et le
[code de conduite](CODE_OF_CONDUCT.md) (en anglais).

| Chemin | Contenu |
|---|---|
| `java/sdk-mobile-money-core` | bibliothèque serveur Java 17+, sans Spring |
| `java/sdk-mobile-money-spring-boot-starter` | intégration Spring Boot |
| `flutter/sdk_mobile_money` | paquet Flutter ; ne parle qu'au serveur du marchand |
| `examples/spring-boot-demo` | serveur de démo, en simulation par défaut |
| `examples/flutter-demo` | application de démo qui paie via le serveur de démo |
| `testdata/` | numéros de référence partagés par les tests Java et Dart |
| `docs/` | [architecture](docs/architecture.md), [sécurité](docs/security.md), [opérateurs](docs/providers), [publication](docs/releasing.md) |

```sh
(cd java && ./mvnw verify)                                                         # Java
(cd flutter/sdk_mobile_money && flutter pub get && flutter analyze && flutter test) # paquet Flutter
(cd examples/flutter-demo && flutter pub get && flutter analyze && flutter test)    # application de démo
```

## Licence

[MIT](LICENSE)
