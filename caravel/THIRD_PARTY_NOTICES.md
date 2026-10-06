# CARAVEL third-party runtime components

## Android PRoot engine and pr-cli

CARAVEL uses the pinned `oonid/pr` source commit:

`fcf25cb2396361f0be2edfc96fdd61a6e738c9d9`

At build time CARAVEL:

- builds the Android PRoot binary from that source with the repository's NDK
  build script;
- builds `pr-cli` for `aarch64-linux-android`;
- packages the pinned Android BusyBox payload;
- places the four ARM64 runtime binaries in the APK's native-library area.

The upstream project documents:
- `src/proot/`: GPL-2.0-or-later
- `src/pr-cli/`: MIT
- `android/` integration: MIT

Source:
https://github.com/oonid/pr/tree/fcf25cb2396361f0be2edfc96fdd61a6e738c9d9

## Ubuntu Base

CARAVEL downloads Ubuntu Base 24.04.5 ARM64 from Canonical and verifies:

`a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2`

Source:
https://cdimages.ubuntu.com/ubuntu-base/releases/24.04/release/

## Node.js

CARAVEL uses Hermes Agent's managed Node package manager inside Ubuntu and
requests Node.js major version 22. The runtime performs an explicit post-install
check that the managed binary reports `v22.x`.

CARAVEL does not bundle a Node.js binary in the APK and does not claim a fixed
Node.js patch-version hash here; the managed package is resolved at Hermes
installation time.

## Hermes Agent

CARAVEL uses official Nous Research Hermes Agent source at:

`345cd2b057a452236de401d3534b8502a7465e8d`

Version: 0.21.3  
License: MIT

Source:
https://github.com/NousResearch/hermes-agent/tree/345cd2b057a452236de401d3534b8502a7465e8d

The dashboard frontend is built from that same source revision and embedded
in CARAVEL.

## Legacy Hermes 3.8 APK

The reverse-engineered Hermes 3.8 APK remains in this repository only as a
historical/architectural reference for the original QRoot investigation.

CARAVEL does not distribute its PairIP licensing layer, embedded third-party
AI credential, or the legacy Hermes `libqroot`/`libtermux` runtime.
