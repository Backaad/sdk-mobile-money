# Descriptif pour l'agent : SDK Mobile Money unifié (Cameroun d'abord, Afrique ensuite)

> **À lire en entier avant toute action.** Ce document est la commande initiale du
> projet. Il est écrit en français pour Durham, le propriétaire ; **tout ce que tu
> produis dans le dépôt (code, commentaires, README, docs, messages de commit) est en
> anglais**, sauf `README.fr.md`. Tes messages à Durham restent **en français**.

---

## 0. Le propriétaire et ses règles de travail

- **Durham** (GitHub : `Backaad`) est développeur Flutter et Spring Boot, au Cameroun.
  Il vient de livrer *Quick*, une application agritech (microservices Spring Boot,
  application Flutter), dont le service de paiement a inspiré ce projet.
- **Toujours lui écrire en français**, même pour un message d'attente.
- **Annoncer avant d'agir** : dire en une phrase ce que tu vas faire, puis le faire.
- **Ne rien ajouter qui ne soit pas demandé ici**, surtout côté interface : si une
  idée te semble utile (un widget, une option, un écran), propose-la et attends son
  accord. Il l'a exigé explicitement.
- **Aucun secret dans la conversation ni dans le dépôt.** Les clés de bac à sable
  arrivent uniquement par les *secrets* GitHub Actions ou des variables d'environnement
  locales. Ne jamais afficher, journaliser ni recopier une clé.
- **Ne jamais affirmer un résultat non vérifié.** Un test qui n'existe pas ne se
  décrit pas ; un comportement d'API non confirmé par la documentation officielle se
  signale comme « à vérifier ».
- Les comptes (Maven Central, pub.dev, tableaux de bord des opérateurs, clés de
  bac à sable) sont créés **par Durham**. Tu prépares tout ce qu'il faut et tu lui
  dis exactement quoi faire.

---

## 1. Le produit

### 1.1 L'idée en une phrase

Une seule interface, propre et sûre, pour **encaisser** et **verser** de l'argent
Mobile Money via les opérateurs et agrégateurs du Cameroun (MTN MoMo, Campay,
NotchPay, Tara), avec des webhooks **vérifiés**, une **idempotence** intégrée et un
paquet Flutter pour l'application mobile.

### 1.2 Pourquoi ça peut prendre des étoiles

- Chaque développeur d'Afrique centrale et de l'Ouest refait ces intégrations, mal
  documentées et pleines de pièges (schéma d'authentification inhabituel, numéros au
  mauvais format, webhooks non signés, statuts trompeurs).
- Les bibliothèques existantes sont mono-opérateur, souvent abandonnées, rarement
  sûres sur les webhooks.
- Ce projet part d'un code qui a déjà affronté ces pièges dans *Quick*. Chaque piège
  connu (section 4) devient une ligne du README : c'est ce qui donne confiance.

### 1.3 Nom

