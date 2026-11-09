# NOVA PING

**Diagnostic réseau pour le gaming compétitif — Android**

NOVA PING aide à comprendre la qualité de réponses réseau vers des points de contrôle publics. Le projet privilégie les mesures reproductibles, les explications honnêtes et les recommandations fondées sur des preuves.

## Principes du produit

- Ne jamais inventer ni simuler un meilleur ping.
- Distinguer le temps de réponse HTTPS du ping réel d'un serveur de jeu.
- Afficher la méthode, le nombre de mesures et les limites de chaque résultat.
- Ne comparer que des destinations mesurées dans des conditions comparables.
- Expliquer le niveau de confiance et les limites des recommandations.
- Demander un consentement explicite avant toute future fonction de routage VPN.
- Ne jamais prétendre qu'un changement DNS ou qu'un test vers un service cloud réduit le ping en jeu sans mesure directe qui le démontre.

## État de la base 1.0.0

La version 1.0.0 consolide la base de diagnostic Android.

### Fonctions implémentées

- Application Android native en Kotlin.
- Sélection d'un profil de jeu : Call of Duty: Mobile, PUBG Mobile, Free Fire ou autre.
- Vérification préalable de l'état de connectivité Android.
- Trois destinations HTTPS de contrôle : Google Connectivity, Cloudflare Connectivity et Microsoft Connectivity.
- Trois cycles indépendants, avec trois mesures par destination à chaque cycle (neuf mesures mesurées par destination).
- Une requête de préparation avant les mesures du premier cycle pour chaque destination.
- Médiane, P95 observé, variation moyenne entre réponses réussies et nombre de requêtes HTTPS sans réponse.
- Médianes par cycle et écart entre cycles lorsque les cycles ont des mesures exploitables.
- Classification indicative des échecs : DNS, délai dépassé, TLS, connexion, statut HTTP ou cause indéterminée.
- Évaluation indicative de la régularité des réponses.
- Historique local des dix dernières sessions, classées par type de connexion.
- Tests unitaires des métriques, du diagnostic et de l'état de préparation réseau.
- Compilation Android automatisée avec GitHub Actions.

### Limites à connaître

Les résultats actuels sont des temps de réponse HTTPS vers des services de contrôle. Ils comprennent le comportement du réseau, la résolution DNS, l'établissement de la connexion, TLS et le temps de réponse du service. Ils ne représentent pas le ping d'un serveur Call of Duty Mobile, PUBG Mobile ou Free Fire.

Les requêtes HTTPS qui échouent sont comptées comme des **échecs de requêtes**, pas comme une mesure de perte de paquets IP. Le diagnostic de connectivité Android indique si le système considère l'accès Internet comme validé ; il ne permet pas toujours d'identifier la cause exacte d'un échec.

La variation moyenne est calculée comme la moyenne des différences absolues entre les réponses réussies successives. Le P95 est estimé par rang le plus proche ; avec neuf échantillons, il correspond au maximum observé. Les classifications de stabilité sont des heuristiques et non une garantie de performance en jeu.

La version 1.0.0 ne modifie pas le routage, ne crée pas de VPN et ne promet aucune réduction du ping en jeu.

## Architecture cible

1. **NOVA CORE** — coordination des tests, sessions et validation des résultats.
2. **PING ENGINE** — mesures étiquetées par protocole, statistiques et historique.
3. **DIAGNOSTIC ENGINE** — état réseau, stabilité et causes possibles des échecs.
4. **GAME INTELLIGENCE** — métadonnées vérifiées sur les jeux, régions et serveurs.
5. **ROUTE ENGINE** — comparaison de routes lorsque des mesures techniquement valides sont possibles.
6. **DECISION ENGINE** — recommandations classées selon les preuves et leur niveau de confiance.
7. **NOVA BOOST** — recherche future sur le routage optionnel, avec consentement et preuve d'un bénéfice mesuré.

## Prochaines étapes

