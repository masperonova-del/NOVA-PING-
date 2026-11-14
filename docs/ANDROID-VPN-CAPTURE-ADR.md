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

Until every gate passes, no VPN declaration, dependency, app UI toggle or “live endpoint” claim is enabled.

## Current outcome

The pure Kotlin packet parser and its unit tests are complete. A loopback-only SOCKS5 bridge now implements authenticated TCP CONNECT and UDP ASSOCIATE. It uses per-instance ephemeral RFC 1929 credentials, requires protection callbacks for upstream TCP and UDP sockets, pins UDP traffic to the first datagram endpoint from the authenticated TCP peer IP, rejects fragmented SOCKS5 UDP frames, limits concurrent UDP forwarding and shuts the association down with its TCP control connection. Domain destinations are deliberately rejected for both TCP CONNECT and UDP ASSOCIATE: resolving them through the Android system resolver could leak DNS outside the protected path. Numeric IPv4/IPv6 destinations remain supported. A strict DNS wire codec now builds A/AAAA questions and validates transaction IDs, flags, the echoed question, resource-record framing, address lengths and TTLs. A positive in-memory cache enforces answer TTLs with an upper bound, avoids caching empty/zero-TTL answers and returns defensive copies. These are protocol and cache primitives only: no DNS network transport exists yet, and domain forwarding remains disabled. The next step is a controlled UDP/TCP transport whose sockets are protected before use, with no system-resolver fallback. Local TCP and UDP echo tests exercise the relay paths. Android live capture, TUN forwarding, the metadata tap, controlled DNS handling and UI integration remain unimplemented. The UDP association now also honors a nonzero client-advertised UDP port and rejects a non-wildcard advertised address that differs from the authenticated TCP peer. A regression test verifies that datagrams from an unadvertised source port are not forwarded. The bridge is not wired into a VPN and must not be treated as a traffic optimizer. CI validates source/tests/APK compilation only; it does not validate live network forwarding or CODM match-server discovery.