Le **nom du dépôt fait foi**. Dans ce document, `mbongo` est un nom provisoire
(« l'argent », en argot camerounais). Remplace-le partout par le nom réel du dépôt :
groupe Maven, nom du paquet Dart, titres.

### 1.4 La règle d'architecture qui ne se discute pas

**Les clés des opérateurs ne quittent jamais le serveur.** Une application mobile se
décompile : une clé qui y est embarquée est une clé volée.

- Le **cœur** qui parle aux opérateurs est une bibliothèque **serveur** (Java).
- Le **paquet Flutter** ne parle **qu'au serveur du marchand**. Il ne connaît aucune
  clé d'opérateur. Il apporte la validation du numéro, la détection de l'opérateur,
  le suivi du statut et un composant d'interface facultatif.

---

## 2. Contenu du dépôt (monodépôt)

```
mbongo/
├── java/
│   ├── mbongo-core/                  # bibliothèque Java 17+, sans Spring
│   ├── mbongo-spring-boot-starter/   # auto-configuration Spring Boot 3
│   └── pom.xml                       # parent Maven (ou Gradle, au choix, justifié)
├── flutter/
│   └── mbongo/                       # paquet Dart/Flutter pour pub.dev
├── examples/
│   ├── spring-boot-demo/             # serveur marchand d'exemple
│   └── flutter-demo/                 # application d'exemple qui appelle ce serveur
├── docs/                             # docs en anglais (architecture, opérateurs, sécurité)
├── .github/                          # CI, modèles d'issues et de PR
├── README.md                         # anglais
├── README.fr.md                      # français
├── CONTRIBUTING.md, CODE_OF_CONDUCT.md, SECURITY.md, CHANGELOG.md, LICENSE (MIT)
├── CLAUDE.md                         # règles durables pour les futures sessions (anglais)
└── .env.example                      # noms des variables, jamais de valeurs réelles
```

### 2.1 `mbongo-core` (Java 17+)

- Dépendances minimales : `java.net.http.HttpClient`, Jackson, SLF4J. Pas de Spring.
- **Modèle unifié** :
  - `PaymentRequest` : montant (`long`, unités entières), devise (`XAF` pour
    commencer ; prévoir `XOF`), numéro, référence marchande, description,
    URL de rappel ;
  - `PayoutRequest` : idem côté versement, nom du bénéficiaire ;
  - `PaymentResult` : référence opérateur, statut initial ;
  - `PaymentStatus` : `PENDING`, `SUCCESSFUL`, `FAILED`, plus le statut brut de
    l'opérateur pour le diagnostic ;
  - `WebhookEvent` : référence, statut, montant si fourni, opérateur, **et un
    indicateur « vérifié » qui ne peut être vrai que si la vérification a réussi** ;
  - `ProviderCapabilities` : `collect`, `payout`, `statusQuery`, et le mode de
    signature des webhooks (`NONE`, `HMAC_SHA256_HEX`, `JWT_HS256`).
- **Patron Strategy + Factory** : une interface `MobileMoneyProvider` (collecte,
  versement, statut, analyse du webhook, capacités), une implémentation par
  opérateur, une fabrique qui choisit selon la configuration, avec un ordre de repli
  configurable entre agrégateurs.
- **Erreurs typées** : `InvalidPhoneNumberException`, `ProviderAuthenticationException`,
  `ProviderUnavailableException`, `PaymentRejectedException`,
  `WebhookVerificationException`… Jamais une `RuntimeException` nue.
- **Numéros** : un module `PhoneNumbers` qui normalise (`+237 6 70…`, `670…`,
  `237670…` → `237670…`) et détecte l'opérateur par préfixe. **La table des
  préfixes est configurable** et sa valeur par défaut doit être vérifiée auprès de
  l'ARTP (régulateur camerounais). Valeur de départ, celle de *Quick*, **à vérifier** :
  MTN 650-654, 670-679, 680-689 ; Orange 655-659, 690-699.
- **Idempotence** : interface `IdempotencyStore` (`tryAcquire`, `markCompleted`,
  `getExistingResult`, `release`) avec une implémentation en mémoire. La clé vaut
  24 h. Une requête rejouée avec la même clé renvoie le premier résultat, sans second
  débit.
- **Mode simulation** distinct du **bac à sable** : la simulation ne parle à personne
  et **ne confirme jamais un paiement** (voir piège 4.8).

### 2.2 `mbongo-spring-boot-starter`

- Propriétés `mbongo.*` (activer un opérateur, ses identifiants, l'ordre de repli,
  le mode simulation), validées au démarrage avec des messages clairs. Exemple : Tara
  activé sans protection de webhook, ce qui doit être refusé au démarrage.
- Une implémentation `IdempotencyStore` Redis (`SETNX` + durée de vie), activée si
  Spring Data Redis est présent.
- Un **assistant de webhook** : une classe qui reçoit le corps brut et les en-têtes,
  vérifie selon l'opérateur, **relit le statut chez l'opérateur quand c'est
  possible**, et publie un événement Spring `PaymentConfirmedEvent` /
  `PaymentFailedEvent`. Le marchand n'écrit que son écouteur.
- Facultatif, désactivé par défaut : un réconciliateur planifié qui relit les
  paiements restés `PENDING`.

### 2.3 Paquet Flutter `mbongo`

- `MobileMoneyPhone` : validation, normalisation, détection de l'opérateur (même
  table que le cœur, mêmes tests de référence).
