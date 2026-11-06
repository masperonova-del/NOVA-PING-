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

## État de la version 1.0.0

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
