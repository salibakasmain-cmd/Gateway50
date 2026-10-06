# CARAVEL

Standalone Android host for a Hermes Agent runtime.

## Architecture milestone

CARAVEL is deliberately **not** a Termux wrapper and does not depend on a VPS.

The Android APK owns:

- the dashboard UI and Hermes visual theme
- the foreground runtime lifecycle
- the Ubuntu ARM64 userspace
- the Android-specific PRoot execution engine
- the Hermes Gateway
- the Hermes web dashboard
- persistent agent state

The current runtime architecture is:

```
Android APK
    |
    +-- CARAVEL dashboard
    |
    +-- foreground RuntimeService
    |
    +-- pr-cli + Android-patched PRoot
    |
    +-- Ubuntu Base ARM64
    |
    +-- Hermes Agent 0.21.3
    |      +-- Gateway 127.0.0.1:8642
    |      +-- Dashboard 127.0.0.1:9119
    |
    +-- controlled Android capability bridge (future)
```

## Runtime installation

First launch prepares the Linux environment. The intended sequence is:

1. Install and verify Ubuntu Base 24.04.5 ARM64.
2. Install Hermes Agent 0.21.3 inside that Ubuntu userspace.
3. Install the Node runtime needed by Hermes tooling.
4. Start the Hermes Gateway under the CARAVEL foreground service.
5. Start the Hermes Dashboard and embed it in CARAVEL.

The Ubuntu rootfs and Hermes source are downloaded on demand and activated through
staged directories plus completion markers, so an interrupted update does not
silently replace a working installation.

## Build

The GitHub Actions workflow:

- pins the Android-compatible PRoot source
- builds the patched PRoot and loader for arm64
- builds the Rust `pr-cli` launcher for `aarch64-linux-android`
- verifies the bundled BusyBox archive
- builds the official Hermes dashboard frontend at the pinned Hermes commit
- assembles a debug APK

## Security / provenance

CARAVEL does not carry the old Hermes 3.8 PairIP licensing layer or its bundled
third-party AI backend credential.

Runtime sources are pinned and verified where practical. See
`THIRD_PARTY_NOTICES.md` for component licensing.
