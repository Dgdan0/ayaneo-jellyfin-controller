# Third-party software

The Android app bundles an arm64 AndroidX Media3 1.4.1 FFmpeg audio extension
to decode AC-3 and E-AC-3 on Pocket DS firmware that lacks platform decoders.
It uses the official Media3 decoder module under Apache License 2.0 and an
LGPL-only FFmpeg 6.0 configuration.

Exact revisions, configuration, rebuild steps, and the packaged license are in
[`app/third_party/media3-ffmpeg/README.md`](app/third_party/media3-ffmpeg/README.md).
