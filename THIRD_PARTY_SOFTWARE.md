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

The iPad and iPhone app casts videos to a Chromecast or Google TV (#44) with Google's Cast iOS sender
SDK, from Google's own Swift package, pinned to **4.8.6**, its dynamic framework, on iOS only (the Mac
keeps AirPlay). The SDK is Google's, under its own terms; its package brings GTMSessionFetcher.

| Software | Use | Licence | File |
|---|---|---|---|
| [Google Cast iOS sender SDK](https://github.com/googlecast/google-cast-ios-sdk) 4.8.6 | Finds TVs, the Cast button, and the TV's player | Google APIs Terms of Service and the Google Cast SDK Additional Developer Terms; the package manifest is Apache 2.0 | `GoogleCast-SDK-Terms.txt` |
| [GTMSessionFetcher](https://github.com/google/gtm-session-fetcher) 3.5.0 | Google Cast's network requests | Apache 2.0, Copyright Google LLC | `GTMSessionFetcher-Apache-2.0.txt` |

The ebook reader's own typefaces (#47) are bundled in the Apple app, as variable fonts, and declared to
Readium: Literata (the default) and Atkinson Hyperlegible Next. Both are under the SIL Open Font
License 1.1, and the texts ship in `apple/Hub/Resources/Licenses`. The font files are unmodified, from
the Google Fonts repository (Literata 3.103, with its italic; Atkinson Hyperlegible Next 2.001).

| Font | Use | Licence | File |
|---|---|---|---|
| [Literata](https://github.com/googlefonts/literata) 3.103 | The reader's default typeface | SIL OFL 1.1, Copyright 2017 The Literata Project Authors | `Literata-OFL-1.1.txt` |
| [Atkinson Hyperlegible Next](https://github.com/googlefonts/atkinson-hyperlegible-next) 2.001 | A typeface for the reader, made for easy reading | SIL OFL 1.1, Copyright 2020-2024 The Atkinson Hyperlegible Next Project Authors | `AtkinsonHyperlegibleNext-OFL-1.1.txt` |

Charter, Georgia and Iowan Old Style, also offered, are the device's own fonts and are not shipped.

The reader's dictionary (#62) is the Pocket DS app's own offline index, bundled unchanged: Xcode copies
`app/src/main/assets/dictionary/en-wordnet-2025.db` into the app from where it is (`apple/project.yml`),
so the repository holds one copy. The Apple app's text of its licence names JellyHub.

| Data | Use | Licence | File |
|---|---|---|---|
| [Open English WordNet](https://en-word.net/) 2025, as a derived SQLite index (headwords, parts of speech, definitions) | The dictionary card in the reader, offline | CC BY 4.0, the Open English WordNet Community and contributors | `OpenEnglishWordNet-CC-BY-4.0.txt` |
