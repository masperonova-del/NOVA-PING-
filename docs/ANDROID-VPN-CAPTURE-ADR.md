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


## Verrouillage de la source HEV et compatibilité Android — 14 novembre 2026

La vérification de provenance a identifié un commit amont immuable pour la prochaine expérimentation :

- Dépôt : https://github.com/heiher/hev-socks5-tunnel
- Commit exact consulté : `75dbc1e9ace6c70d3d1b94df7c283efe1de7effc`
- Révision amont : `HevConfig: Bump up to 2.20.0.`
- Licence du fichier racine `LICENSE` : MIT. Toute redistribution du code ou d'une portion substantielle devra conserver l'avis de copyright et le texte de permission.
- Les fichiers Android amont `Android.mk` et `Application.mk` ont été inspectés. La construction amont déclare les ABI `armeabi-v7a`, `arm64-v8a`, `x86` et `x86_64`, ainsi que `APP_PLATFORM := android-29`. Le linkage inclut aussi les options de pages mémoire de 16 Kio.

### Blocage de compatibilité à résoudre avant l'AAR

NOVA PING déclare actuellement `minSdk = 26`, tandis que la configuration Android amont inspectée cible la plateforme 29. Cela ne prouve pas à lui seul une incompatibilité à l'exécution, mais interdit de supposer que l'AAR précompilé fonctionnera sur tous les appareils actuellement pris en charge par NOVA PING.

Avant toute intégration, il faut choisir et vérifier l'une de ces stratégies :
1. reconstruire HEV avec un niveau NDK compatible avec le `minSdk` retenu, puis tester sur appareil/API minimale ;
2. ou relever explicitement le `minSdk` de NOVA PING si le produit accepte réellement d'abandonner les appareils plus anciens.

La présence de quatre ABI dans le script amont ne garantit pas que chaque artefact AAR publié contienne ces quatre ABI : les bibliothèques natives de l'artefact devront être inspectées séparément. La provenance, les ABI réellement empaquetées, le niveau API et la reproductibilité de la construction seront des contrôles CI avant toute distribution.

**Décision :** le commit HEV ci-dessus devient la référence source candidate pour le prototype natif, pas une dépendance intégrée. Aucun AAR n'est ajouté, aucune ABI n'est déclarée validée et le service VPN reste inactif.

## Prototype natif de métadonnées — première tranche isolée

Un premier composant C indépendant du moteur HEV a été ajouté sous `native/metadata/` :

- `packet_metadata.h/.c` définit un enregistrement fixe contenant uniquement version IP, famille/adresse distante, transport, direction, port distant, longueur du paquet et indicateur de fragmentation.
- Le parseur traite IPv4 et IPv6, les extensions IPv6 Hop-by-Hop, Routing, Destination Options, Fragment et AH, ainsi que les en-têtes TCP/UDP.
- Les adresses distantes privées, réservées ou non globales sont rejetées ; une seule extrémité doit correspondre à une adresse locale fournie explicitement.
- Les fragments non initiaux, les longueurs incohérentes, les protocoles non pris en charge et les en-têtes tronqués sont rejetés.
- Aucune charge utile, aucun contenu applicatif et aucun tampon de paquet ne sont conservés dans le résultat.
- Une file SPSC de 64 enregistrements fixes utilise des opérations atomiques sans attente active. Si elle est pleine, le nouvel échantillon de télémétrie est abandonné et le compteur de pertes augmente ; le composant ne touche jamais au paquet source.

Des tests natifs couvrent IPv4 UDP sortant, IPv4 TCP entrant, IPv6 UDP sortant, les paquets malformés, le rejet d'une destination privée, le premier fragment et la saturation de la file.

Le workflow Android CI compile ce composant en C11 avec avertissements traités comme erreurs et exécute le binaire de test sur l'hôte Linux.

**Limite explicite :** ce prototype parse des buffers de test fournis par le harnais ; il n'est pas encore branché aux points HEV, ne reçoit aucun paquet de l'appareil et n'est pas appelé par JNI. Le test CI prouve la compilation et le comportement des cas unitaires, pas la sûreté temps réel du chemin de transfert. Le VPN reste inactif et aucune redirection n'est introduite.


## Revue de propriété des pbuf et cardinalité des producteurs — 14 novembre 2026

Une inspection supplémentaire du code HEV épinglé au commit `75dbc1e9ace6c70d3d1b94df7c283efe1de7effc` précise les contraintes qui doivent être résolues avant de raccorder la file de métadonnées.

### Chaînes pbuf : aucune hypothèse de contiguïté

