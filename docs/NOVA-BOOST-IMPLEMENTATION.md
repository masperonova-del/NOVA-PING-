# NOVA BOOST — Implementation status

## Current implementation

`NovaBoostEngine` is an advisory layer over the existing `DecisionEngine`.
It consumes a `NetworkContext`, route aggregates, and the selected `GameProfile`.
It returns a `BoostAssessment` containing:

- the selected game profile;
- a state (`OBSERVE` or `RECOMMEND` in this first implementation);
- the existing evidence-based recommendations;
- a human-readable summary;
- explicit measurement and routing limitations.

## Safety and evidence boundaries

- Public HTTPS control endpoints are not game match servers.
- A game profile does not establish that a game's server endpoints are known.
- No CODM match-server ping is claimed unless independently verified measurement evidence exists.
- No traffic is redirected and no VPN session is started.
- `routingAvailable` and `vpnConsentRequired` remain false because no VPN service is implemented.
- The presence of a WireGuard library is not proof of an operational tunnel.
- Recommendations are advisory; the engine does not modify Android network settings.

## State model

- `OBSERVE`: no route measurement evidence is available yet.
- `RECOMMEND`: measurement evidence exists and the engine can present the Decision Engine's findings.
- `READY`, `ACTIVE`, `FAILED`, and `STOPPED` are reserved for a future, separately validated routing lifecycle. They must not be used to imply that a tunnel currently exists.

## Validation

Unit tests cover observation without measurements, propagation of existing recommendations, explicit CODM evidence limitations, and the absence of routing claims.

The implementation is not considered validated until the branch CI completes successfully and the app is exercised on a physical Android device.
