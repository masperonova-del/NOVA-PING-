# NOVA BOOST — Implementation status

## Current implementation

`NovaBoostEngine` is an advisory layer over the existing `DecisionEngine`.
It consumes a `NetworkContext`, route aggregates, and the selected `GameProfile`.
It returns a `BoostAssessment` containing:

- the selected game profile;
- a state (`OBSERVE` or `RECOMMEND` in this first implementation);
- the existing evidence-based recommendations;
- a human-readable summary;
- an explicit evidence count showing how many distinct HTTPS control targets meet the minimum quality gate;
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

- `OBSERVE`: fewer than two distinct control targets meet the minimum evidence gate, so route-derived findings remain exploratory. The UI still shows endpoint-specific findings but does not promote the overall BOOST state.
- `RECOMMEND`: at least two distinct HTTPS control targets each contain five planned samples and at least three complete DNS/TCP/TLS/HTTP observations; alternatively, Android reports that Internet access is not validated, which supports a direct connectivity recommendation.
- `READY`, `ACTIVE`, `FAILED`, and `STOPPED` are reserved for a future, separately validated routing lifecycle. They must not be used to imply that a tunnel currently exists.

## Validation

Unit tests cover empty evidence, rejection of one-target or weak-target evidence, the two-target/three-complete-samples gate, duplicate-host protection, the actionable unvalidated-connectivity case, explicit CODM evidence limitations, and the absence of routing claims.

The implementation is not considered validated until the branch CI completes successfully and the app is exercised on a physical Android device.


## Evidence gate added in 1.18.0-dev

NOVA BOOST no longer enters `RECOMMEND` merely because one aggregate contains a sample. It requires at least two distinct HTTPS control targets; each target must have the planned five route samples and at least three complete DNS/TCP/TLS/HTTP observations. Duplicate aggregates for the same hostname do not increase the evidence count. The separate unvalidated-Internet finding remains actionable even without route samples. This gate improves the reliability of the diagnostic state; it does not create a VPN, change routing, or validate game-server latency.
