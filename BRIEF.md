# sdk-mobile-money — descriptif du projet

Je veux créer un SDK open source qui permet à n'importe quel développeur
d'**encaisser** et de **verser** de l'argent par Mobile Money au Cameroun, avec une
seule interface, quel que soit l'opérateur derrière. Aujourd'hui, chacun refait ces
intégrations dans son coin. Les documentations sont incomplètes, chaque opérateur a
ses habitudes, et les webhooks sont souvent mal sécurisés. Je veux une bibliothèque
propre, sûre et bien documentée, que les développeurs aient envie d'utiliser et de
recommander.

On commence par le Cameroun (MTN MoMo, Campay, NotchPay, Tara). L'architecture doit
permettre d'ajouter ensuite d'autres pays et d'autres opérateurs.

## Le principe de base

Les clés des opérateurs restent **sur le serveur**. Une application mobile se
décompile, donc une clé embarquée dedans finit volée. Le cœur du SDK est une
bibliothèque serveur en Java. Le paquet Flutter, lui, ne parle qu'au serveur du
marchand, jamais directement aux opérateurs.

## Ce que contient le dépôt

```
sdk-mobile-money/
├── java/
│   ├── sdk-mobile-money-core/                  # bibliothèque Java 17+, sans Spring
│   ├── sdk-mobile-money-spring-boot-starter/   # intégration Spring Boot 3
│   └── pom.xml
├── flutter/
│   └── sdk_mobile_money/                       # paquet pour pub.dev
├── examples/
│   ├── spring-boot-demo/
│   └── flutter-demo/
├── docs/
├── README.md, README.fr.md
├── CONTRIBUTING.md, CODE_OF_CONDUCT.md, SECURITY.md, CHANGELOG.md, LICENSE (MIT)
└── .env.example
```

Noms proposés : groupe Maven `io.github.backaad`, paquet Java
`io.github.backaad.mobilemoney`, paquet Dart `sdk_mobile_money`.

## Les briques

### Le cœur Java

Une bibliothèque légère : le client HTTP de Java, Jackson pour le JSON, SLF4J pour
les journaux, et rien d'autre.

