# ADR 0001 — Android tunnel engine for NOVA BOOST

- Status: accepted for the first proof of concept; subject to build and device validation
- Date: 2026-11-07
- Branch: `nova-boost-foundation`

## Context

NOVA PING 1.5.2 is a measurement application. Its Android manifest does not declare a VPN service, and it does not currently route application traffic through a remote gateway.

NOVA BOOST needs an Android tunnel implementation that can carry IP traffic, including UDP, through a remote relay. Android's `VpnService` provides the TUN interface and consent lifecycle, but it does not provide the encrypted tunnel protocol or the relay infrastructure.

## Decision

Use the maintained WireGuard Android tunnel library as the preferred tunnel engine for the first proof of concept:

- Maven coordinate: `com.wireguard.android:tunnel`
- Candidate artifact reviewed: `1.0.20260102`
- The WireGuard project documents this library as its Android embedding interface.
- The artifact metadata identifies Apache License 2.0. The exact resolved artifact and transitive dependency licenses must still be checked in the build before distribution.

Do not implement custom cryptography or a proprietary tunnel protocol.

## Android integration constraints

1. Implement a dedicated Android `VpnService`, protected by `android.permission.BIND_VPN_SERVICE` and advertised with the `android.net.VpnService` intent action.
2. Always call `VpnService.prepare()` and let Android present the system consent screen.
3. Use an explicit per-app allowlist for the selected game. Do not route all device applications by default.
4. Keep the tunnel's own network socket outside the VPN using `VpnService.protect()` to prevent recursive routing.
5. Handle service revocation, network changes, process shutdown, and explicit disconnect without leaving the device in a broken connectivity state.
6. The first test must use a controlled test application or synthetic traffic. A real game must not be selected until the relay path and measurement method are verified.

## Privacy and distribution requirements

- Encrypt traffic between the Android device and the VPN gateway.
- Explain clearly what traffic is routed, where it goes, and what data is retained before requesting consent.
- Do not inspect game payloads, collect game credentials, or log user traffic contents.
- Provide an obvious disconnect control.
- Review Google Play's current VpnService eligibility, disclosure, and listing requirements before public distribution.

## Proof-of-concept acceptance criteria

The first Android VPN milestone is accepted only when:

- The app builds with the selected WireGuard tunnel artifact and license checks pass.
- Android's system VPN consent is requested before starting the service.
- Only the selected test app is routed through the tunnel.
- The tunnel endpoint is excluded from the VPN interface itself.
- Connect, disconnect, revoke, and network-change paths are tested.
- Stopping or revoking the service restores ordinary networking.
- The UI clearly identifies this as a tunnel test, not a game optimizer.

No APK may be described as an optimizer until a real relay is deployed and repeated direct-versus-relay tests show an improvement to the same verified destination.

## Consequences

- WireGuard reduces the cryptographic and tunnel-protocol engineering burden.
- NOVA still needs its own relay/gateway, secure provisioning, route comparison, health monitoring, and evidence-based game testing.
- A tunnel can make latency worse. The client must be able to reject a relay and return to direct networking.
- The first milestone is a functional tunnel proof of concept, not a promised ping reduction.

## References

- Android VPN guide: https://developer.android.com/develop/connectivity/vpn
- Android VpnService reference: https://developer.android.com/reference/android/net/VpnService
- WireGuard embedding guide: https://www.wireguard.com/embedding/
- WireGuard Android project: https://git.zx2c4.com/wireguard-android
- Maven Central artifact: https://central.sonatype.com/artifact/com.wireguard.android/tunnel
- Google Play VpnService policy: https://support.google.com/googleplay/android-developer/answer/12564964
