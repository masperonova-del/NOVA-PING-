# ADR-001 — Android capture and packet-forwarding architecture

- **Status:** evaluated; candidate engine identified, live integration blocked pending a packet-observation hook and local SOCKS5 forwarding bridge.
- **Date:** 14 November 2026
- **Scope:** user-consented, app-scoped endpoint metadata for game-network diagnostics.

## Context

The IPv4/IPv6 metadata parser is now implemented as a pure Kotlin component. It accepts packet bytes and explicit local TUN addresses, and returns only IP version, remote address, remote port, transport, direction, packet length and fragmentation status. It does not receive live packets yet.

A live Android capture needs two independent capabilities:

1. A correctly functioning Android TUN/VPN data path, restricted to the selected game and approved by the user.
2. A non-disruptive observation hook that feeds packet headers to the metadata parser while the original game packets continue through the forwarding engine.

The observation path must never become a second reader competing for the same TUN file descriptor.

## Verified platform constraints

Android's `VpnService` requires a system consent flow. The app must offer visible start/stop controls and an ongoing notification while active. The service owns a TUN interface and must transfer outgoing packets onward and write replies back. Sockets used by the forwarding engine must be protected with `VpnService.protect()` to avoid routing loops. Android also supports per-app VPN configuration.

Official Android guide: https://developer.android.com/develop/connectivity/vpn

## Forwarding engine evaluated: HEV tun2socks

The upstream `heiher/hev-socks5-tunnel` project documents Android support, IPv4/IPv6, TCP forwarding and UDP relay. It publishes a self-contained Android AAR for four ABIs and a JNI interface accepting a TUN file descriptor. Its documented configuration requires a SOCKS5 server address and port (the sample uses localhost).

- Project: https://github.com/heiher/hev-socks5-tunnel
- Current release checked on this date: 2.20.0
- License: MIT
- Android AAR: `hev-socks5-tunnel.aar`
- JNI API: `hev.htproxy.TProxyService.TProxyStartService(configPath, tunFd)`, plus stop/status/statistics methods.

The library is a promising forwarding component, not a complete NOVA capture solution. The documented API hands the TUN descriptor to native code and does not expose a documented per-packet metadata callback. NOVA PING cannot safely start a second reader on that same TUN queue: it could consume packets intended for the forwarding engine and break game traffic.

HEV also forwards to a SOCKS5 endpoint; the AAR itself does not provide a public Internet exit or a local direct-connect SOCKS5 server.

Upstream technical references:
- Features and SOCKS5 configuration: https://github.com/heiher/hev-socks5-tunnel#hev-socks5-tunnel
- C / Java / Kotlin API and prebuilt AAR: https://github.com/heiher/hev-socks5-tunnel#api

## Decision

Keep HEV as the **preferred forwarding candidate for a prototype**, but do not add its AAR or start a VPN until both of these gaps are resolved:

1. **Metadata tap:** either maintain a small, reviewable native fork that copies only the IPv4/IPv6 and TCP/UDP headers into a non-blocking observation callback, or select an engine that already provides a safe packet callback. The observation callback must never block, modify, or delay packet forwarding. If the observer is overloaded, drop metadata samples and keep forwarding.
2. **Direct-connect SOCKS5 bridge:** because NOVA PING has no configured relay server, implement a local SOCKS5 bridge on loopback. It must support TCP CONNECT and UDP ASSOCIATE, keep DNS behavior correct, and call `VpnService.protect()` on every upstream socket before connecting so the bridge does not re-enter its own VPN.

If a future release uses a remote relay instead, the server must be provisioned, authenticated, encrypted and independently measured. No relay hostname, credentials or server are assumed to exist today.

## Required architecture

```text
Selected game package only
        |
Android VpnService (explicit system consent)
        |
TUN packet path
        |
Non-blocking metadata tap ----> GamePacketMetadataParser
        |                          |
        |                          v
        |                 local observation registry
        v
HEV tun2socks (candidate)
        |
Local SOCKS5 bridge (127.0.0.1)
        |
VpnService-protected TCP/UDP sockets
        |
Current Wi-Fi or mobile network
```

The tap must be integrated into the packet path; the two arrows must not mean two consumers reading the same TUN descriptor.

## Activation gate

Live capture stays disabled until automated tests and device checks establish all of the following:

