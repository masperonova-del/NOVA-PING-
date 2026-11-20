# NOVA PING — protocole de comparaison DIRECT / CANDIDATE

## Objectif

Comparer, sur un même réseau physique, le chemin Internet direct et un chemin candidat à partir de mesures répétées vers les mêmes points de contrôle HTTPS.

Cette expérience ne mesure pas le ping d'un serveur de match. Elle ne fait ni traceroute, ni découverte d'adresses de serveurs de jeu, ni modification automatique du routage.

## Parcours dans l'application

1. Ouvrir la section ROUTE ENGINE — ANALYSE DES PHASES.
2. Capturer un lot DIRECT lorsque le VPN candidat est désactivé.
3. Activer le chemin candidat déjà configuré, puis capturer CANDIDATE.
4. Répéter cette paire trois fois, en gardant le même réseau physique et les mêmes destinations.
5. Appuyer sur COMPARER LES SÉRIES.
6. Si les conditions ont changé, réinitialiser l'expérience et recommencer plutôt que mélanger des mesures incompatibles.

Le bouton de capture ne démarre ni n'arrête un VPN. Il ne fait que mesurer le chemin actuellement actif. Le tunnel NOVA PING intégré n'est pas considéré comme candidat tant qu'il n'utilise pas un relais distant configuré et vérifiable.

## Provenance obligatoire

Chaque lot conserve localement :

- un identifiant d'expérience et un identifiant de lot ;
- le rôle DIRECT ou CANDIDATE ;
- le type de transport physique observé ;
- un fingerprint éphémère basé sur le handle Android du réseau physique ;
- les heures de début et de fin ;
- le profil de mesure et l'ensemble des hôtes testés ;
- les agrégats par phase et le nombre de mesures réussies.

Le fingerprint n'est pas un identifiant de serveur ni une preuve cryptographique du chemin réseau. Aucun SSID, adresse MAC, contenu applicatif ou charge utile de paquet n'est enregistré. Si Android ne permet pas d'identifier un unique réseau physique sous le VPN, l'application refuse d'enregistrer le lot.

## Règles d'acceptation

- Trois paires DIRECT/CANDIDATE sont nécessaires.
- Chaque paire doit conserver le même transport physique, le même fingerprint, le même profil de mesure et le même ensemble de destinations.
- Le lot CANDIDATE doit commencer après le lot DIRECT correspondant, avec un écart maximal de 30 minutes. Le même libellé de profil ou serveur candidat doit être conservé sur les trois captures.
- Chaque destination doit avoir cinq échantillons planifiés et au moins trois échantillons complets pour chaque phase.
- Une amélioration doit atteindre au moins 5 % sur la somme des médianes de phases, sans dégradation du P95 observé, de la variation ni du nombre de réponses réussies.
- Une variation plus faible est classée comme inconclusive, pas comme une optimisation.
- Une baisse de fiabilité ou une dégradation répétée produit un verdict de régression.

Le P95 calculé à partir de cinq échantillons reste une statistique descriptive de petit échantillon ; il ne constitue pas une garantie de performance.

## Limites Android et sécurité

Android n'autorise qu'une seule application VPN active à la fois. Une comparaison avec un chemin candidat VPN doit donc être faite en dehors du tunnel NOVA PING actuel. Les sockets du futur relais NOVA devront continuer à être protégés contre les boucles de routage conformément au modèle Android VpnService.

Référence plateforme : https://developer.android.com/develop/connectivity/vpn

## Critères avant toute sélection automatique

Cette expérience n'active aucun routage automatique. Avant de choisir automatiquement un chemin, il faudra :

- disposer d'un relais distant NOVA provisionné, authentifié et mesuré ;
- mesurer la disponibilité, la latence, la stabilité et les pertes du relais ;
- répéter les comparaisons sur plusieurs périodes et réseaux ;
- définir un seuil d'avantage matériel et une politique de retour direct ;
- valider les transitions et la restauration de connectivité sur appareils physiques ;
- distinguer les mesures HTTPS des véritables mesures de serveurs de jeu, qui nécessitent des endpoints de match vérifiés et une méthode de mesure autorisée.

## État de cette version

La version route-comparison-v1 fournit la capture manuelle des lots, le stockage local des agrégats, les contrôles de comparabilité et l'affichage du verdict. Elle ne fournit pas de relais distant, de ping CODM certifié ni de sélection automatique.
