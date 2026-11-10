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

### WI-FI STABILITY MONITOR

Runs in a user-started Android foreground service with a persistent notification and an explicit stop action. `ConnectivityManager` callbacks observe Wi-Fi availability, cellular availability, validation state and changes to the default transport (Wi-Fi, cellular data, VPN or other). A periodic snapshot is also recorded approximately once per minute.

Events are stored locally in shared preferences, with the displayed history capped at 15 entries. The service unregisters callbacks and stops its sampling loop when stopped or destroyed. Android may still restrict or terminate background work according to device and OS policy; the app does not promise uninterrupted monitoring. The tracker does not infer the physical cause of a disconnect, inspect packet payloads, or measure a game's server ping.

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


## 1.13 presentation and optimizer boundary

The UI motion helper is presentation-only. It must never schedule, delay, cancel or alter a network probe. Diagnostic timestamps are captured by the measurement layer, not by animation callbacks.

The future optimizer integration is specified in docs/OPTIMIZATION-BRIDGE.md. It remains disconnected until the optimizer implementation and its exact API are reviewed.


## 1.14 dashboard experience

The dashboard surfaces the active Android transport, Internet validation state and Android's metered-network flag. Bandwidth values, when available, are estimates advertised by Android and are not throughput measurements. Returning to the activity refreshes the network summary. These indicators do not alter the diagnostic engine or prove game-server latency.
