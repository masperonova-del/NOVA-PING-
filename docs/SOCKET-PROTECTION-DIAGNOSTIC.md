# NOVA PING — Diagnostic des sockets amont

## Constat initial

Le rapport de l'appareil montrait 512 refus `protect()` pour `TCP_CONNECT`, alors que l'autorisation VPN, l'interface TUN, le descripteur TUN et le moteur HEV étaient présents. Les sockets étaient signalés comme ouverts, non connectés et non liés. Aucun appel réel à `Socket.connect()` n'était effectué.

## Cause architecturale identifiée

Le service configure un VPN par application avec `Builder.addAllowedApplication(gamePackage)`. Selon la documentation Android, lorsqu'une liste d'applications autorisées est définie, seules les applications de cette liste utilisent le VPN ; les autres utilisent le réseau système comme si le VPN n'était pas actif.

Le processus NOVA PING n'est pas dans cette liste : seul le jeu sélectionné est ajouté. Les sockets amont créés par le pont SOCKS5 et le résolveur DNS appartiennent donc au processus NOVA PING et sont déjà hors du VPN. Appeler `VpnService.protect()` sur ces sockets n'est pas nécessaire pour éviter une boucle de routage.

Références Android :
- https://developer.android.com/reference/android/net/VpnService.Builder#addAllowedApplication(java.lang.String)
- https://developer.android.com/develop/connectivity/vpn

## Correction

- Introduire `VpnRoutingPolicy` pour refléter les règles de la liste d'applications Android.
- Ne pas appeler `protect()` pour les sockets amont du processus NOVA PING lorsque ce processus est absent de la liste autorisée.
- Continuer à vérifier les préconditions des sockets.
- Conserver l'appel réel à `protect()` si le processus NOVA PING est lui-même routé dans le VPN (liste vide ou package présent).
- Distinguer dans le rapport les sockets « bypassés par la liste d'applications » des sockets effectivement protégés et des refus de protection.

Cette correction ne désactive pas le VPN du jeu et ne contourne pas un refus de protection lorsqu'une protection est réellement nécessaire.

## Vérification attendue sur appareil

Après installation de la nouvelle build et démarrage du tunnel avec un jeu sélectionné :

1. Les compteurs `bypassés par la liste d'applications` doivent augmenter pour les sockets amont de NOVA PING.
2. Les refus `protect()` dus à ces sockets doivent disparaître.
3. Les appels réels `Socket.connect()` doivent être observés.
4. Les connexions TCP peuvent encore échouer pour des raisons réseau indépendantes ; il faudra lire leurs catégories.
5. Vérifier ensuite que le jeu est toujours bien sélectionné dans l'allowlist et que le tunnel reste stable.

La CI ne remplace pas ce test physique. Aucun gain de ping CODM ne peut être conclu à partir de ce diagnostic seul.

## Contrôle de non-régression

Les tests unitaires de politique vérifient les trois cas : liste limitée au jeu, liste vide (toutes les applications passent dans le VPN), et NOVA PING explicitement inclus. La compilation Android et le lint doivent ensuite être exécutés par Android CI sur cette branche avant toute installation.
