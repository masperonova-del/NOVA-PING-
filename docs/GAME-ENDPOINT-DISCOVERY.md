# Game endpoint discovery — technical boundary and implementation plan

## What is confirmed today

Activision's public support page lists the following Call of Duty: Mobile ports for Android / iOS:

- TCP 80 and 443
- TCP 65010 (Lobby)
- TCP 65050 (Chat)
- UDP 7500–8000

Source: https://support.activision.com/fr/articles/ports-used-for-call-of-duty-games

This is public connectivity metadata. It does **not** publish match-server IP addresses, hostnames, match regions, or a way to distinguish a match server from every other service using those ports. A port match alone must never be displayed as a confirmed match server.

## What the Android app can and cannot observe

NOVA PING's current Route Engine opens its own HTTPS connections to public control endpoints. It does not inspect Call of Duty: Mobile's sockets. Android app sandboxing also means NOVA PING cannot simply read another app's remote socket list through the ordinary connectivity APIs.

Android provides `VpnService` as a possible user-consented network-observation path. Its builder can scope VPN traffic to an explicitly allowed application with `addAllowedApplication`. However, `establish()` creates a TUN interface that delivers IP packets to the app; a working VPN implementation must handle that packet path correctly. A metadata-only observer must not install a tunnel that silently blackholes or disrupts the selected game's traffic.

Android reference: https://developer.android.com/reference/android/net/VpnService.Builder

## Observation model added in this change

`GameEndpointObservationRegistry` accepts only observations that:

1. carry explicit user-consent state;
2. match the exact package selected by the caller;
3. contain a numeric public unicast IP address (no DNS lookup);
4. contain a valid TCP/UDP port and timestamp;
5. are grouped by game, package, address, port and transport.

It aggregates observation counts and distinct session counts. Repeated observations across sessions raise a candidate's recurrence label, but never promote it to a verified match server.

The model stores only endpoint metadata. It has no packet-payload, credential, message-content, or remote-upload fields.


## Packet metadata parser now added

The branch contains `GamePacketMetadataParser`, a pure Kotlin decoder for IP packets, and `HevMetadataNative`, the single-consumer JNI adapter for the bounded native HEV metadata queue. The HEV readiness monitor starts polling only after native initialization. The VPN allow-lists the exact selected game package, so the TUN metadata stream is scoped to that package. `NovaVpnService` now feeds decoded HEV endpoint headers into `GameEndpointObservationSession`, which applies `GameEndpointObservationRegistry` validation and aggregation. The session is in-memory, bounded to 512 observations, and cleared when the VPN service is destroyed. It stores no payloads and does not write endpoint addresses into diagnostics or persistent preferences. The Android system VPN-consent flow is the authorization gate; observations are accepted only when that authorization is present. It extracts only:

- IP version and remote numeric address;
- remote TCP/UDP port and transport;
- inbound/outbound direction relative to explicitly supplied local TUN addresses;
- packet length and whether the first fragment indicates fragmentation.

It handles IPv4 headers and IPv6 extension headers (including TCP/UDP after supported extension headers), rejects non-initial fragments, malformed lengths, unsupported transports, ambiguous local/remote direction and non-global address ranges. The parser does not retain packet bytes, expose payload data, open sockets, establish a VPN, forward traffic or observe the phone by itself.

The existing WireGuard dependency is a WireGuard tunnel backend, not a generic passive packet-capture API. The current `WireGuardEngine` only detects the backend version. A future capture adapter still needs a correctly functioning full packet path before this parser can receive live game packets.

WireGuard Android embedding reference: https://github.com/WireGuard/wireguard-android

## Android capture architecture decision

The forwarding-engine evaluation and activation gates are documented in [ADR-001](ANDROID-VPN-CAPTURE-ADR.md). The current VPN path uses the integrated HEV tun2socks engine and a bounded native metadata tap inside the packet path. Kotlin consumes only the tap queue through the single JNI consumer after HEV reports native readiness; it does not read from the TUN descriptor independently. This avoids a second reader stealing packets from the forwarding engine. The existing tunnel/bridge path still requires real-device regression testing with each selected game and network type.

## Required next implementation stage

The next stage is validation and productization of the connected observer:

- expose the candidate list in the UI with a clear "observed endpoint — server role unconfirmed" label;
- add a user-facing explanation and a dedicated opt-in for endpoint history before retaining observations beyond the active VPN session;
- keep endpoint addresses in memory only until that explicit history choice is implemented;
- test the HEV tap and selected-package isolation on a real Android device with CODM in lobby and in a live match;
- map additional game packages explicitly instead of grouping them under "other";
- do not calculate match RTT or packet loss from one-way packet observations; those metrics need a separate, validated measurement method;
- do not use candidate endpoints for routing or probing until their role and measurement method are independently validated.

The HEV-to-registry connection is now implemented, but the candidate list is not yet exposed in the UI and is not used as a ping target. An endpoint still needs independent evidence of its match-server role before it can be labelled as a verified match server or used as a CODM ping target. The current session uses the CODM package mapping when the selected package is `com.activision.callofduty.shooter`; other selected packages are conservatively grouped as `other` until their package-to-game mapping is explicitly defined.

## Non-goals

- No port scanning or probing of third-party address ranges.
- No decryption or inspection of game payloads.
- No claim that a public control endpoint represents a CODM match server.
- No automatic VPN routing optimization or claim of lower in-game latency.