- `lwip_io_task_entry()` reçoit un `pbuf` de `hev_tunnel_read()`, puis le remet à `netif->input()`. Le compactage `tunnel_compact_pbuf()` ne copie que les buffers déjà contigus (`buf->next == NULL`) et de taille limitée ; les chaînes restent donc possibles.
- `netif_output_handler()` transmet le `pbuf` directement à `hev_tunnel_write()`. Il ne garantit pas que `p->payload` contienne à lui seul tous les octets du paquet.
- Le chemin TCP accumule explicitement des chaînes avec `pbuf_cat()`.
- Le chemin UDP peut recevoir des datagrammes réassemblés en chaîne ; il les clone alors pour obtenir un contenu contigu.
- Le chemin UDP GSO construit également des chaînes de pbuf de référence. Un pbuf transmis à la sortie peut donc représenter un agrégat, et non un unique datagramme ordinaire.

**Décision :** ne jamais analyser directement `p->payload` en supposant que `p->len == p->tot_len`. Ne jamais modifier, libérer, conserver ou retarder le pbuf depuis le tap.

### Extraction limitée aux en-têtes

Une copie complète de chaque paquet dans un tampon fixe de 64 Kio serait inutile, coûteuse et particulièrement inadaptée au chemin critique. La future interface du parseur natif doit distinguer :

- la longueur totale déclarée par l'en-tête IP ;
- la longueur réellement disponible dans le pbuf ;
- le nombre d'octets d'en-tête copiés dans un petit tampon borné.

L'adaptateur devra utiliser une primitive de copie pbuf consciente des chaînes (par exemple `pbuf_copy_partial()`) pour récupérer uniquement les octets nécessaires à l'analyse des en-têtes IP, des extensions IPv6 et de TCP/UDP. Il vérifiera séparément que la longueur IP déclarée ne dépasse pas `p->tot_len`, que les en-têtes nécessaires sont entièrement disponibles et que la limite de copie n'est jamais dépassée. Aucun octet de charge utile applicative ne sera copié.

Le parseur actuel exige que la longueur IP déclarée soit inférieure ou égale à la longueur du buffer fourni. Il ne faut donc pas lui transmettre un préfixe tronqué en prétendant qu'il s'agit du paquet complet. Une API native distincte ou un refactoring explicite devra préserver cette validation tout en acceptant une vue limitée aux en-têtes. Les vecteurs partagés Kotlin/C devront couvrir cette nouvelle distinction.

Les paquets GSO/agrégés seront ignorés par la télémétrie tant qu'une stratégie documentée ne permet pas d'identifier et de séparer correctement chaque segment. Le transfert original reste inchangé.

### Cardinalité des producteurs : la file SPSC n'est pas encore raccordable

Le prototype actuel est une file **SPSC** (un producteur, un consommateur). Les deux points d'instrumentation envisagés ne constituent pas automatiquement un producteur unique :

- le point d'entrée TUN est exécuté dans `lwip_io_task_entry()` ;
- le point de sortie TUN est exécuté dans `netif_output_handler()`, appelé depuis le traitement lwIP et potentiellement depuis différents contextes de tâches HEV.

Le code inspecté montre des appels lwIP protégés par le mutex HEV à plusieurs endroits, mais cela ne suffit pas à lui seul à démontrer le modèle exact d'exécution natif, l'unicité du thread OS ni l'absence de producteurs concurrents au niveau du callback. Une file SPSC unique ne doit donc pas être utilisée pour les deux hooks sur la seule base de cette inspection.

Avant raccordement, il faudra prouver la cardinalité réelle des producteurs à partir de l'ordonnanceur HEV et des chemins d'appel. Les options acceptables sont :
1. une file SPSC distincte par producteur réellement unique, avec un consommateur qui les draine ;
2. une file MPSC bornée si plusieurs producteurs peuvent réellement s'exécuter concurremment, avec garanties atomiques vérifiées sur chaque ABI.

Le contrôle atomique ABI qui vient de passer en CI confirme la compilation et la liaison du probe 32 bits ; il ne prouve ni l'unicité des producteurs, ni le comportement concurrent à l'exécution sur appareil. Le contrôle runtime `atomic_is_lock_free()` restera obligatoire avant toute utilisation de la file.

### Garde-fous inchangés

- Les hooks ne doivent ni bloquer, ni allouer de mémoire, ni appeler JNI de façon synchrone.
- Si la file est pleine, seul l'échantillon de télémétrie est perdu.
- Le pbuf original est toujours transmis au moteur sans modification.
- Aucun payload, identifiant de compte, contenu de message ou secret n'est enregistré.
- Aucun VPN réel n'est activé avant les tests natifs, l'intégration JNI, les tests de transfert et les contrôles sur appareil.