- System consent is requested and revocation is handled.
- `Builder.addAllowedApplication` restricts the VPN to the exact selected game package.
- NOVA PING's control traffic and the SOCKS5 bridge's upstream sockets bypass the VPN using `protect()`.
- TCP CONNECT, UDP ASSOCIATE, DNS over UDP/TCP, IPv4 and IPv6 work end-to-end.
- Stop, crash, permission revocation and Wi-Fi/mobile transitions restore normal connectivity.
- The metadata tap never delays or mutates packet forwarding.
- No packet payload, credentials or message contents are stored or uploaded.
- A failed observer drops observations, not game packets.

Until every gate passes, no VPN activation, traffic redirection, app UI toggle or “live endpoint” claim is enabled. A manifest service declaration and a fail-closed platform adapter are present only as preparation; they do not establish a TUN interface.

## Current outcome

The pure Kotlin packet parser and its unit tests are complete. A loopback-only SOCKS5 bridge now implements authenticated TCP CONNECT and UDP ASSOCIATE. It uses per-instance ephemeral RFC 1929 credentials, requires protection callbacks for upstream TCP and UDP sockets, pins UDP traffic to the first datagram endpoint from the authenticated TCP peer IP, rejects fragmented SOCKS5 UDP frames, limits concurrent UDP forwarding and shuts the association down with its TCP control connection. Domain destinations are resolved only through the injected protected DNS resolver for both TCP CONNECT and UDP ASSOCIATE. If it is absent or fails, the request is rejected; no system DNS fallback occurs. Numeric IPv4/IPv6 destinations remain supported. A strict DNS wire codec now builds A/AAAA questions and validates transaction IDs, flags, the echoed question, resource-record framing, address lengths and TTLs. A positive in-memory cache enforces answer TTLs with an upper bound, avoids caching empty/zero-TTL answers and returns defensive copies. These are protocol and cache primitives only: no DNS network transport exists yet, and domain forwarding remains disabled. The next step is a controlled UDP/TCP transport whose sockets are protected before use, with no system-resolver fallback. Local TCP and UDP echo tests exercise the relay paths. Android live capture, TUN forwarding, the metadata tap, controlled DNS handling and UI integration remain unimplemented. The UDP association now also honors a nonzero client-advertised UDP port and rejects a non-wildcard advertised address that differs from the authenticated TCP peer. A regression test verifies that datagrams from an unadvertised source port are not forwarded. The bridge is not wired into a VPN and must not be treated as a traffic optimizer. CI validates source/tests/APK compilation only; it does not validate live network forwarding or CODM match-server discovery.


## Transport DNS contrôlé — implémentation initiale

Le résolveur contrôlé utilise une adresse de résolveur fournie sous forme d'`InetAddress` numérique (aucune résolution du nom du résolveur). Chaque socket UDP ou TCP est soumis à son callback de protection avant toute connexion ou émission. Les réponses UDP proviennent d'un socket connecté au résolveur configuré ; les réponses sont validées par le codec DNS existant avec l'identifiant, le nom, le type et les longueurs attendus. Une réponse marquée tronquée déclenche une nouvelle requête DNS sur TCP avec cadrage RFC 1035 et délai borné.

Le résolveur utilise le cache positif existant. Il ne fait aucun fallback vers le résolveur système. Le transport est couvert par des tests de boucle locale UDP, de repli TCP et de refus lorsque la protection échoue.

**Limites restantes :** le résolveur n'est pas encore branché au pont SOCKS5 ; les destinations par nom de domaine restent donc bloquées. La prise en charge des chaînes CNAME, des réponses DNS négatives et d'EDNS n'est pas implémentée. Aucun résolveur public n'est présélectionné : l'adresse doit être fournie explicitement par la configuration future.


## Intégration DNS au pont SOCKS5

Le résolveur contrôlé expose maintenant une résolution d'adresse qui interroge A puis AAAA et utilise le cache positif. Le pont SOCKS5 accepte ce résolveur comme dépendance optionnelle et l'utilise pour les destinations de domaine en TCP CONNECT et UDP ASSOCIATE. Si le résolveur n'est pas configuré ou si la résolution échoue, la connexion échoue ; aucun appel au résolveur système n'est effectué. Les destinations IP numériques continuent d'utiliser le chemin direct protégé.

**Configuration non terminée :** l'application n'instancie pas encore ce résolveur avec une adresse numérique de serveur DNS choisie et des callbacks raccordés à VpnService.protect(). Cette intégration doit être faite dans le futur service VPN, avec consentement explicite. Aucun domaine ne doit être présenté comme pris en charge en production avant cette configuration et les tests de bout en bout.


## Adaptateur Android VpnService — préparation fail-closed