- Renforcer la qualité des mesures et la validation des résultats.
- Ajouter une comparaison Wi-Fi / données mobiles uniquement lorsque les deux connexions sont réellement testables.
- Construire un catalogue de régions et de destinations de jeu vérifiées, sans deviner les adresses privées.
- Étudier les méthodes de mesure de route adaptées à Android.
- Développer des recommandations transparentes avant toute optimisation active.

## Build

La compilation de développement est automatisée par GitHub Actions. Les APK debug sont des versions de test ; une distribution publique nécessitera une configuration de signature et un processus de publication adaptés.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Standards de mesure réseau](docs/NETWORK-MEASUREMENT.md)
- [Feuille de route](docs/ROADMAP.md)

## Plateforme

Android en priorité. Le gaming mobile compétitif est le cas d'usage principal.

## Licence

Aucune licence open source n'a été choisie. Tous droits réservés au propriétaire du dépôt tant qu'une licence n'est pas ajoutée.


## Version 1.4.1 — Fiabilisation des mesures

La version de développement 1.4.1 conserve les quatre modules et corrige plusieurs points de fiabilité :

- **Diagnostic réseau (1.1)** : contexte du réseau actif, type de transport, validation Internet Android, réseau mesuré, itinérance et estimations de bande passante lorsqu'Android les fournit.
- **Game Intelligence (1.2)** : catalogue de profils de jeux avec statut explicite des preuves. Aucune adresse de serveur de match n'est inventée ou présentée comme vérifiée.
- **Route Engine (1.3)** : décomposition de trois connexions HTTPS par destination en résolution DNS, établissement TCP, négociation TLS et délai jusqu'à la lecture de la ligne de statut HTTP.
- **Decision Engine (1.4)** : recommandations explicables fondées sur plusieurs essais, actions proposées et niveau de confiance indicatif.

### Corrections et limites de la version 1.4.1

Le Route Engine ne réalise pas encore un traceroute complet et ne mesure pas directement le ping des serveurs de match. Les phases sont mesurées vers des services publics de contrôle ; elles dépendent aussi du comportement de ces services. Chaque destination est testée sur trois cycles ; les statistiques sont calculées séparément par phase. Le P95 sur trois mesures reste descriptif et ne garantit pas la latence extrême. Les seuils de recommandation sont indicatifs et devront être calibrés sur davantage de mesures réelles. Les adresses retournées par DNS sont essayées en séquence jusqu'à ce qu'une connexion TCP réussisse.

NOVA PING 1.4.1 ne modifie pas le routage de l'appareil et ne promet aucune réduction du ping en jeu. NOVA BOOST reste une phase de recherche séparée, soumise à des essais comparatifs et au consentement explicite de l'utilisateur.


## Version 1.5.0 — Wi-Fi Stability Monitor

La version 1.5.0 ajoute une surveillance des changements réseau lorsque l'application est au premier plan :

- Détecte les événements de disponibilité/perte du réseau Wi-Fi signalés par Android.
- Distingue la présence du Wi-Fi de la validation de l'accès Internet par Android.
- Enregistre les changements du réseau par défaut (Wi-Fi, données mobiles, VPN ou autre).
- Affiche les événements horodatés et la durée de récupération après une coupure Wi-Fi ou une perte de validation Internet.
- Conserve uniquement les 20 événements les plus récents dans l'affichage de la session ; aucun historique de surveillance n'est envoyé à un serveur.

**Limites :** la surveillance s'interrompt lorsque NOVA PING passe en arrière-plan. Les callbacks Android signalent l'état des réseaux connus du système ; ils ne révèlent pas nécessairement la cause radio exacte d'une coupure. Une perte de validation Internet n'est pas une perte de Wi-Fi. Aucun ping COD Mobile ni paquet du jeu n'est capturé.


## Version 1.5.1 — Fiabilisation de la reprise du moniteur

Correctif du suivi Wi-Fi après le passage de NOVA PING en arrière-plan :

- Synchronise l'état Wi-Fi et le réseau actif avec l'état courant fourni par Android à chaque reprise.
- Évite de journaliser une connexion Wi-Fi déjà présente comme une nouvelle connexion.
- Signale séparément les états constatés à la reprise lorsque l'heure réelle du changement est inconnue.
- Ne calcule une durée de récupération que lorsque la perte et le retour ont été observés pendant une même période de surveillance continue.
- Ajoute des tests unitaires pour les reprises, les états inchangés et les durées inconnues.