## Lecteur de segments natif — première primitive validée

Le commit `11cc13b` ajoute au prototype C une vue de lecture en segments, indépendante de lwIP :

- `np_packet_reader_t` décrit une séquence de segments en lecture seule et sa longueur agrégée.
- `np_packet_reader_copy()` copie uniquement une plage explicitement demandée, même si elle traverse plusieurs segments.
- La fonction vérifie les pointeurs, les débordements de taille, la cohérence entre la somme des segments et la longueur déclarée, ainsi que les bornes de chaque lecture.
- Les tests couvrent une lecture traversant plusieurs frontières de segments, une plage limitée aux octets demandés, une lecture hors limites, une longueur agrégée incohérente et un segment invalide.

La CI `43622324319` a validé les tests C, les tests sous ASan/UBSan, la compilation pour les quatre ABI Android, les tests Kotlin, Android Lint et les builds Debug/Release.

**Limite :** cette primitive ne dépend pas de lwIP et n'est pas encore un adaptateur `pbuf`. Elle ne parse pas encore directement un paquet segmenté et n'est pas appelée depuis HEV. Le prochain travail doit fournir une interface de lecture incrémentale pour le parseur, capable de valider la longueur IP totale sans copier la charge utile, puis un adaptateur lwIP utilisant une copie partielle bornée. Le raccordement aux hooks HEV reste bloqué jusqu'à preuve de la cardinalité des producteurs et tests de non-régression du transfert.


### Tests supplémentaires du lecteur segmenté — 14 novembre 2026

Le lecteur générique `np_packet_reader_copy()` est maintenant testé avec toutes les paires de frontières possibles sur un paquet IPv4/TCP de test, y compris les segments vides. Les tests vérifient aussi les lectures qui traversent les frontières, les plages hors limites, les longueurs agrégées incohérentes et les tableaux de segments absents.

Cette primitive est un composant de base uniquement : elle n'est pas encore branchée sur les `pbuf` HEV et ne produit pas encore de métadonnées directement depuis une chaîne. Le parseur existant continue d'exiger un paquet complet. L'étape suivante reste la conception d'un parseur à longueur totale déclarée et préfixe d'en-têtes capturé, afin de ne jamais copier la charge utile.

## Parseur natif à lecture segmentée et copie limitée aux en-têtes

Le prototype `np_parse_packet_metadata_reader()` accepte maintenant une vue `np_packet_reader_t` constituée de segments en lecture seule. Il vérifie la longueur IP déclarée contre la longueur agrégée réelle du lecteur, puis copie par étapes uniquement les octets nécessaires aux en-têtes :

- IPv4 : en-tête de base, options éventuelles, puis en-tête TCP ou UDP ;
- IPv6 : en-tête de base, extensions prises en charge (maximum huit), puis en-tête TCP ou UDP ;
- TCP : en-tête minimal puis options jusqu'à la longueur indiquée par le champ Data Offset ;
- UDP : en-tête fixe de huit octets.

Le tampon de travail est fixe et borné à 2048 octets. Les chaînes d'extensions qui dépassent cette limite sont ignorées pour la télémétrie. La charge utile applicative n'est pas copiée dans ce tampon. Les paquets malformés, tronqués, non pris en charge, les fragments non initiaux et les destinations non publiques continuent d'échouer sans produire de métadonnées.

Des tests natifs couvrent IPv4 TCP/UDP segmenté, IPv6 avec Destination Options, des frontières de segments au milieu des en-têtes et une longueur IP déclarée supérieure à la longueur réellement disponible.

**Limite importante :** ce parseur consomme encore une abstraction `np_packet_reader_t`. Aucun adaptateur `pbuf_copy_partial()` HEV n'est implémenté, aucun hook HEV ne l'appelle et aucune télémétrie réelle ne remonte depuis le VPN Android. Le tampon de 2048 octets est une limite de télémétrie volontaire, pas une limite imposée au transfert réseau : un paquet dont les en-têtes dépassent cette borne est simplement ignoré par l'analyse. Le VPN reste inactif.

## Réévaluation de la cardinalité des producteurs HEV — commit amont épinglé

La revue du sous-module hev-task-system référencé par HEV a permis de préciser le modèle d'exécution :