Le manifeste déclare maintenant `NovaVpnService` avec la permission système `BIND_VPN_SERVICE` et l'action `android.net.VpnService`. L'adaptateur fournit :
- un constructeur de configuration VPN limité à un seul package de jeu installé via `Builder.addAllowedApplication()` ;
- des méthodes de protection des sockets TCP et UDP qui délèguent à `VpnService.protect()`.

L'écran de préparation VPN est maintenant présent dans `MainActivity` :
- il propose les applications installées déclarées par Android comme jeux et possédant une activité de lancement ;
- il appelle `VpnService.prepare()` et affiche la boîte de dialogue système uniquement si une autorisation est nécessaire ;
- il indique explicitement le résultat du consentement sans démarrer de tunnel.

Cette étape ne démarre pas de VPN et n'appelle pas `Builder.establish()`. Le package choisi n'est pas encore transmis au service pour créer le tunnel. L'instanciation du résolveur DNS avec une adresse numérique explicitement configurée, le moteur TUN/tun2socks et le tap de métadonnées restent à intégrer. La déclaration du service et l'accord de consentement Android ne signifient donc pas que le trafic est actuellement capturé ou optimisé.


## Liaison de la sélection au service — préparation uniquement

Après le retour positif de la boîte de dialogue Android, l'application valide et mémorise le package sélectionné dans des préférences VPN dédiées. Si l'autorisation était déjà accordée, le package est mémorisé immédiatement après validation. Le service relit ce package au moment où son futur chemin de préparation est appelé, vérifie à nouveau sa syntaxe et sa présence dans PackageManager, puis construit un `Builder` limité à ce package avec `addAllowedApplication()`.

La préparation du builder vérifie aussi que le consentement Android est toujours accordé. Aucun service n'est démarré par l'écran de sélection, aucun `Builder.establish()` n'est appelé et aucun trafic n'est redirigé. Une sélection absente, invalide ou désinstallée échoue sans fallback. Des tests unitaires couvrent la validation syntaxique des noms de package ; la vérification d'installation reste assurée par Android PackageManager.


## Audit d'intégration — décision du 14 novembre 2026

Une vérification du dépôt NOVA PING et de l'API amont HEV a été effectuée avant toute intégration native.

### État réel du dépôt

- `NovaVpnService` prépare un `VpnService.Builder` limité au package de jeu sélectionné et expose les wrappers `protect()` TCP/UDP.
- Le consentement Android et la mémorisation du jeu sont présents.
- Le service n'appelle pas `Builder.establish()`, ne démarre pas de moteur TUN et ne redirige aucun trafic.
- `LocalSocks5Bridge`, son relais UDP et le résolveur DNS contrôlé existent comme composants testables, mais aucun service Android ne les démarre ni ne les raccorde à un moteur natif.
- La dépendance WireGuard est déjà dans Gradle, mais `WireGuardEngine` ne fait qu'instancier `GoBackend` et lire sa version. Aucun profil WireGuard, pair, endpoint, clé ou relais n'est configuré. Ce backend ne constitue donc pas un chemin de transfert actif.

### Vérification de l'API HEV

Le README amont consulté expose pour Android un AAR avec les méthodes JNI suivantes : démarrage avec un chemin de configuration et le descripteur TUN, arrêt, état d'exécution et statistiques de trafic. L'API documentée ne décrit aucun callback de paquet ni mécanisme d'observation par paquet. Le moteur attend un serveur SOCKS5 configuré ; il ne fournit pas lui-même un serveur de sortie Internet.

Référence amont consultée : https://github.com/heiher/hev-socks5-tunnel

Conséquence : l'AAR standard ne peut pas, à lui seul, alimenter `GamePacketMetadataParser`. Lire le même descripteur TUN depuis un second thread n'est pas une solution : les deux lecteurs se disputeraient les paquets et pourraient interrompre le transfert. Les statistiques globales HEV ne remplacent pas les observations IP/port/transport par flux.

### Décision technique

1. **Ne pas intégrer ni démarrer l'AAR HEV standard dans cette étape.** Son interface publique documentée ne satisfait pas l'exigence de tap de métadonnées.
2. **Ne pas activer WireGuard comme solution de remplacement.** Il nécessite un pair et un serveur WireGuard provisionnés, absents du dépôt. Ajouter un tunnel sans endpoint valide ne ferait que créer une fausse progression et pourrait couper la connexion.
3. Garder le service VPN en mode préparation uniquement. Aucun `establish()`, aucune route par défaut, aucun bouton d'activation et aucun trafic de jeu redirigé.
4. Pour la prochaine preuve technique, comparer deux voies réelles :
   - un fork natif HEV, versionné et reproductible, avec un hook de copie d'en-têtes dans le chemin de traitement existant, callback borné/non bloquant et tests natifs ;
   - un moteur qui expose déjà une API d'observation sûre, sans second lecteur TUN.
