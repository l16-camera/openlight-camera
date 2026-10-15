#!/bin/sh
# Rebuild the astro library and drop it where apktool picks it up.
# The APK build runs in docker without Rust, so the .so is kept prebuilt in the tree.
set -e
cd "$(dirname "$0")"
NDK="${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$BIN/aarch64-linux-android23-clang"
# generic: a global target-cpu=native would bake Apple instructions into an ARM build.
RUSTFLAGS="-C target-cpu=generic -C link-arg=-Wl,-soname,libopenlight_astro.so" \
    cargo build --release --target aarch64-linux-android --lib
mkdir -p ../../light_camera/lib/arm64-v8a
"$BIN/llvm-strip" -o ../../light_camera/lib/arm64-v8a/libopenlight_astro.so \
    target/aarch64-linux-android/release/libopenlight_astro.so
ls -l ../../light_camera/lib/arm64-v8a/libopenlight_astro.so