- hev_task_system_init() crée un contexte dans le stockage TLS du thread appelant.
- hev_task_system_run() entre dans hev_task_system_schedule() et sélectionne/exécute les tâches coopératives dans ce contexte. Le scheduler bascule entre tâches ; il ne crée pas un thread OS par tâche.
- Dans HEV, les tâches d'entrée TUN, timer et sessions sont créées avec hev_task_new() puis exécutées par ce même scheduler.
- Le callback netif_output_handler() est appelé synchroniquement par lwIP. Les chemins visibles qui peuvent produire cette sortie sont sérialisés par le HevTaskMutex partagé : netif->input() dans lwip_io_task_entry(), tcp_write()/tcp_output() dans la tâche TCP, udp_sendfrom() dans la tâche UDP et tcp_tmr() dans la tâche timer.
- Le hook d'entrée TUN et le hook de sortie TUN s'exécutent donc séquentiellement dans le même contexte de scheduler pour cette instance HEV, et non comme deux producteurs OS concurrents.

### Décision de conception actualisée

Pour le commit HEV 75dbc1e9ace6c70d3d1b94df7c283efe1de7effc et le sous-module hev-task-system ded55c412b3951bc7decb5cb3b9135701e762998, une file SPSC unique peut être envisagée pour les deux points d'observation, car ils sont appelés séquentiellement par le même thread de scheduler.

Cette conclusion est strictement liée à cette version et à ce cycle de vie :
- initialisation, exécution et appels lwIP doivent rester attachés au même thread OS ;
- aucun callback externe ne doit appeler directement lwIP depuis un autre thread ;
- toute mise à jour HEV ou du sous-module impose de réexaminer le modèle d'exécution et les chemins d'appel ;
- le contrôle runtime atomic_is_lock_free() reste obligatoire sur l'appareil avant utilisation.

Cette revue lève le doute de concurrence entre les deux hooks pour la version épinglée ; elle n'implémente toujours aucun hook. Le parseur segmenté, la file et le lecteur de segments restent des composants de test isolés. Aucun pbuf n'est encore observé en production, aucun callback JNI n'est ajouté et aucun VPN n'est activé.
## Adaptateur de lecture pbuf non propriétaire — 15 novembre 2026

Un adaptateur C isolé a été ajouté sous native/metadata/pbuf_reader_adapter.h et .c.

- Il accepte un pointeur pbuf opaque, sa longueur totale u16 et une fonction de copie compatible avec la signature et le comportement de pbuf_copy_partial().
- Il expose ce stockage au parseur d'en-têtes par l'interface callback déjà testée ; les lectures sont limitées à la longueur totale et aux bornes uint16.
- Une copie partielle est considérée comme un échec : le parseur ne doit pas utiliser un en-tête incomplet.
- Le contexte et le pbuf restent la propriété de l'appelant et doivent vivre jusqu'au retour du parseur. L'adaptateur ne conserve pas le pbuf, ne le modifie pas et ne change pas ses références.
- Aucun en-tête lwIP n'est inclus dans cette couche portable. Le futur hook HEV devra fournir un petit wrapper typé autour de pbuf_copy_partial(const struct pbuf *, ...), puis appeler le parseur de façon synchrone pendant que le pbuf est valide.

Les tests utilisent une chaîne pbuf simulée avec plusieurs segments et vérifient : extraction UDP correcte, lecture arrêtée à la fin des en-têtes (28 octets dans le vecteur IPv4/UDP), refus d'une copie partielle et refus d'une initialisation invalide.

Le workflow CI compile maintenant ce nouvel adaptateur dans les tests natifs, les tests avec sanitizers et les quatre cibles Android.

**Limite importante :** cet adaptateur n'est pas encore appelé depuis hev-socks5-tunnel.c. Aucun patch HEV, aucune compilation AAR, aucun callback JNI et aucun hook d'entrée/sortie TUN ne sont inclus dans cette étape. Il s'agit du pont de lecture portable et testable qui prépare ce raccordement. Aucun VPN n'est activé.
## File de télémétrie et façade d'observation — 15 novembre 2026

Un composant isolé native/metadata/metadata_tap.h/.c orchestre maintenant les briques validées :
- initialisation de la file SPSC de taille fixe et vérification runtime de ses atomiques lock-free sur l'ABI courante ;
- observation synchrone par parseur reader, sans allocation dynamique ;
- mise en file d'un enregistrement de métadonnées fixe uniquement ;
- lecture par un seul consommateur via np_metadata_tap_poll(), en dehors du chemin de transfert ;
- exposition du compteur d'échantillons perdus lorsque la file est pleine.

