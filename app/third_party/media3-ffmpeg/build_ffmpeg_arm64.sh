#!/bin/bash
set -euo pipefail

MODULE_PATH="$1"
NDK_PATH="$2"
FFMPEG_PATH="$3"
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/windows-x86_64/bin"
OUT="$FFMPEG_PATH/android-libs/arm64-v8a"

cd "$FFMPEG_PATH"
./configure \
  --target-os=android \
  --enable-static \
  --disable-shared \
  --disable-doc \
  --disable-programs \
  --disable-everything \
  --disable-avdevice \
  --disable-avformat \
  --disable-swscale \
  --disable-postproc \
  --disable-avfilter \
  --disable-symver \
  --disable-v4l2-m2m \
  --disable-vulkan \
  --enable-swresample \
  --enable-decoder=ac3 \
  --enable-decoder=eac3 \
  --arch=aarch64 \
  --cpu=armv8-a \
  --libdir="$OUT" \
  --cross-prefix="$TOOLCHAIN/aarch64-linux-android26-" \
  --nm="$TOOLCHAIN/llvm-nm.exe" \
  --ar="$TOOLCHAIN/llvm-ar.exe" \
  --ranlib="$TOOLCHAIN/llvm-ranlib.exe" \
  --strip="$TOOLCHAIN/llvm-strip.exe"
make -j8
make install-libs

test -f "$OUT/libavcodec.a"
test -f "$OUT/libavutil.a"
test -f "$OUT/libswresample.a"
echo "FFmpeg AC-3 libraries built for arm64-v8a"
