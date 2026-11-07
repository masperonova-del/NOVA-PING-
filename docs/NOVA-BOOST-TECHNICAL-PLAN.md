# NOVA PING — NOVA BOOST technical plan

## Product commitment

NOVA PING is intended to become a mobile gaming network optimizer, not remain a diagnostic-only app. Diagnostics are the measurement and verification layer used by the optimizer; they are not the end product.

The product goal is to compete with gaming routing products by testing whether an alternate path can improve a player's real connection to a game service, then applying that path only when the benefit is demonstrated.

No release may claim that it reduces in-game ping unless repeatable measurements support that claim.

## Repository baseline audited

Current default-branch baseline: version 1.5.2, versionCode 19.

Implemented:
- Android connectivity context and foreground Wi-Fi stability monitoring.
- HTTPS control-endpoint measurements.
- DNS, TCP, TLS and HTTP phase timing to public control endpoints.
- Statistics, local history, game profile labels and cautious recommendations.
- Unit tests and GitHub Actions debug APK builds.

Not implemented:
- Android VPN service or TUN interface.
- Encrypted tunnel to a remote gateway.
- Relay/gateway infrastructure.
- Game-app traffic routing through a relay.
- Route selection based on measured end-to-end benefit.
- Verified game-server endpoint metadata.

The current manifest only declares INTERNET and ACCESS_NETWORK_STATE. The current app does not modify device or game traffic routing.

## Target architecture

### 1. Android client
- Keep the existing diagnostic and measurement modules.
- Add an explicitly user-started Android VpnService only after the tunnel design is selected.
- Request Android's VPN consent through the system flow.
- Initially route only the user-selected game app through the tunnel (per-app allowlist); do not silently route all device traffic.
- Show clear connection state, selected game, relay region, data-use notice and a visible disconnect action.
- Handle revocation, network changes, process/service shutdown and tunnel failure safely.
- Keep direct connectivity available as the fallback when the tunnel is stopped or cannot connect.

### 2. Secure tunnel
- Do not invent a cryptographic protocol or write custom encryption.
- Select a maintained, reviewed tunnel implementation after checking Android compatibility, licensing, build integration and operational requirements.
- The tunnel transport must support the UDP traffic commonly used by real-time games, while also handling other IP traffic required by the selected app.
- The gateway connection itself must be kept outside the VPN interface to avoid routing the tunnel into itself.
- Do not inspect or store game payload contents.

### 3. Relay infrastructure
- A relay is required; VpnService alone does not improve routing.
- Each relay needs a reachable public endpoint, secure authentication/key management, capacity monitoring and operational health checks.
- The gateway forwards the selected app's IP traffic toward its actual Internet destinations.
- Start with a small test environment and one region; expand only when measurements justify additional regions.
- Minimize operational logs and never collect game credentials or account data.

### 4. Route intelligence
- Compare direct connectivity with candidate relay paths under comparable conditions.
- Measure client-to-relay quality and gateway health, but do not treat either as proof of game-server latency.
- Prefer measurements to the same verified destination and protocol whenever a valid target is available.
- A relay may be selected only when repeated tests show a meaningful improvement without unacceptable regressions in latency, jitter, loss, stability or throughput.
- Geographic proximity alone must never determine the preferred relay.
- If the game destination cannot be verified or its response cannot be measured reliably, label the result as inconclusive rather than claiming a gaming improvement.

### 5. Verification
Every optimization experiment records:
- direct or tunnel mode;
- selected game profile and app package;
- relay identifier and region;
- network type and test time;
- measurement method, protocol, sample count and raw/summary results;
- median, P95, jitter and valid loss metrics where measurable;
- tunnel overhead, failures and reconnects;
- confidence and limitations.

The release gate requires repeatable A/B results on multiple sessions and networks. A lower client-to-relay ping alone is not sufficient. Where possible, verify with the game's own in-session latency indicator or a verified game endpoint. If that evidence is unavailable, do not claim reduced in-game ping.

## Delivery milestones

### M0 — Feasibility and design (this branch)
- Preserve the 1.5.2 main branch unchanged.
- Document Android VPN constraints, tunnel requirements, privacy and proof criteria.
- Review the maintained tunnel implementation options and their licenses before selecting one.
- Define the smallest safe test setup and its cost before provisioning infrastructure.

### M1 — Android VPN proof of concept
- Add a clearly labeled, opt-in VPN service on a separate development branch.
- Establish and stop the local VPN interface correctly.
- Route only a controlled test app or synthetic test traffic.
- Verify that stopping/revoking the service restores normal networking.
- Do not call this an optimizer yet; no relay means no alternate route.

### M2 — First relay tunnel
- Connect the Android client to a test gateway using the selected maintained tunnel.
- Verify encrypted transport, UDP forwarding, DNS behavior, reconnect handling and direct fallback.
- Test only controlled endpoints first.
- Do not claim game improvement.

### M3 — Route comparison engine
- Compare direct and relay paths to identical, verified targets.
- Add repeatable A/B tests, relay health scoring and safe automatic fallback.
- Reject relays that are slower or unstable.

### M4 — Gaming pilot
- Test a supported game only after its traffic path and test methodology are understood.
- Compare in-game outcomes across repeated sessions and networks.
- Publish a gaming optimization claim only if the results are repeatable.

### M5 — Product release
- Add relay regions only where data supports them.
- Add transparent data usage, privacy controls, support and operational monitoring.
- Complete security, licensing, store-policy and performance reviews.

## Non-negotiable engineering rules

1. Never overwrite or remove the working 1.5.2 baseline to experiment with NOVA BOOST.
2. Work on feature branches and merge only after review and successful CI.
3. Never add a VPN permission/service without a clear user-facing explanation and explicit system consent.
4. Never claim that DNS changes, a faster relay handshake, or a cloud HTTPS test prove lower game ping.
5. Never route traffic through a relay unless the tunnel is secure, the route is measured and the user can disconnect immediately.
6. Keep tests, documentation and user-visible claims aligned with what the code actually does.
7. If a feature is blocked by Android, the game, the operator, infrastructure cost or licensing, state that blocker before proceeding.
