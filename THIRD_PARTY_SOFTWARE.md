# Third-party software

The Android app bundles an arm64 AndroidX Media3 1.4.1 FFmpeg audio extension
to decode AC-3 and E-AC-3 on Pocket DS firmware that lacks platform decoders.
It uses the official Media3 decoder module under Apache License 2.0 and an
LGPL-only FFmpeg 6.0 configuration.

Exact revisions, configuration, rebuild steps, and the packaged license are in
[`app/third_party/media3-ffmpeg/README.md`](app/third_party/media3-ffmpeg/README.md).

## Reading fonts (#47)

The ebook reader bundles three typefaces in `app/src/main/assets/fonts`, each under the SIL Open Font License
1.1 (the texts are in `app/src/main/assets/licenses` and on the app's Fonts and licences page). They are shipped
as released, not subsetted and not modified.

| Font | Version | Source | Files |
|---|---|---|---|
| Literata (default) | 3.103 | The Literata Project Authors, as released through Google Fonts (`ofl/literata`) | `Literata.ttf`, `Literata-Italic.ttf`: variable, weight 200 to 900, optical size 7 to 72 |
| Atkinson Hyperlegible Next | 2.001 | Braille Institute and the project authors, through Google Fonts (`ofl/atkinsonhyperlegiblenext`) | `AtkinsonHyperlegibleNext.ttf`, `-Italic.ttf`: variable, weight 200 to 800 |
| Charis | 7.000 | SIL Global's own release (`silnrsi/font-charis`, `Charis-7.000.zip`); Reserved Font Names "Charis" and "SIL" | `Charis-Regular.ttf`, `-Bold.ttf`, `-Italic.ttf`, `-BoldItalic.ttf` |