Elle expose un modèle commun à tous les opérateurs :
- une demande de paiement : montant entier, devise (`XAF` d'abord, `XOF` prévu),
  numéro, référence du marchand, description, URL de rappel ;
- une demande de versement, sur le même modèle, avec le nom du bénéficiaire ;
- un statut : `PENDING`, `SUCCESSFUL` ou `FAILED`, avec le statut brut de l'opérateur
  pour le diagnostic ;
- un événement de webhook, qui indique clairement s'il a été vérifié ;
- les capacités de chaque opérateur : collecte, versement, statut interrogeable, et
  type de signature des webhooks.

Chaque opérateur implémente la même interface. Une fabrique choisit le bon selon la
configuration. Si un opérateur est indisponible, on peut basculer vers un autre,
mais seulement si le marchand l'a configuré, et le résultat doit dire qui a
réellement traité le paiement.

Les erreurs sont typées : numéro invalide, authentification refusée, opérateur
indisponible, paiement refusé, webhook non vérifié.

Le module des numéros normalise toutes les écritures courantes (`+237 6 70…`,
`670…`, `237670…`) et devine l'opérateur d'après le préfixe. La table des préfixes
doit être configurable. Sa valeur par défaut est à vérifier auprès de l'ARTP. Point
de départ : MTN 650-654, 670-679, 680-689 ; Orange 655-659, 690-699.

Toute opération qui débite ou verse passe par une clé d'idempotence valable 24 h.
Si la même demande revient (double appui, nouvelle tentative réseau), on renvoie le
premier résultat sans débiter une seconde fois. Une implémentation en mémoire est
fournie dans le cœur.

Il faut enfin un mode simulation pour développer sans compte. Ce mode ne parle à
personne et ne confirme jamais un paiement. Pour tester un parcours complet, on
utilise un bac à sable.

### Le starter Spring Boot

- La configuration passe par des propriétés `mobile-money.*`. Au démarrage, elles
  sont vérifiées avec des messages clairs, par exemple : opérateur activé sans clé,
  URL de rappel en `localhost` en production.
- Une implémentation Redis de l'idempotence, active si Redis est présent.
- Une aide pour les webhooks. Elle reçoit le corps brut et les en-têtes, vérifie la
  signature, relit le statut chez l'opérateur quand c'est possible, puis publie un
  événement Spring (paiement confirmé ou échoué). Le marchand n'a qu'à écouter
  l'événement.
- Une tâche planifiée facultative, désactivée par défaut, qui relit les paiements
  restés en attente.

### Le paquet Flutter

- La validation et la normalisation du numéro, et la détection de l'opérateur, avec
  les mêmes règles que le cœur Java.
- Un client qui appelle le serveur du marchand : lancer un paiement, puis suivre son
  statut jusqu'à une issue, avec un délai maximal et la possibilité d'annuler.
- Un composant d'interface simple et facultatif :
  - le champ du numéro ;
  - le logo de l'opérateur détecté ;
  - le montant ;
  - le bouton ;
  - le message « confirmez sur votre téléphone ».

  Il doit fonctionner en thème clair et en thème sombre.

### Les exemples

Un petit serveur Spring Boot avec trois routes (lancer un paiement, lire son statut,
recevoir les webhooks), en simulation par défaut. Une application Flutter qui s'en
sert. Un `docker-compose.yml` qui lance le serveur et Redis.

## Les opérateurs

Voici ce que je sais de chacun. Vérifie tout dans la documentation officielle avant
de coder, et note les différences dans `docs/providers/`.

### Campay — à faire en premier

- Adresses : `https://demo.campay.net` (bac à sable) et `https://www.campay.net`.
- Jeton :
  - on l'obtient par `POST /api/token/` avec `username` et `password` ; la réponse
    donne `token` et `expires_in` ;
  - on le garde en cache jusqu'à expiration, moins une minute de marge ;
  - sur un refus 401, on le renouvelle et on rejoue une seule fois.
- L'en-tête est `Authorization: Token <jeton>` (avec « Token », **pas** « Bearer »).
- Collecte : `POST /api/collect/` (`amount` en texte, `currency`, `from`,
  `description`, `external_reference`, `redirect_url`). La réponse donne `reference`.
- Versement : `POST /api/withdraw/` (`amount`, `currency`, `to`, `description`,
  `external_reference`).
- Statut : `GET /api/transaction/{reference}/`.
- Les numéros s'écrivent `237XXXXXXXXX`, sans `+`. Un numéro à 9 chiffres est refusé.
- Webhook :
  - il peut arriver en GET ou en POST, selon le réglage du tableau de bord : il faut
    accepter les deux ;
  - le champ `signature` est un JWT HS256, signé avec la clé de webhook de
    l'application ;
  - on refuse les jetons non signés et les jetons expirés.

### NotchPay

- Adresse : `https://api.notchpay.co`. Clés de test en `pk_test_…`.
- La collecte se fait en deux appels :
  1. `POST /payments` (montant, devise, référence, description, `callback`,
     `customer.phone`) pour créer la transaction ;
  2. `POST /payments/{reference}` avec le canal (`cm.mtn` ou `cm.orange`) et le
     numéro, ce qui déclenche la demande de confirmation sur le téléphone.
- Statut : `GET /payments/{reference}`.
  - `complete`, `completed`, `successful` et `success` signifient payé ;
  - `failed`, `canceled`, `cancelled`, `rejected` et `expired` signifient échoué ;
  - tout le reste est en attente.
- Versement : `POST /transfers` (montant, devise, référence, bénéficiaire avec canal,
  numéro et nom). Les transferts doivent être activés sur le compte.
- La clé publique va dans `Authorization`. Pour les opérations sensibles (charge,
  transferts), vérifie dans la documentation où va la clé privée : peut-être un
  en-tête dédié.
- Webhook : en-tête `x-notch-signature`, qui contient un HMAC-SHA256 en hexadécimal
  du corps brut.

### MTN MoMo (API directe)

- Bac à sable public : `https://sandbox.momodeveloper.mtn.com`. C'est le seul
  opérateur testable entièrement sans contrat : il sert pour les tests d'intégration.
- Le flux de collecte :
  1. obtenir un jeton par `POST /collection/token/`, en authentification Basic avec
     l'utilisateur et la clé d'API, plus `Ocp-Apim-Subscription-Key` ;
  2. lancer la collecte par `POST /collection/v1_0/requesttopay`, avec les en-têtes
     `Authorization: Bearer`, `X-Reference-Id` (un UUID v4 qui sert de référence),
     `X-Target-Environment` et `X-Callback-Url` ;
  3. lire le statut par `GET /collection/v1_0/requesttopay/{referenceId}`.
- Le versement suit le même schéma sous `/disbursement/`, avec son propre jeton.
- À confirmer : la devise acceptée par le bac à sable (peut-être seulement `EUR`), et
  le nom de l'environnement de production au Cameroun.
- MTN ne signe pas ses rappels : on relit toujours le statut avant de confirmer.

### Tara (Tara Money, par Dikalo) — en expérimental

- Adresse : `https://www.dklo.co/api/tara/`.
- Collecte : `POST /mobilepay`, avec `apiKey`, `businessId`, `productId` (référence
  unique), `productName`, `network` (vide pour la détection automatique, `wave` pour
  Wave au Sénégal et en Côte d'Ivoire), `productPrice` (entier), `phoneNumber`
  (`237…`) et `webHookUrl`.
  - La réponse donne `status` (`SUCCESS` si la demande est acceptée), `message` et
    `vendor`, mais pas d'identifiant de transaction.
- Versement : `POST /payout/create`, avec `apiKey`, `businessId`, `receiverName`,
  `paymentMethod`, `receiverPhoneNumber`, `receiverId` et `amount`. À confirmer.
- Tara n'a **pas d'interrogation de statut**, et ses **webhooks ne sont pas signés**.
  Il faut donc protéger la confirmation autrement :
  - une URL de rappel unique et imprévisible par paiement (jeton aléatoire de
    128 bits, à usage unique, avec une expiration) ;
  - la vérification que le rappel correspond au paiement attendu : référence,
    montant, numéro ;
  - une liste d'adresses IP autorisées, facultative.
- Il faut répondre vite (`200`) : un rappel trop lent n'est pas renvoyé.
- La clé d'API voyage dans le corps de la requête : on ne journalise jamais ce corps.
- Le README le présente comme expérimental, avec ces limites expliquées clairement.

### Orange Money en direct — plus tard

Son API marchande demande un partenariat. En attendant, Orange passe par Campay,
NotchPay ou Tara. Le README l'annonce comme prévu.

### Le tableau du README

| Opérateur | Collecte | Versement | Statut interrogeable | Webhook signé | Bac à sable |
|---|---|---|---|---|---|
| Campay | oui | oui | oui | JWT HS256 | oui |
| NotchPay | oui | oui | oui | HMAC-SHA256 | oui |
| MTN MoMo | oui | oui | oui | non, statut relu | oui, public |
| Tara (expérimental) | oui | à confirmer | non | non, URL à jeton | à confirmer |
| Orange Money direct | prévu | prévu | | | |

Ce tableau doit refléter ce qui a vraiment été vérifié.

## Fiabilité et sécurité

C'est ce qui fera la différence avec les bibliothèques existantes. Chaque point
ci-dessous doit avoir son test.

1. On ne croit jamais le statut annoncé par un webhook. Quand l'opérateur permet de
   relire le statut, on le relit avant de confirmer.
2. Le webhook d'un opérateur ne peut confirmer que les transactions de cet
   opérateur.
3. Les signatures se comparent en temps constant.
4. Une erreur passagère pendant la lecture d'un statut (réseau, erreur 5xx, délai
   dépassé, réponse vide) laisse le paiement en attente. Elle ne le marque jamais
   comme échoué.
5. Les jetons d'accès sont gardés en cache jusqu'à leur expiration, puis renouvelés,
   y compris après un refus 401.
6. Le format des numéros propre à chaque opérateur est centralisé et testé.
7. Aucune clé, aucun jeton, aucune signature ni aucun corps qui en contient ne finit
   dans les journaux.
8. La simulation ne confirme jamais un paiement.
9. Rien ne débite ni ne verse deux fois pour une même clé d'idempotence.
10. Une configuration incomplète ou dangereuse empêche le démarrage, avec un message
    clair.
11. Aucune bascule silencieuse vers un autre opérateur.

## Tests et intégration continue

- Les tests de chaque opérateur utilisent WireMock, avec des réponses tirées de la
  documentation. Ils couvrent :
  - le succès et le refus ;
  - un refus 401 suivi du renouvellement du jeton ;
  - une erreur 5xx et une réponse vide ;
  - un statut inconnu.
- Les tests contre les bacs à sable (MTN d'abord, puis Campay) ne tournent que si les
  clés sont présentes. Sinon, ils sont ignorés proprement, sans échouer.
- Côté Flutter : des tests unitaires, et un test de rendu du composant en thème
  clair et en thème sombre.
- Les numéros de référence sont dans un fichier JSON partagé, lu par les tests Java
  et par les tests Dart, pour que les deux côtés suivent exactement les mêmes règles.
- GitHub Actions vérifie :
  - la compilation et les tests Java (JDK 17 et 21) ;
  - `flutter analyze` et `flutter test` ;
  - la couverture, affichée en badge.
- Le versionnage est sémantique et le `CHANGELOG` tenu à jour. Première version :
  `0.1.0`.

## La documentation

Le README en anglais est la vitrine du projet. Dans l'ordre :
1. une phrase qui dit ce que fait le SDK ;
2. les badges ;
3. une animation de la démo Flutter (laisse-moi l'emplacement si tu ne peux pas la
   faire) ;
4. pourquoi ce SDK, en quatre lignes ;
5. le tableau des opérateurs ;
6. un démarrage en cinq minutes, côté Spring Boot puis côté Flutter ;
7. la partie sécurité ;
8. la feuille de route ;
9. comment contribuer.

`README.fr.md` reprend le même contenu en français. Dans `docs/` : l'architecture
(avec un diagramme Mermaid), la sécurité, et une page par opérateur. Il faut aussi
des modèles d'issues (bogue, nouvel opérateur, question) et un `SECURITY.md` pour
signaler une faille en privé.

## Le déroulé

On avance par étapes. Chaque étape a sa branche et sa pull request.

1. **Le socle** :
   - l'arborescence et la construction Maven ;
   - le paquet Flutter vide ;
   - la CI verte ;
   - la licence, `.env.example` ;
   - un `CLAUDE.md` en anglais qui reprend les règles durables du projet.
2. **Le cœur et Campay** :
   - le modèle commun, les numéros, l'idempotence ;
   - Campay complet ;
   - les tests de fiabilité.
3. **NotchPay, MTN MoMo et Tara**, avec la fabrique, la bascule configurable et le
   test du bac à sable MTN.
4. **Le starter Spring Boot**, avec l'exemple serveur et le docker-compose.
5. **Le paquet Flutter**, avec l'application d'exemple.
6. **La documentation complète** et la préparation des publications sur Maven
   Central et pub.dev.

À la fin de chaque étape, fais-moi un court bilan :
- ce qui est fait ;
- ce qui est vérifié, et comment ;
- ce qui reste à confirmer ;
- ce que je dois faire de mon côté.

## Ce que je fais moi-même

- Je crée les comptes : bacs à sable MTN, Campay, NotchPay et Tara, Sonatype pour
  Maven Central, et pub.dev.
- J'ajoute les clés dans les secrets GitHub Actions, sous les noms que tu auras
  prévus dans `.env.example`.
- J'enregistre l'animation de la démo si besoin.
- J'annonce le projet une fois la `0.1.0` publiée.

## Comment on travaille ensemble

- Écris-moi en français. Le code, les commentaires, la documentation et les commits
  sont en anglais, sauf `README.fr.md`.
- Avant de faire quelque chose d'important, dis-le-moi en une phrase.
- Si tu as une idée qui n'est pas dans ce descriptif, surtout côté interface,
  propose-la avant de la coder.
- Aucune clé ne doit apparaître dans le dépôt ni dans nos échanges.
- Ne présente jamais comme vérifié quelque chose qui ne l'est pas : un point non
  confirmé dans la documentation reste « à confirmer ».

## Hors périmètre pour l'instant

Le paiement par carte, les abonnements, les remboursements automatiques, un tableau
de bord web, et d'autres langages (Node, PHP, Python). Ces idées peuvent aller dans
la feuille de route, sans être codées.