- `MbongoClient` : appelle **le serveur du marchand** (URL de base fournie par
  l'application), lance un paiement, suit son statut par interrogation avec arrêt
  propre (délai maximal, annulation).
- `MobileMoneyPaymentSheet` : un composant d'interface simple et facultatif (champ
  numéro, logo de l'opérateur détecté, montant, bouton, état « confirmez sur votre
  téléphone »). Rien de plus sans l'accord de Durham.
- Aucune clé d'opérateur, aucune dépendance lourde. Thème clair et sombre corrects.

### 2.4 Exemples

- `spring-boot-demo` : un serveur marchand minimal (deux routes : lancer un paiement,
  lire son statut ; une route webhook) qui utilise le starter en mode simulation par
  défaut.
- `flutter-demo` : une application qui utilise le paquet contre ce serveur.
- Un `docker-compose.yml` qui lance le serveur d'exemple et Redis.

---

## 3. Les opérateurs : ce qu'on sait, et ce qu'il faut vérifier

Tout ce qui suit vient du service de paiement de *Quick* et de recherches du
09-10/10/2026. **Avant de coder chaque opérateur, relis sa documentation officielle.**
Note chaque écart dans `docs/providers/<operateur>.md`.

### 3.1 Campay (agrégateur MTN + Orange) — le plus éprouvé, à faire en premier

