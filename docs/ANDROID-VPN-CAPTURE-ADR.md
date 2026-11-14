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