La surveillance reste limitée au premier plan. Les changements survenus pendant une pause ne peuvent pas être reconstruits avec précision.


## Version 1.8.0-dev — Renforcement des mesures

- Le diagnostic HTTPS passe à cinq cycles indépendants de cinq mesures par destination, soit 25 tentatives planifiées par destination.
- L'ordre des destinations continue de tourner entre les cycles afin de limiter le biais d'ordre.
- Le nombre réellement tenté est enregistré : une session interrompue n'est plus présentée comme si les 25 requêtes avaient été exécutées.
- Une destination n'est classée que si les 25 tentatives ont été exécutées et qu'au moins 20 réponses ont abouti (80 %).
- Les diagnostics de régularité exigent au moins 15 réponses exploitables ; en dessous, le résultat reste « Données insuffisantes ».
- L'analyse DNS/TCP/TLS/HTTP passe à cinq essais par destination. Son P95 de phase reste le maximum observé sur cinq échantillons et doit être interprété avec prudence.
- Les compteurs, l'historique et les tests sont alignés sur le nouveau plan de mesure.

Cette version ne mesure toujours pas directement le ping d'un serveur de jeu et ne modifie pas le routage réseau.


## Version 1.9.0-dev — Détails des mesures

- Enregistre chaque tentative HTTPS individuelle avec son cycle, son numéro d'essai, son temps de réponse, son code HTTP ou sa cause d'échec.
- Conserve les résultats individuels DNS, TCP, TLS et ligne de statut HTTP des cinq essais Route Engine.
- Ajoute deux vues de détail pour consulter la dernière comparaison et la dernière analyse des phases.
- Ajoute l'export CSV local des mesures HTTPS et des phases réseau.
- Les dix dernières sessions de comparaison restent conservées localement ; les sessions antérieures à cette version ne contiennent pas rétroactivement les mesures brutes.
- Les résultats exportés restent des mesures de points de contrôle HTTPS et ne représentent pas le ping des serveurs de match.

## Version 1.10.0-dev — Comparaison prudente des points de contrôle

Le Decision Engine distingue désormais deux lectures indépendantes :

- **Fiabilité observée** : nombre de connexions HTTPS complètes parmi cinq essais par destination.
- **Délais des phases** : somme indicative des médianes DNS, TCP, TLS et HTTP, calculée uniquement si au moins trois mesures sont disponibles pour chacune des phases et pour au moins deux destinations.

Lorsque les données ne permettent pas un classement, l'application l'indique au lieu de désigner artificiellement un gagnant. Les comparaisons portent exclusivement sur les points de contrôle HTTPS publics. Elles ne déterminent pas le meilleur serveur de match CODM et ne prouvent aucune amélioration du ping en jeu.


## Version 1.11.0-dev — Comparaison de sessions

La prochaine version de développement ajoute un comparateur local qui met en regard deux sessions terminées sur le même profil de jeu et le même type de connexion. Il affiche les écarts de médiane HTTPS et les volumes de réponses réussies, tout en excluant les sessions interrompues ou insuffisamment mesurées. Cette comparaison concerne les points de contrôle publics, jamais le ping direct d'un serveur de jeu.


## Version 1.11.1-dev — Comparaison stricte des sessions

- Comparaison locale de deux sessions terminées sur le même profil de jeu et le même type de connexion.
- Comparaison destination par destination des médianes HTTPS et des réponses réussies.
- Validation des 25 positions d'essai brutes : les sessions ne sont comparées que si les mêmes positions ont fourni une réponse exploitable dans les deux sessions.
- Les sessions interrompues, les données historiques sans essais bruts et les échantillons incomplets sont écartés.
- Les résultats restent des mesures vers des points de contrôle HTTPS publics ; ils ne représentent pas le ping du serveur CODM et ne modifient pas le routage.

Version Android de développement : **1.11.1-dev** (versionCode **26**).
