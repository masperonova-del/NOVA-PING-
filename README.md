# NOVA PING

**Intelligent network diagnostics for competitive gaming.**

NOVA PING is a mobile-first project designed to help players understand, measure and improve the stability of their internet connection during online games.

The project consolidates the useful VELOCE direction into one product: measurement first, evidence-based recommendations, and real routing improvements only when technically demonstrated.

## Product principles

- Measure real network behavior; never invent or simulate a better ping.
- Separate internet latency from actual game-server latency.
- Report median latency, P95 latency, jitter and packet loss with the measurement method and sample count.
- Compare destinations and routes only when the test is technically valid.
- Give recommendations with a confidence level and an explanation.
- Request clear permission before any VPN-based routing or traffic handling.
- Do not promise that changing DNS or probing a cloud server will reduce in-game ping.

## Planned capabilities

1. **NOVA CORE** — coordinates tests and turns measurements into decisions.
2. **PING ENGINE** — latency, jitter, stability and loss measurements.
3. **ROUTE ENGINE** — destination and route comparison, with explicit limits.
4. **GAME INTELLIGENCE** — game profiles and region guidance.
5. **DIAGNOSTIC ENGINE** — Wi-Fi/mobile network and device-side checks.
6. **DECISION ENGINE** — prioritized recommendations based on evidence.
7. **NOVA BOOST (later phase)** — optional routing through controlled relays, subject to feasibility, consent and measured benefit.

## Important technical limit

A successful HTTP request to a cloud endpoint is not proof of the ping to a Call of Duty Mobile, PUBG Mobile or Free Fire game server. ICMP, TCP, UDP and application-level measurements are different and must not be presented as interchangeable.

A VPN or relay can sometimes improve a route, but can also make it worse. NOVA PING will only claim an improvement after before/after measurements under comparable conditions.

## Project status

This repository currently contains the product and engineering foundation. The Android application, measurement engine and automated tests are not yet implemented.

See:
- [Architecture](docs/ARCHITECTURE.md)
- [Measurement standards](docs/NETWORK-MEASUREMENT.md)
- [Roadmap](docs/ROADMAP.md)

## Initial target

Android first. Competitive mobile gaming is the primary use case; PC support may follow.

## License

Not selected yet. Until a license is added, all rights are reserved by the repository owner.
