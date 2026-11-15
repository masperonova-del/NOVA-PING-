# HEV metadata integration

This directory contains the first native integration layer for the exact upstream
HEV revision `75dbc1e9ace6c70d3d1b94df7c283efe1de7effc`.

## What it changes

- Observes the TUN-input pbuf in `lwip_io_task_entry()` before compaction.
- Observes the TUN-output pbuf in `netif_output_handler()` before writing.
- Uses a typed wrapper around lwIP `pbuf_copy_partial()`; the portable parser
  reads only the IP and transport headers it needs.
- Builds the local-address list from HEV's configured TUN IPv4/IPv6 addresses,
  never from the internal loopback netif addresses.
- Uses two independent SPSC queues: one producer queue for TUN input and one
  for TUN output. This avoids treating two HEV task contexts as one SPSC producer.
- Drops telemetry on parser failure, unsupported input, non-lock-free atomics,
  or queue saturation. The original pbuf and tunnel result are not changed.
- Exposes one native poll function that drains both queues fairly, plus a combined
  dropped-sample counter. It requires exactly one polling consumer.
- Adds direct JNI exports for one fixed-size metadata record and the dropped counter.
- Adds a blocking JNI start entry point backed by `hev_socks5_tunnel_main_from_str()` and a stop entry point backed by `hev_socks5_tunnel_quit()`; the JNI layer duplicates the Android TUN descriptor, passes the duplicate as a borrowed external descriptor, and closes its duplicate after the blocking HEV call returns.
  The Kotlin bridge serializes all queue reads through one dedicated executor and
  rejects malformed native records before exposing them to the app.

The portable C sources are staged as separate translation units under
`src/nova-metadata/`. The pinned upstream `build.mk` recursively discovers
C source files below `src/`, so each implementation is compiled exactly once.
The hook patch includes only the public metadata headers; it does not include
implementation `.c` files.

## Apply and validate

Run:

```bash
bash scripts/prepare-hev-metadata.sh
```

The script checks out the pinned HEV revision, stages the already-tested
portable metadata sources, and verifies that the patch applies cleanly.

## Android packaging and limits

The Android CI builds the patched HEV shared library for arm64-v8a, armeabi-v7a,
x86 and x86_64, verifies the JNI and metadata symbols, stages each library in
the app's generated jniLibs directory, and packages them into the debug/release
APKs. The generated native libraries are build outputs, not committed binaries.

The Kotlin metadata bridge is passive: loading the library does not start HEV, establish
a TUN interface, request VPN consent, or route traffic. The native start/stop ABI is now available, but the Android service
must still coordinate explicit user consent, TUN establishment, protected SOCKS5
upstream sockets, foreground lifecycle, and reliable shutdown before any traffic
is routed. Device-level validation remains required.
