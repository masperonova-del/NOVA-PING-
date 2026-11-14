# NOVA BOOST — Implementation status

## Current implementation

`NovaBoostEngine` is an advisory layer over the existing `DecisionEngine`.
It consumes a `NetworkContext`, route aggregates, and the selected `GameProfile`.
It returns a `BoostAssessment` containing:

- the selected game profile;
- a state (`OBSERVE` or `RECOMMEND`);
- the existing evidence-based recommendations;
- a human-readable summary;
- explicit measurement and routing limitations.

## Evidence gate (1.17.1-dev)

NOVA BOOST enters `RECOMMEND` only when at least two distinct HTTPS control destinations each have exactly five attempted Route Engine samples and at least four complete HTTPS connections. Partial batches, repeated failures from only one target, and a single qualified target remain in `OBSERVE`.

This gate controls the status label; it does not discard the diagnostic details or hide individual recommendations. It prevents a small or failed sample from being presented as enough evidence for the recommendation state.

## Safety and evidence boundaries

- Public HTTPS control endpoints are not game match servers.
- A game profile does not establish that a game's server endpoints are known.
- No CODM match-server ping is claimed unless independently verified measurement evidence exists.
- No traffic is redirected and no VPN session is started.
- `routingAvailable` and `vpnConsentRequired` remain false because no VPN service is implemented.
- The presence of a WireGuard library is not proof of an operational tunnel.
- Recommendations are advisory; the engine does not modify Android network settings.

## State model

- `OBSERVE`: no measurements exist, or the evidence gate is not satisfied.
- `RECOMMEND`: at least two distinct destinations meet the five-attempt / four-complete-connection evidence threshold, so the Decision Engine's findings can be presented as recommendations.
- `READY`, `ACTIVE`, `FAILED`, and `STOPPED` are reserved for a future, separately validated routing lifecycle. They must not be used to imply that a tunnel currently exists.

## Validation

Unit tests cover empty evidence, partial batches containing repeated failures, a single qualified destination, two qualified destinations, explicit CODM evidence limitations, and the absence of routing claims.

The implementation is not considered validated until the branch CI completes successfully and the app is exercised on a physical Android device.
