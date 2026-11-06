# NOVA PING — Architecture

## 1. Product architecture

```text
Android application
  ├── NOVA CORE
  │    ├── Test coordinator
  │    ├── Session manager
  │    └── Result validation
  ├── PING ENGINE
  │    ├── Latency probes
  │    ├── Jitter analysis
  │    ├── Loss estimation
  │    └── Stability history
  ├── ROUTE ENGINE
  │    ├── Endpoint comparison
  │    ├── Route evidence
  │    └── Region comparison
  ├── GAME INTELLIGENCE
  │    ├── Game profiles
  │    └── Region metadata
  ├── DIAGNOSTIC ENGINE
  │    ├── Wi-Fi / mobile state
  │    ├── Signal and network changes
  │    └── Device-side checks
  └── DECISION ENGINE
       ├── Recommendation ranking
       ├── Confidence scoring
       └── Explanation of trade-offs

Optional future infrastructure
  ├── Measurement endpoints
  └── NOVA BOOST relay network
       └── Android VpnService (explicit user consent)
```

## 2. Module responsibilities

### NOVA CORE
Coordinates test sessions, records conditions, validates samples and exposes a consistent result model.

### PING ENGINE
Measures reachable test endpoints using a documented protocol. It must preserve raw samples and calculate:
- median round-trip time;
- P95 round-trip time;
- jitter using a documented calculation;
- packet-loss estimate, with sent/received counts;
- test duration and sample count.

### ROUTE ENGINE
The current implementation measures DNS resolution, TCP connection establishment, TLS handshake and elapsed time until the HTTP response status line is read, across three cycles per public HTTPS control endpoint. Each phase is summarized independently from successful samples. DNS uses Android's resolver and TCP tries the returned addresses in sequence. It does not yet discover intermediate router hops. A cloud endpoint is a diagnostic target, not automatically the game server.

### GAME INTELLIGENCE
Maintains structured profiles for supported games and an explicit evidence status for server metadata. The current catalog intentionally marks match-server destinations as unverified. Unknown server locations must remain unknown rather than guessed.

### DIAGNOSTIC ENGINE
Identifies relevant conditions such as network type changes, weak or unstable connectivity, and local device constraints. It should not claim access to private game internals.

### DECISION ENGINE
Generates explainable recommendations from the active network context and repeated DNS/TCP/TLS/HTTP phase summaries. Thresholds require repeated samples and remain indicative; confidence is deliberately limited. Calibration across real devices and operator networks remains future work.

## 3. Future NOVA BOOST

A real traffic-routing feature requires infrastructure capable of forwarding traffic, plus an Android VPN implementation where appropriate. It must:
- explain what traffic is routed;
- request Android VPN consent;
- provide a visible stop/disconnect control;
- avoid collecting payload contents;
- use secure transport and minimal operational logs;
- compare results before and after activation;
- automatically avoid claiming a benefit when measurements do not support it.

A DNS change alone is not a route optimizer. A Cloudflare Worker or ordinary HTTP endpoint alone is not a general-purpose gaming relay.

## 4. Data model principles

Every test result should include:
- timestamp;
- network type;
- destination identifier;
- protocol and test method;
- sent and received sample counts;
- raw latency samples or a privacy-safe summary;
- median, P95 and jitter;
- loss percentage, if validly measurable;
- confidence and known limitations.

## 5. Privacy and safety

- No packet payload inspection.
- No hidden traffic interception.
- No background VPN activation.
- No credentials or game-account access.
- Keep diagnostics local by default.
- Clearly disclose any future telemetry and obtain consent.
