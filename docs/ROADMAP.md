# NOVA PING — Roadmap

## Version 1.0.0 — Diagnostic de base

- [x] Application Android native en Kotlin.
- [x] Profils Call of Duty: Mobile, PUBG Mobile, Free Fire et autre.
- [x] Pré-vérification de la connectivité Android.
- [x] Trois destinations HTTPS de contrôle.
- [x] Trois cycles indépendants et neuf mesures par destination.
- [x] Médiane, P95 observé, variation moyenne et comptage des requêtes HTTPS sans réponse.
- [x] Médianes par cycle et écart inter-cycle.
- [x] Classification des causes d'échec lorsque l'exception réseau permet de les identifier.
- [x] Évaluation indicative de la régularité.
- [x] Historique local des dix dernières sessions.
- [x] Tests unitaires et compilation Android automatisée.

## Version 1.1.0 — Diagnostic réseau approfondi

- [x] Distinguer Wi-Fi, données mobiles, Ethernet, VPN et autres transports détectés.
- [x] Afficher l'état Internet validé par Android.
- [x] Afficher le statut réseau mesuré, les estimations de bande passante fournies par Android lorsqu'elles sont disponibles et ne signaler l'itinérance que si l'information est réellement connue.
- [ ] Ajouter une comparaison Wi-Fi / mobile simultanée, uniquement si Android permet de tester les deux réseaux sans changer la connexion active.
- [ ] Ajouter des informations radio Wi-Fi/cellulaire sans demander des permissions sensibles non nécessaires.
- [ ] Valider les variations de contexte sur plusieurs appareils.

## Version 1.2.0 — Game Intelligence

- [x] Créer un catalogue structuré des profils de jeux.
- [x] Associer un statut de preuve aux informations de serveur.
- [x] Empêcher l'affichage de destinations de match non vérifiées comme si elles étaient officielles.
- [ ] Intégrer des régions et destinations seulement à partir de sources fiables, datées et vérifiables.
- [ ] Étudier les interfaces de mesure officiellement accessibles pour chaque jeu.

## Version 1.3.0 — Route Engine

- [x] Mesurer séparément la résolution DNS.
- [x] Mesurer l'établissement TCP vers une destination HTTPS de contrôle.
- [x] Mesurer la négociation TLS avec vérification du nom d'hôte.
- [x] Mesurer le délai jusqu'au premier en-tête HTTP.
- [x] Afficher les phases incomplètes et les codes HTTP disponibles.
- [ ] Étudier une méthode fiable de découverte des sauts intermédiaires lorsque le réseau et Android l'autorisent.
- [x] Répéter les mesures sur trois cycles, en faisant tourner l'ordre des destinations.
- [x] Calculer séparément médiane, P95 observé, variation et nombre de réussites par phase.
- [x] Essayer les adresses DNS retournées jusqu'à établir une connexion TCP.
- [ ] Étudier une méthode fiable de découverte des sauts intermédiaires lorsque le réseau et Android l'autorisent.

**Limite actuelle :** le Route Engine décompose les phases d'une connexion vers un point de contrôle. Il ne réalise pas encore un traceroute complet et ne révèle pas automatiquement la route empruntée par un serveur de jeu.

## Version 1.4.0 — Decision Engine

- [x] Construire des recommandations explicables à partir du contexte réseau et des phases mesurées.
- [x] Afficher une action suggérée et un niveau de confiance.
- [x] Distinguer les problèmes DNS, TCP, TLS et réponse HTTP.
- [x] Éviter d'attribuer automatiquement un délai à l'opérateur ou au réseau local.
- [x] Exiger plusieurs échantillons avant de signaler une anomalie de phase.
- [x] Ne pas qualifier de sain un point de contrôle présentant des échecs, même isolés.
- [ ] Calibrer les seuils sur un ensemble plus large de mesures répétées et d'appareils réels.
- [ ] Comparer les recommandations avant/après et mesurer leurs effets.

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