5. Avant toute compilation native, figer le commit amont exact, vérifier les ABI livrées, la licence, la chaîne NDK et la reproductibilité de l'AAR. Ne pas utiliser une dépendance mutable de type `latest`.
6. Une fois le tap disponible, raccorder le callback à une file bornée puis au parseur Kotlin. Si la file est pleine, abandonner l'échantillon de télémétrie, jamais le paquet original.

### Critères de sortie de cette étape

- Le choix du moteur est justifié par son API réellement disponible, pas seulement par ses performances ou sa compatibilité Android.
- Un test natif démontre que le tap reçoit des métadonnées sans consommer, modifier ou bloquer les paquets transférés.
- Les sockets de sortie du pont SOCKS5 sont protégés avant toute connexion.
- Un test local de bout en bout vérifie TCP, UDP et DNS, puis les scénarios arrêt, révocation et changement de réseau.
- Les vérifications sur appareil confirment que la connectivité normale revient après arrêt ou erreur.
- Tant que ces critères ne sont pas atteints, l'interface et la documentation continuent d'indiquer clairement qu'aucun tunnel n'est actif.

Cette décision remplace toute interprétation selon laquelle la seule présence de l'AAR HEV ou de la bibliothèque WireGuard suffirait à rendre le moteur TUN opérationnel.


## Inspection du chemin de paquets HEV — précision du point d'instrumentation

Inspection du code source amont (branche `main`, dépôt consulté le 14 novembre 2026) :

- Dans `src/hev-socks5-tunnel.c`, `lwip_io_task_entry()` lit les paquets TUN avec `hev_tunnel_read()`, met à jour les compteurs, puis remet le `pbuf` à `netif->input()`. C'est le point d'entrée du trafic reçu depuis Android, avant traitement par lwIP.
- Dans le même fichier, `netif_output_handler()` reçoit le `pbuf` sortant et appelle `hev_tunnel_write()`. C'est le point de sortie vers le descripteur TUN.
- Ces deux emplacements couvrent les deux directions du chemin de données sans ouvrir un second lecteur sur le descripteur TUN.
- `src/hev-jni.c` enregistre actuellement quatre méthodes JNI : démarrer, arrêter, état et statistiques. Il n'existe pas de méthode de remontée des métadonnées.
- Le callback JNI synchrone depuis la boucle de transfert serait à éviter : il introduirait un coût et un risque de blocage dans le chemin critique.

### Conséquence pour le parseur NOVA PING

Le parseur Kotlin actuel valide les longueurs déclarées du paquet et exige que les octets nécessaires aux en-têtes IP et TCP/UDP soient présents. Lui transmettre un préfixe tronqué sans adapter son contrat serait incorrect.

Le premier prototype doit donc utiliser une extraction native des seuls champs de métadonnées à ces deux points, puis publier des enregistrements fixes dans une file SPSC bornée et non bloquante. Le thread de transfert ne doit jamais attendre le consommateur ; si la file est pleine, seul l'échantillon de télémétrie est abandonné. Aucun contenu applicatif ni charge utile ne doit entrer dans la file.

L'extraction native doit vérifier les longueurs d'en-tête, IPv4 IHL, extensions IPv6, fragmentation, protocoles TCP/UDP, ports et adresses locales/distantes. Les cas ambigus ou malformés sont ignorés. Les tests doivent comparer les résultats natifs avec les cas de référence du parseur Kotlin.

### Séquencement de réalisation

1. Figer le commit amont HEV exact et archiver les références de source/licence utilisées.
2. Définir un enregistrement C stable (version, direction, famille IP, protocole, adresses, ports, longueur, indicateur de fragmentation).
3. Ajouter le hook de métadonnées aux deux points identifiés, sans modifier le `pbuf` et sans bloquer le transfert.
4. Exposer une lecture JNI par lots, hors chemin de transfert, avec limites strictes et arrêt propre.
5. Ajouter tests C pour IPv4/IPv6, extensions, fragments, paquets malformés et saturation de file ; ajouter tests JNI/Kotlin pour décodage et cycle de vie.
6. Seulement après ces tests, intégrer l'AAR natif au service Android encore inactif.

Aucune instrumentation native ni activation VPN n'est déclarée comme réalisée par cette inspection. Il s'agit du plan d'implémentation fondé sur les points de code vérifiés.