- Bases : `https://demo.campay.net` (bac à sable), `https://www.campay.net` (production).
- Jeton : `POST /api/token/` avec `{"username", "password"}` (identifiants de
  l'application). La réponse contient `token` et `expires_in`.
  - Mettre le jeton en cache jusqu'à `expires_in` moins 60 s.
  - Sur un 401, renouveler et rejouer **une seule fois**.
- **En-tête `Authorization: Token <jeton>`, pas `Bearer`** : `Bearer` renvoie 401.
- Collecte : `POST /api/collect/` avec `amount` (en chaîne), `currency`, `from`,
  `description`, `external_reference`, `redirect_url`. La réponse contient `reference`.
- Versement : `POST /api/withdraw/` avec `amount`, `currency`, `to`, `description`,
  `external_reference`.
- Statut : `GET /api/transaction/{reference}/`, champ `status`.
- **Numéro au format `237XXXXXXXXX`, sans `+`.** Un numéro local à 9 chiffres est
  refusé avec un 400.
- Webhook :
  - Campay rappelle en **GET ou en POST**, selon le réglage du tableau de bord : servir
    les deux.
  - Le champ `signature` est un **JWT HS256** signé avec la *webhook key* de
    l'application, prise telle quelle en UTF-8.
  - Refuser `alg: none` et les jetons expirés.
- À vérifier : les limites de montant du bac à sable et la liste exacte des statuts.

### 3.2 NotchPay (agrégateur MTN + Orange + cartes)

- Base : `https://api.notchpay.co`. Clés `pk_test_…` / `pk_live_…` (publique) et
  clé privée.
- Collecte en deux temps :
  1. `POST /payments` avec `amount`, `currency`, `reference`, `description`,
     `callback`, `customer.phone` → référence de la transaction (parfois imbriquée
     sous `transaction`) ;
  2. `POST /payments/{reference}` avec `{"channel": "cm.mtn" | "cm.orange",
     "data": {"phone": …}}`, ce qui déclenche le message USSD sur le téléphone.
- Statut : `GET /payments/{reference}`. Normaliser :
  - `complete`, `completed`, `successful`, `success` → `SUCCESSFUL` ;
  - `failed`, `canceled`, `cancelled`, `rejected`, `expired` → `FAILED` ;
  - le reste → `PENDING`.
- Versement : `POST /transfers` avec `amount`, `currency`, `description`,
  `reference`, `recipient{channel, number, name}`. Les transferts doivent être
  activés sur le compte.
- Authentification : `Authorization: <clé publique>`.
  - **À vérifier** : *Quick* envoyait la clé privée dans `Authorization` pour la
    charge et les transferts.
  - La documentation pourrait demander un en-tête dédié (par exemple `X-Grant`) :
    suis la documentation.
- Webhook : en-tête `x-notch-signature`, **HMAC-SHA256 hexadécimal du corps brut**,
  comparé à temps constant.

### 3.3 MTN MoMo (API directe, *Open API*)

- **Attention** : l'implémentation de *Quick* n'a jamais tourné en vrai et **oublie
  l'obtention du jeton OAuth**. Ne pas s'en inspirer. Suivre la documentation
  officielle (`momodeveloper.mtn.com`).
- Bac à sable public : `https://sandbox.momodeveloper.mtn.com`. **C'est le seul
  opérateur entièrement testable sans contrat** : priorité pour les tests
  d'intégration.
- Rappel du flux, **à confirmer dans la doc** :
  - créer l'utilisateur d'API et sa clé (bac à sable uniquement) ;
  - obtenir un jeton par `POST /collection/token/` en authentification Basic
    (`apiUser:apiKey`), avec `Ocp-Apim-Subscription-Key` ;
  - collecter par `POST /collection/v1_0/requesttopay`, avec les en-têtes
    `Authorization: Bearer`, `X-Reference-Id` (UUID v4, il sert de référence),
    `X-Target-Environment` et `X-Callback-Url` ;
  - lire le statut par `GET /collection/v1_0/requesttopay/{referenceId}` ;
  - le versement suit le même schéma sous `/disbursement/` (`/v1_0/transfer`), avec
    son propre jeton et sa propre clé d'abonnement.
- À vérifier : le bac à sable n'accepterait que la devise `EUR`, et le nom de
  l'environnement de production au Cameroun (`mtncameroon` ?).
- **MTN ne signe pas ses rappels** : un rappel MTN n'est qu'un signal. Le statut se
  relit toujours par l'API avant toute confirmation.

### 3.4 Tara (Tara by Dikalo, aussi appelé Taramoney) — à ajouter, en « expérimental »

- Base : `https://www.dklo.co/api/tara/`. La page de documentation renvoyait 404 le
  26/09/2026. Le SDK PHP tiers `consolidis/tara-payment` confirme la base et le
  champ de collecte (il n'est pas officiel).
- Collecte : `POST /mobilepay` avec `apiKey`, `businessId`, `productId` (référence
  unique), `productName`, `network` (`""` = détection automatique ; `"wave"` pour
  Wave au Sénégal et en Côte d'Ivoire), `productPrice` (entier), `phoneNumber`
  (`237…`) et `webHookUrl`. Réponse : `status` = `SUCCESS` si accepté, `message`,
  `vendor`. **Pas d'identifiant de transaction dans la réponse.**
- Versement : `POST /payout/create` avec `apiKey`, `businessId`, `receiverName`,
  `paymentMethod` (`MOBILE_MONEY`…), `receiverPhoneNumber`, `receiverId` et `amount`.
  Réponse : `status`, `payoutId`, `message`. **À vérifier.**
- **Aucune interrogation de statut** : seul le webhook (POST JSON, `status` =
  `SUCCESS` sinon échec) annonce le résultat. Répondre `200` vite, car Tara ne
  réessaie pas un rappel trop lent.
- **Le webhook n'est pas signé** (aucune vérification documentée). Puisqu'on ne peut
  ni relire le statut ni vérifier une signature, le SDK doit imposer des protections :
  1. une **URL de rappel imprévisible par transaction** : jeton aléatoire de
     128 bits dans `webHookUrl`, vérifié à réception, à usage unique, expirant ;
  2. la **concordance** avec la transaction en attente : `productId`, montant et
     numéro, si présents ;
  3. une **liste d'adresses IP autorisées**, facultative et configurable ;
  4. si une signature apparaît un jour dans la documentation (Quick supposait un
     HMAC-SHA256 dans `X-Signature`), l'ajouter et la rendre obligatoire.
- `apiKey` voyage **dans le corps** de la requête : ne **jamais** journaliser le corps
  des requêtes Tara.
- Marquer Tara **expérimental** dans le README, avec ces limites expliquées
  honnêtement.

### 3.5 Orange Money (API directe) — plus tard

- Pas dans la première version. L'API marchande d'Orange Cameroun (WebPay, par
  redirection) exige un partenariat. En attendant, Orange passe par Campay, NotchPay
  ou Tara.
- La lister dans le README comme « prévue », avec un lien vers une issue.

### 3.6 Tableau à publier dans le README

| Opérateur | Collecte | Versement | Statut interrogeable | Webhook signé | Bac à sable |
|---|---|---|---|---|---|
| Campay | oui | oui | oui | JWT HS256 | oui |
| NotchPay | oui | oui | oui | HMAC-SHA256 | oui (`pk_test`) |
| MTN MoMo | oui | oui | oui | non (relecture obligatoire) | oui (public) |
| Tara (expérimental) | oui | à vérifier | **non** | **non** (URL à jeton) | à vérifier |
| Orange Money direct | prévu | prévu | — | — | — |

Mets ce tableau à jour avec ce que tu vérifies réellement.

---

## 4. Les pièges déjà payés dans Quick : chacun devient un test

1. **Ne jamais croire le statut d'un webhook.** Quand l'opérateur permet de relire le
   statut, on le relit avant de confirmer. Le statut du webhook ne sert qu'à
   journaliser un désaccord.
2. **Le webhook d'un opérateur ne statue que sur ses propres transactions.** Un
   rappel MTN, jamais signé, ne doit pas pouvoir confirmer une transaction Campay.
3. **Comparer les signatures à temps constant** (`MessageDigest.isEqual`). Un
   `equalsIgnoreCase` s'arrête au premier caractère différent, et la durée de la
   réponse permet de deviner la signature octet par octet.
4. **Une erreur passagère pendant la lecture du statut vaut `PENDING`, jamais
   `FAILED`** : réseau, 5xx, délai dépassé, corps vide. Conclure à l'échec affichait
   « paiement échoué » alors que l'argent était passé.
5. **Jeton mis en cache, mais pas pour toujours.** Respecter `expires_in` avec une
   marge, et renouveler sur 401 (une seule fois).
6. **Format des numéros** propre à chaque opérateur, centralisé et testé
   (`237XXXXXXXXX` pour Campay, NotchPay et Tara).
7. **Ne jamais journaliser** une clé, un jeton, une signature reçue, ni un corps qui
   en contient. Le type d'exception suffit au diagnostic ; le message d'une
   bibliothèque JWT peut citer le jeton.
8. **La simulation ne confirme jamais rien.** Dans *Quick*, `checkStatus` en
   simulation répondait `SUCCESSFUL` à tout. Un webhook forgé au `curl` créditait
   donc un compte : c'était une faille. Pour tester de bout en bout, on utilise un bac
   à sable, pas la simulation.
9. **Idempotence** sur toute opération qui débite ou verse. Un double appel (double
   appui, nouvelle tentative réseau) ne doit jamais débiter deux fois.
10. **Valider la configuration au démarrage.** Exemples : un secret de webhook absent
    pour un opérateur activé, une URL de rappel `localhost` en production. *Quick*
    envoyait à Tara une URL `localhost` faute de variable transmise, et les rappels
    n'arrivaient jamais.
11. **Router vers un autre opérateur seulement si on le dit.** Dans *Quick*,
    demander Tara désactivé basculait sans prévenir vers Campay. Le repli doit être
    explicite, configurable, et visible dans le résultat.

---

## 5. Qualité

- **Tests unitaires** pour chaque opérateur, avec **WireMock**, à partir de réponses
  tirées de la documentation : succès, refus, 401 puis renouvellement, 5xx, corps
  vide, statuts inconnus.
- **Un test par piège de la section 4**, nommé d'après lui.
- **Tests d'intégration contre les bacs à sable** (MTN d'abord, Campay ensuite),
  exécutés seulement si les secrets sont présents : ignorés proprement sinon, jamais
  en échec.
- **Paquet Flutter** : tests unitaires (numéros, opérateurs, client avec un serveur
  simulé) et test de rendu du composant en thème clair et sombre.
- **Mêmes données de référence** pour les numéros en Java et en Dart : un fichier
  JSON partagé (`docs/phone-fixtures.json`) que les deux suites lisent.
- **Intégration continue (GitHub Actions)** : construction et tests Java (JDK 17 et
  21), `flutter analyze` et `flutter test`, couverture publiée, badge dans le README.
- Versionnage sémantique, `CHANGELOG.md` tenu à jour, première version `0.1.0`.

---

## 6. Documentation (c'est elle qui rapporte les étoiles)

- **README en anglais**, dans cet ordre :
  1. une phrase de promesse ;
  2. les badges ;
  3. une animation ou une capture de la démo Flutter (Durham la fera si tu ne peux
     pas, prépare l'emplacement) ;
  4. « Why » en 4 lignes ;
  5. le tableau des opérateurs ;
  6. un démarrage en 5 minutes (Spring Boot puis Flutter) ;
  7. la sécurité (les pièges de la section 4, résumés) ;
  8. la feuille de route ;
  9. contribuer ;
  10. la licence.
- `README.fr.md` : même contenu, en français (une grande partie du public est
  francophone).
- `docs/architecture.md` (avec un diagramme Mermaid), `docs/security.md`,
  `docs/providers/*.md`.
- `SECURITY.md` : comment signaler une faille en privé.
- Modèles d'issue : bogue, nouvel opérateur, question.
- Des issues « good first issue » préparées pour les opérateurs à venir : Orange
  direct, Wave, Moov, Airtel, M-Pesa. Durham les ouvrira, ou toi si tu en as l'accès.
- Mentionner l'origine sans exagérer : « extracted from the payment service of
  *Quick*, an agritech app built in Cameroon ». **Aucun chiffre de volume, aucune
  promesse de production** non vérifiés.

---

## 7. Plan de travail : une branche et une PR par étape

| Étape | Contenu | Terminée quand |
|---|---|---|
| **0. Socle** | Arborescence, Maven, paquet Flutter vide, CI verte, licence, `CLAUDE.md` (anglais, règles durables : langue, sécurité, pièges), `.env.example` | La CI passe sur une PR vide |
| **1. Cœur + Campay** | Modèle unifié, numéros (+ fichier de référence), idempotence en mémoire, Campay complet, tests WireMock et tests des pièges | Tous les tests passent |
| **2. NotchPay, MTN, Tara** | Les trois opérateurs, la fabrique et le repli explicite, le test d'intégration du bac à sable MTN | Tests verts ; Tara marqué expérimental |
| **3. Starter Spring Boot** | Propriétés, validation au démarrage, idempotence Redis, assistant de webhook, événements, exemple `spring-boot-demo` + docker-compose | La démo tourne en simulation |
| **4. Paquet Flutter** | Numéros, client, composant de paiement, `flutter-demo` | `flutter test` vert, démo fonctionnelle contre le serveur d'exemple |
| **5. Docs et publication** | README (en et fr), docs, modèles, `CHANGELOG`, préparation de la publication Maven Central et pub.dev | La liste des actions de Durham est prête |

À la fin de chaque étape, écris à Durham, en français et en quelques lignes :
- ce qui est fait ;
- ce qui est vérifié, et comment ;
- ce qui reste « à vérifier » ;
- ce qu'il doit faire lui-même (créer un compte, ajouter un secret…).

---

## 8. Ce que Durham fera lui-même (prépare-lui les instructions)

- Créer les comptes de bac à sable : MTN MoMo Developer, Campay (démo), NotchPay
  (mode test), Tara si un bac à sable existe.
- Ajouter les clés dans **Settings → Secrets and variables → Actions** du dépôt,
  sous les noms que tu auras fixés dans `.env.example`.
- Créer les comptes de publication : Sonatype Central (avec une clé GPG) pour Maven,
  et pub.dev.
- Enregistrer l'animation de la démo, si tu ne peux pas la produire.
- Annoncer le projet une fois la version `0.1.0` publiée.

---

## 9. Hors périmètre (ne pas faire sans accord)

- Paiement par carte, abonnements, remboursements automatiques, tableau de bord web.
- Autres langages (Node, PHP, Python) : de bonnes idées pour plus tard, à proposer
  dans la feuille de route seulement.
- Tout opérateur hors de la section 3.
