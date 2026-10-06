# Third-party software

The Android app bundles an arm64 AndroidX Media3 1.4.1 FFmpeg audio extension
to decode AC-3 and E-AC-3 on Pocket DS firmware that lacks platform decoders.
It uses the official Media3 decoder module under Apache License 2.0 and an
LGPL-only FFmpeg 6.0 configuration.

Exact revisions, configuration, rebuild steps, and the packaged license are in
[`app/third_party/media3-ffmpeg/README.md`](app/third_party/media3-ffmpeg/README.md).

## The Apple app

The iPad and iPhone app reads ebooks with Readium's Swift toolkit (#25, phase 4), added with Swift
Package Manager in `apple/project.yml` and pinned to release **3.11.0**. Only its `ReadiumShared`,
`ReadiumStreamer` and `ReadiumNavigator` products are used, and only on iOS: the navigator is UIKit,
so the Mac app does not link it. Each licence text ships in the app, in `apple/Hub/Resources/Licenses`.

| Software | Use | Licence | File |
|---|---|---|---|
| [Readium Swift toolkit](https://github.com/readium/swift-toolkit) 3.11.0 | Opens EPUBs and draws their pages | BSD 3-Clause, Copyright (c) 2017, Readium | `Readium-swift-toolkit-BSD-3-Clause.txt` |
| [CryptoSwift](https://github.com/krzyzanowskim/CryptoSwift) | Readium's streamer: deobfuscating embedded fonts | zlib-style, with the acknowledgement it asks for: "This product includes software developed by the "Marcin Krzyzanowski" (http://krzyzanowskim.com/)." | `CryptoSwift-License.txt` |
| [Zip](https://github.com/marmelroy/Zip) | Readium's archive access; it includes minizip, under the zlib licence | MIT, Copyright (c) 2015 Roy Marmelstein | `Zip-MIT.txt` |
| [DifferenceKit](https://github.com/ra1028/DifferenceKit) | Readium's navigator: changes to highlights | Apache 2.0 | `DifferenceKit-Apache-2.0.txt` |
| [Fuzi](https://github.com/readium/Fuzi) (Readium's fork) | Reading the EPUB's XML | MIT | `Fuzi-MIT.txt` |
| [ZIPFoundation](https://github.com/readium/ZIPFoundation) (Readium's fork) | Reading the EPUB's ZIP | MIT, Copyright (c) 2017-2024 Thomas Zoechling | `ZIPFoundation-MIT.txt` |
| [SwiftSoup](https://github.com/scinfu/SwiftSoup) | Reading a footnote's HTML | MIT | `SwiftSoup-MIT.txt` |

Readium's package also declares GCDWebServer, SQLite.swift (for LCP) and swift-docc-plugin, which
Swift Package Manager fetches but the app does not link. LCP and its DRM are not used.
