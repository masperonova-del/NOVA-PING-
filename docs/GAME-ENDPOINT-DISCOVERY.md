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

## Required next implementation stage

Before enabling live capture, a separate engineering change must provide:

- an explicit Android system VPN-consent flow;
- strict allow-listing to the selected game package;
- a correctly implemented and tested packet-forwarding path, or a documented alternative that does not intercept traffic;
- local-only metadata extraction (IP version, remote address, port, transport, timestamp, session ID);
- immediate stop and cleanup controls;
- no packet payload persistence;
- no endpoint claim stronger than "observed during the selected game's session".

Only after that capture layer is stable can the observation registry be connected to the UI and used to build a candidate list. An endpoint still needs independent evidence of its match-server role before it can be labelled as a verified match server or used as a CODM ping target.

## Non-goals

- No port scanning or probing of third-party address ranges.
- No decryption or inspection of game payloads.
- No claim that a public control endpoint represents a CODM match server.
- No automatic VPN routing optimization or claim of lower in-game latency.
