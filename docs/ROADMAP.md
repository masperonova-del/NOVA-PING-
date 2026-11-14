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
- [x] Mesurer le délai jusqu'à la lecture de la ligne de statut HTTP (pas un temps de premier octet réseau).
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

## Version 1.5.0 — Wi-Fi Stability Monitor

- [x] Ajouter un suivi Android des réseaux Wi-Fi disponibles au premier plan.
- [x] Distinguer perte du réseau Wi-Fi et perte de validation Internet lorsque le Wi-Fi reste présent.
- [x] Enregistrer les changements du réseau par défaut.
- [x] Horodater les transitions et calculer la durée de récupération observée.
- [x] Ajouter des tests unitaires du suivi des transitions.
- [ ] Vérifier sur appareil réel les événements de déconnexion/reconnexion et les délais de récupération.
- [ ] Évaluer les limites des callbacks Android sur différents constructeurs et versions.

**Limites :** la surveillance fonctionne au premier plan uniquement. Les événements reflètent les informations fournies par Android ; ils ne déterminent pas à eux seuls si la cause est le signal, le routeur, le répéteur ou l'opérateur. Aucun trafic du jeu n'est inspecté.

## Correctif 1.5.1 — Reprise de surveillance

- [x] Synchroniser l'état courant du Wi-Fi et du réseau actif lors du retour au premier plan.
- [x] Éviter les faux événements de nouvelle connexion lorsque l'état n'a pas changé.
- [x] Signaler explicitement les changements constatés après une pause dont l'heure exacte est inconnue.
- [x] Ne calculer les durées de récupération que pour des transitions observées sans interruption de surveillance.
- [x] Ajouter les tests unitaires correspondants.

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


## Version 1.8.0-dev — Renforcement des mesures

- [x] Centraliser le plan de mesure : cinq cycles HTTPS et cinq tentatives par cycle (25 par destination).
- [x] Utiliser le P95 nearest-rank sur 25 mesures (24e valeur ordonnée).
- [x] Enregistrer le nombre réellement tenté afin de ne pas transformer une interruption en fausses pertes.
- [x] Exiger au moins 20 réponses sur 25 et un lot complet avant de classer une destination.
- [x] Exiger 15 réponses exploitables avant d'évaluer la régularité HTTPS.
- [x] Porter l'analyse de phase à cinq essais par destination et expliciter les limites du P95 sur cinq valeurs.
- [x] Ajouter des tests sur le plan d'échantillonnage et les seuils de comparabilité.


## Version 1.9.0-dev — Inspection des mesures brutes

- [x] Enregistrer chaque essai HTTPS avec son cycle, son index, son temps, son code HTTP ou sa cause d'échec.
- [x] Enregistrer séparément les cinq essais DNS, TCP, TLS et ligne de statut HTTP par destination.
- [x] Afficher les tentatives HTTPS individuelles de la dernière session.
- [x] Afficher les phases individuelles de la dernière analyse Route Engine.
- [x] Exporter les mesures brutes et les phases au format CSV.
- [x] Conserver les dix dernières sessions dans l'historique local.
- [ ] Vérifier sur appareil réel l'affichage des 25 essais, les échecs et l'export CSV.

**Limite :** les anciennes sessions enregistrées avant 1.9.0-dev ne contiennent que des résumés. Les mesures brutes ne sont disponibles que pour les diagnostics réalisés après installation de cette version.

## Version 1.10.0-dev — Comparaison prudente des points de contrôle

- [x] Distinguer le classement de fiabilité (connexions HTTPS complètes sur cinq essais) du classement des délais de phase.
- [x] Ne classer la fiabilité que lorsque plusieurs destinations disposent chacune d'un lot complet de cinq essais.
- [x] Signaler explicitement les données insuffisantes ou l'absence de gagnant clair.
- [x] Comparer les sommes des médianes DNS, TCP, TLS et HTTP uniquement si chaque phase dispose d'au moins trois mesures réussies pour au moins deux destinations.
- [x] Préciser que la somme des médianes de phase est indicative et n'est pas une mesure directe du temps total de connexion.
- [x] Interdire toute assimilation du meilleur point de contrôle au meilleur serveur de jeu.
- [x] Ajouter des tests unitaires pour les classements, les égalités et les données incomplètes.

**Limite :** ces comparaisons portent uniquement sur les points de contrôle HTTPS publics. Elles ne mesurent ni le ping CODM, ni la route réelle du match, ni une amélioration de routage.


## Version 1.11.1-dev — Comparaison fiable des sessions

- [x] Ajouter une comparaison locale entre deux sessions terminées ayant le même profil de jeu et le même type de connexion.
- [x] Exclure les sessions interrompues par un changement de réseau.
- [x] Comparer les médianes HTTPS destination par destination et afficher les écarts en millisecondes.
- [x] Afficher les nombres de réponses réussies et ignorer les paires avec moins de 15 réponses exploitables ou des plans de mesure différents.
- [x] Vérifier les 25 positions d'essai brutes et comparer exactement les positions réussies, pas seulement leur nombre.
- [x] Refuser les sessions anciennes ou incomplètes qui ne contiennent pas les 25 essais bruts nécessaires.
- [ ] Vérifier sur appareil réel l'affichage, les anciennes sessions et les cas de données insuffisantes.

