# NOVA PING — Roadmap

## Version 1.0.0 — Diagnostic de base

- [x] Application Android native en Kotlin.
- [x] Profils Call of Duty: Mobile, PUBG Mobile, Free Fire et autre.
- [x] Pré-vérification de la connectivité Android.
- [x] Trois destinations HTTPS de contrôle.
- [x] Trois cycles indépendants et neuf mesures par destination.
- [x] Requête de préparation distincte avant le premier cycle de chaque destination.
- [x] Médiane, P95 observé, variation moyenne et comptage des requêtes HTTPS sans réponse.
- [x] Médianes par cycle et écart inter-cycle.
- [x] Classification des causes d'échec lorsque l'exception réseau permet de les identifier.
- [x] Évaluation indicative de la régularité.
- [x] Historique local des dix dernières sessions.
- [x] Tests unitaires pour les métriques et les règles de diagnostic.
- [x] Compilation debug automatisée par GitHub Actions.
- [x] Documentation des limites des mesures HTTPS.

## Version 1.1.0 — Diagnostic réseau approfondi

- [ ] Séparer clairement les états Wi-Fi, réseau cellulaire et accès Internet validé.
- [ ] Ajouter des diagnostics Wi-Fi utiles lorsque Android expose les données nécessaires.
- [ ] Ajouter une comparaison Wi-Fi / mobile seulement lorsque les deux réseaux sont testables.
- [ ] Enregistrer les conditions de test nécessaires pour comparer deux sessions.
- [ ] Améliorer l'explication des échecs HTTP avec les codes de réponse disponibles.
- [ ] Améliorer les tests de non-régression et les validations sur appareil réel.

## Version 1.2.0 — Game Intelligence

- [ ] Définir un modèle de données pour les jeux, régions et destinations.
- [ ] Ajouter des métadonnées vérifiées et datées sur les régions de jeu.
- [ ] Distinguer les points de contrôle publics des serveurs réellement utilisés par un jeu.
- [ ] Ne pas afficher d'adresse de serveur supposée ou non vérifiée.

## Version 1.3.0 — Route Engine

- [ ] Étudier les méthodes de mesure de route disponibles sur Android.
- [ ] Comparer les destinations et routes uniquement avec une méthode documentée.
- [ ] Afficher les limites liées au filtrage ICMP, TCP ou UDP.
- [ ] Tester les comparaisons répétées dans des conditions contrôlées.

## Version 1.4.0 — Decision Engine

- [ ] Construire un moteur de recommandations explicables.
- [ ] Classer les actions selon la qualité des preuves et leur utilité attendue.
- [ ] Mesurer si une recommandation améliore réellement les résultats.
- [ ] Refuser de recommander une action lorsque les données sont insuffisantes.

## Phase de recherche — NOVA BOOST

- [ ] Étudier les coûts et la disponibilité d'infrastructures relais.
- [ ] Évaluer un prototype Android VpnService avec consentement explicite.
- [ ] Comparer le routage direct et le relais sur les mêmes destinations.
- [ ] Mesurer les bénéfices et les régressions sur plusieurs réseaux.
- [ ] Ne publier la fonction que si les gains sont répétables et clairement démontrés.

## Conditions de validation

Une fonction n'est considérée comme terminée que lorsque :
- le code est commité ;
- les tests automatisés passent ;
- les limites sont documentées ;
- les fonctions dépendant du matériel sont vérifiées sur un appareil Android réel ;
- les affirmations affichées correspondent aux mesures obtenues.

## Règle produit

Une réponse HTTPS vers un service cloud n'est pas le ping du serveur de jeu. Un échec de requête HTTPS n'est pas automatiquement une perte de paquets IP. Un DNS différent ou un VPN ne constitue pas une optimisation tant qu'un gain réel n'a pas été mesuré.
