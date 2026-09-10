# Media3 FFmpeg audio extension

`app/libs/media3-decoder-ffmpeg-1.4.1-ac3-arm64.aar` is an arm64 build of the
official AndroidX Media3 FFmpeg decoder. It adds AC-3 and E-AC-3 audio decoding
for Pocket DS devices whose Android firmware does not expose those codecs.

## Exact sources

- AndroidX Media commit `c35a9d62baec57118ea898e271ac66819399649b`
  (tag `1.4.1`), Apache License 2.0.
- FFmpeg commit `ba69be84a1ceabfb39127831ad8da0fd7cb471f3`
  (`release/6.0`), LGPL 2.1 or later for this configuration.

The FFmpeg configuration has `CONFIG_GPL=0`, `CONFIG_NONFREE=0`, and enables
only the `ac3` and `eac3` decoders plus their required dependencies.

## Rebuilding

1. Check out the exact AndroidX Media and FFmpeg commits above.
2. Link the FFmpeg checkout as
   `libraries/decoder_ffmpeg/src/main/jni/ffmpeg` in the Media checkout.
3. With Android NDK r26b and GNU Make available, run:

   ```sh
   ./app/third_party/media3-ffmpeg/build_ffmpeg_arm64.sh \
     <media-checkout>/libraries/decoder_ffmpeg/src/main \
     <android-sdk>/ndk/26.1.10909125 \
     <ffmpeg-checkout>
   ```

4. In `libraries/decoder_ffmpeg/build.gradle`, set the module minimum SDK to
   26 and `ndk.abiFilters` to `arm64-v8a`, then run
   `gradlew :lib-decoder-ffmpeg:assembleRelease`.
5. Copy the resulting AAR from
   `libraries/decoder_ffmpeg/buildout/outputs/aar/` to `app/libs/`.

The application source and Gradle project in this repository provide the
relinkable application material. The exact unmodified library source is
available at the source commits above. The FFmpeg LGPL text is packaged in the
APK at `assets/licenses/FFmpeg-LGPL-2.1.txt`.