**Limite :** la comparaison porte sur les points de contrôle HTTPS publics. Elle ne mesure pas l'évolution du ping en jeu et ne démontre pas une amélioration de la route vers un serveur de match. Les résultats restent sensibles à l'heure, à la charge locale et au comportement des services distants.


## Version 1.12.0-dev — Fiabilité du diagnostic TLS

- [x] Distinguer les délais dépassés pendant la négociation TLS des autres erreurs TLS.
- [x] Distinguer les erreurs de protocole TLS des erreurs de certificat.
- [x] Ajouter des tests unitaires pour les catégories de délais et de protocole.
- [x] Mettre à jour l'identifiant User-Agent des requêtes.
- [ ] Vérifier sur appareil réel la classification du problème de certificat Microsoft et les cas de délai TLS.

**Limite :** la classification décrit l'exception observée sur un point de contrôle. Elle ne prouve pas une panne générale du réseau et ne désactive jamais la validation des certificats.


## Version 1.13.0-dev — Interface animée et pont d'optimisation

- [x] Ajouter une entrée progressive des sections de l'interface.
- [x] Ajouter un retour visuel léger sur les actions de diagnostic.
- [x] Isoler les animations dans un composant de présentation sans lien avec les sondes réseau.
- [x] Ajouter un emblème NOVA animé à l'en-tête du tableau de bord.
- [x] Ajouter l'icône vectorielle NOVA PING et la déclarer dans le manifeste Android.
- [ ] Ajouter les animations d'état pendant les cycles de mesure, en gardant les horodatages et tâches réseau indépendants de l'interface.
- [ ] Valider la fluidité sur appareil Android réel et vérifier l'impact sur la consommation.
- [x] Définir le contrat de données pour comparer une session directe et une session optimisée.
- [ ] Connecter le moteur d'optimisation réel après réception de son API, de ses formats de résultats et de ses règles de sécurité.
- [ ] Réaliser des essais A/B comparables avant toute affirmation de gain.

**Limite :** cette version prépare l'interface et le contrat d'intégration. Aucun moteur de routage n'est activé par ce travail et aucun trafic n'est redirigé.

## Version 1.14.0-dev — Tableau de bord réseau

- [x] Présenter séparément le transport actif, la validation Internet Android et le statut de réseau mesuré.
- [x] Rafraîchir les indicateurs au retour de l'application au premier plan.
- [x] Clarifier que la bande passante affichée provient d'une estimation Android et n'est pas mesurée par NOVA.
- [x] Conserver les composants de diagnostic, les historiques et l'export CSV inchangés.
- [ ] Vérifier sur appareil réel l'affichage en Wi-Fi, en données mobiles et hors connexion.
- [ ] Contrôler la lisibilité sur plusieurs tailles d'écran et la fluidité des animations.

**Limite :** le tableau de bord actualise l'état réseau à l'ouverture et au retour au premier plan. Il ne réalise pas encore de rafraîchissement continu à chaque changement réseau et ne mesure pas le débit réel.


## Version 1.14.0-dev — Dashboard réseau

- [x] Afficher le transport réseau actif (Wi-Fi, données mobiles, VPN, Ethernet ou autre).
- [x] Distinguer la capacité Internet de la validation Internet déclarée par Android.
- [x] Afficher séparément le statut réseau mesuré (réseau facturé) selon le signal Android.
- [x] Rafraîchir les indicateurs lorsque l'activité revient au premier plan.
- [x] Clarifier que les estimations de bande passante Android ne sont pas des mesures de débit.
- [x] Améliorer la hiérarchie visuelle des cartes, boutons et détails de résultats.
- [ ] Vérifier les indicateurs sur appareil réel pendant les bascules Wi-Fi / données mobiles / VPN.
- [ ] Vérifier l'accessibilité, les tailles d'écran et le contraste sur plusieurs appareils.

**Limite :** l'actualisation du tableau de bord relit l'état Android local ; elle ne télécharge pas une nouvelle version de l'application et ne met pas à jour l'APK installé. Les numéros de builds GitHub Actions sont indépendants de `versionName` et `versionCode` Android.


## Version 1.19.0-dev — Intégrité des comparaisons A/B

- [x] Refuser les séries directes et candidates de tailles différentes au lieu de les apparier silencieusement par troncature.
- [x] Exiger une identité directe stable et un même profil candidat (libellé et serveur VPN) sur toutes les paires.
- [x] Refuser les lots contenant des hôtes vides ou dupliqués.
- [x] Exiger cinq tentatives et au moins trois observations complètes sur chaque phase nécessaire avant comparaison.
- [x] Ajouter des tests de non-régression pour les séries déséquilibrées, les hôtes dupliqués, les profils changeants et les phases incomplètes.