Le test natif assemble le faux pbuf segmenté, l'adaptateur de copie et la façade de télémétrie. Il vérifie la métadonnée UDP attendue, l'absence de modification du paquet source, la lecture des seuls en-têtes et le rejet des entrées malformées.

### Contrat nécessaire au futur raccordement HEV

Les hooks devront appeler np_metadata_tap_observe() pendant que le pbuf est encore valide, en lui passant les adresses locales réellement configurées sur le TUN. Attention : le netif lwIP interne de HEV est initialisé avec les adresses loopback 127.0.0.1 et ::1 ; elles ne représentent pas l'adresse locale du TUN Android. Les adresses doivent être dérivées des valeurs de configuration retournées par hev_config_get_tunnel_ipv4_address() et hev_config_get_tunnel_ipv6_address(), et analysées/validées explicitement. Le fichier conf/main.yml amont montre des exemples d'adresses TUN dédiées, distinctes des adresses loopback du netif interne.

Le hook d'entrée TUN représente le sens sortant depuis l'application ; le hook de sortie TUN représente le sens entrant. Le parseur déduit toutefois le sens avec les adresses locales fournies, il ne doit pas se fier à l'adresse du netif lwIP interne.

Le producteur unique reste le thread du scheduler HEV épinglé ; le futur consommateur JNI devra être sérialisé sur un seul thread. Aucun appel JNI synchrone ne doit être ajouté au transfert. La file n'est pas MPMC et ne doit pas être appelée depuis un producteur externe concurrent.

**Limite actuelle :** la façade et les tests sont dans NOVA PING, mais les hooks ne sont pas encore ajoutés à HEV. Le moteur amont n'est ni vendored ni compilé dans cette étape ; aucun AAR n'est produit, aucun service VPN n'est démarré et aucun trafic n'est redirigé.

## Mise à jour — raccordement HEV et barrière de disponibilité — 28 novembre 2026

L'état du dépôt a évolué depuis les premières sections historiques de cet ADR. La branche
`fix/hev-start-stop-handshake-v1` contient désormais le raccordement des hooks natifs
dans le patch HEV épinglé, l'export JNI des enregistrements et leur lecture Kotlin.

### État actuel vérifié dans le code

- `nova_metadata_init()` initialise les taps natifs et lit les adresses TUN depuis la configuration HEV.
- Les hooks d'entrée et de sortie TUN observent les en-têtes via l'adaptateur pbuf non propriétaire.
- Le consommateur JNI est sérialisé par un exécuteur Kotlin à thread unique.
- Le rapport de diagnostic réduit actuellement les métadonnées aux compteurs transport/direction/taille ; les IP et ports ne sont pas conservés.
- Ces métadonnées ne prouvent pas à elles seules qu'une destination est un serveur de match ni ne fournissent le RTT du jeu.

### Correction de cycle de vie

Le polling Kotlin ne doit pas commencer avant l'initialisation native des taps. Le signal
`hev_socks5_tunnel_is_running()` est publié par le thread du scheduler après l'initialisation
HEV et `nova_metadata_init()`. Il sert désormais de barrière de disponibilité :

1. Kotlin prépare et lance le thread HEV.
2. Le moniteur attend la confirmation native de disponibilité.
3. Après confirmation et vérification que le thread est toujours vivant et que l'arrêt n'est pas demandé, Kotlin démarre le consommateur de métadonnées.
4. L'arrêt du tunnel interrompt le consommateur existant comme auparavant.

Cette séquence évite que le consommateur JNI lise `np_metadata_tap_t.ready` pendant que le
thread HEV est encore en train de l'initialiser. Elle ne modifie ni le transfert des paquets,
ni le format des enregistrements, ni les règles de confidentialité.

### Contrôles CI renforcés

Le workflow valide maintenant les symboles JNI `nativePrepareStart` et `nativeCancelStart`
pour chacune des quatre ABI, en plus des symboles déjà vérifiés. Les branches de base
utilisées par les PR HEV empilées sont également couvertes par le déclencheur
`pull_request`, afin que ces PR ne dépendent pas uniquement d'un push sur leur branche.

### Limites restantes

- La télémétrie reste agrégée et ne relie pas encore les observations à un identifiant de session de jeu.
- Aucun classement automatique des endpoints observés comme serveurs de match n'est ajouté.
- Aucun ping de serveur CODM ni taux de perte de paquets de la partie n'est calculé.
- Aucun changement de route ni relais distant n'est activé par cette évolution.

Les sections antérieures décrivent l'état des étapes à leur date respective ; cette mise à jour
décrit l'état du raccordement au commit de cette branche.
