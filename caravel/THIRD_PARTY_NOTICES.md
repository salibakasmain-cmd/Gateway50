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

## Native 7-Zip extraction stack

CARAVEL follows the Hermes 3.8 extraction architecture:

`Java -> JNI -> bit7z -> 7-Zip shared library`

CARAVEL builds:

- bit7z v4.1.0 — Mozilla Public License 2.0;
- 7-Zip v26.01 — upstream 7-Zip licensing applies;
- Android NDK libc++ shared runtime.

The JNI layer skips archive symlinks during the ordinary extraction pass,
pre-creates directory symlinks, then restores file symlinks through bit7z's
safe output-path handling.

Sources:
https://github.com/rikyoz/bit7z/releases/tag/v4.1.0
https://github.com/ip7z/7zip/releases/tag/26.01

## Ubuntu Base

CARAVEL downloads the fixed Xermes Android ARM64 Ubuntu archive:

`ubuntu.7z`

Release:
`v2026.09.02-model-startup`

SHA-256:

`01049b0d56fb5e8d8fc8483756bf43144942ab50a00978c7f531e6f9ec1462a8`

Source:
https://github.com/goldenduo/XermesRelease/releases/tag/v2026.09.02-model-startup

The asset is pinned rather than using a moving `latest` URL.

## Node.js

CARAVEL uses the official Hermes Agent Node bootstrap script from the pinned
Hermes source revision inside Ubuntu and requests Node.js major version 22.
The runtime performs an explicit post-install check that the managed binary
reports `v22.x`.

CARAVEL does not bundle a Node.js binary in the APK and does not claim a fixed
Node.js patch-version hash here; the upstream bootstrap resolves the current
Node.js 22 release at Hermes installation time.

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
historical/architectural reference.

CARAVEL does not distribute its PairIP licensing layer, embedded third-party
AI credential, or the legacy Hermes `libqroot`/`libtermux` runtime.