**Limite :** la comparaison reste une analyse de points de contrôle HTTPS. Elle ne mesure pas le ping du jeu, ne prouve pas une amélioration de routage et ne modifie pas le réseau Android.


## Version 1.19.0-dev — Intégrité des comparaisons

- [x] Exiger au moins trois paires de séries comparables.
- [x] Refuser les transports différents, les lots incomplets, les hôtes dupliqués et les profils candidats changeants.
- [x] Séparer explicitement les points de contrôle HTTPS des serveurs de match.
- [x] Tests unitaires, Android Lint, APK Debug et APK Release validés par GitHub Actions (build #323).
- [ ] Confirmer sur appareil réel le verdict et le nombre de paires valides affichés après une comparaison.

## Version 1.20.0-dev — Registre de preuves Game Intelligence

- [x] Ajouter un modèle de preuve serveur lié à un jeu, une région, un hôte et un protocole.
- [x] Exiger une provenance HTTPS, une date de publication, une date de vérification et une échéance.
- [x] Rejeter les preuves expirées, mal formées ou dont la chronologie est incohérente.
- [x] Ajouter des tests unitaires de provenance, d'expiration et de filtrage par jeu.
- [ ] Connecter le registre à l'interface Game Intelligence.
- [ ] N'ajouter aucune destination de match tant qu'une source fiable, datée et vérifiable ne la confirme.
- [ ] Ne pas confondre une preuve documentaire d'hôte avec la preuve qu'Android peut mesurer cet hôte depuis l'appareil.

**État initial :** aucun endpoint de match CODM n'est ajouté par cette version. Le registre est une fondation technique ; les profils restent non vérifiés tant qu'aucune preuve admissible n'est enregistrée.

## Version 1.21.0-dev — Intégration Game Intelligence

- [x] Relier le sélecteur de jeu à l'état du registre de preuves.
- [x] Afficher explicitement l'absence de serveur de match vérifié par jeu.
- [x] Séparer les points de contrôle Internet des destinations de match.
- [x] Ne pas injecter d'adresse IP ou de région non confirmée.
- [ ] Ajouter des enregistrements uniquement après validation d'une source publique fiable et du rôle réel de serveur de match.
- [ ] Ajouter une sonde dédiée uniquement lorsque le protocole et l'accessibilité de mesure sont établis.
- [ ] Vérifier l'interface et les APK sur appareil Android réel.

**Limite :** cette version intègre l'état de preuve dans l'interface ; elle ne découvre pas encore automatiquement les adresses IP des serveurs de match. Le registre est actuellement vide.


## Version 1.22.0-dev — Références réseau publiques Game Intelligence

- [x] Ajouter un registre distinct pour les informations de connectivité publiées par les éditeurs.
- [x] Intégrer la table officielle Activision des ports CODM Android/iOS : TCP 80, 443, 65010 (salon), 65050 (chat) et UDP 7500–8000.
- [x] Vérifier le domaine HTTPS de provenance, le protocole et les bornes de ports avant affichage.
- [x] Afficher la date de consultation de la source et les limites d'interprétation dans l'interface.
- [x] Garder le statut « aucun serveur de match vérifié » tant qu'aucune adresse et aucun rôle de serveur de match ne sont confirmés.
- [x] Ajouter des tests de filtre par jeu, de provenance, de protocole et de plage de ports.
- [ ] Vérifier sur appareil réel la lisibilité de la nouvelle référence CODM.

**Limite :** la documentation Activision publie des ports mais pas les IP des serveurs de match. Les ports ne permettent pas, seuls, d'identifier un hôte distant, de distinguer à coup sûr un serveur de lobby d'un serveur de partie, ni de mesurer le ping CODM. La table doit être revalidée sur la source officielle avant toute nouvelle certification.

**Source officielle consultée le 14 novembre 2026 :** https://support.activision.com/fr/articles/ports-used-for-call-of-duty-games


## Version 1.23.0-dev — Modèle d'observation des endpoints de jeu

- [x] Ajouter un modèle d'observation des IP/ports distants associés à une application de jeu explicitement sélectionnée.
- [x] Exiger un état de consentement explicite et l'identité exacte du package attendu.
- [x] Valider les adresses IP numériques publiques sans résolution DNS.
- [x] Agréger les observations par jeu, package, IP, port et transport.
- [x] Distinguer les observations répétées sur plusieurs sessions des répétitions au sein d'une seule session.
- [x] Ne jamais assimiler une observation à la preuve d'un serveur de match.
- [x] Ajouter les tests unitaires du filtrage, de la validation IP et de l'agrégation.
- [ ] Implémenter une collecte Android réellement consentie, limitée à l'application choisie, sans perturber son trafic.
- [ ] Connecter les candidats observés à l'interface et à l'historique local.
- [ ] Confirmer le rôle de serveur de match par une preuve indépendante avant toute mesure dédiée.

**Limite :** cette version ajoute le modèle et les règles d'agrégation, pas encore la capture réseau Android. L'architecture de capture doit traiter correctement l'interface TUN et le transfert des paquets avant toute activation. Voir `docs/GAME-ENDPOINT-DISCOVERY.md`.
