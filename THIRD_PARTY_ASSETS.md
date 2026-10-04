# Third-party visual assets

The service logos shown in Manage and Notifications use 512px PNG light/dark
variants retrieved on 2026-09-09. Android selects the matching file from
`drawable-nodpi` or `drawable-night-nodpi` when the app theme changes.

| Resource | Source | Licence / note |
|---|---|---|
| `logo_sonarr.png` | [Dashboard Icons: Sonarr](https://dashboardicons.com/icons/sonarr) | Dashboard Icons collection; Apache-2.0 repository licence, underlying marks retained by their owners |
| `logo_bazarr.png` | [Dashboard Icons: Bazarr](https://dashboardicons.com/icons/bazarr) | Dashboard Icons collection; Apache-2.0 repository licence, underlying marks retained by their owners |
| `logo_radarr.png` (dark app) | [Dashboard Icons: Radarr](https://dashboardicons.com/icons/radarr) | Dashboard Icons collection; Apache-2.0 repository licence |
| `logo_radarr.png` (light app) | [Dashboard Icons: external Radarr](https://dashboardicons.com/icons/external/radarr) | selfh.st/icons, CC BY 4.0; center recolored to the normal icon's `#FFC230` yellow |
| `logo_jellyfin.png` | [selfh.st/icons](https://github.com/selfhst/icons) | CC BY 4.0 |
| `logo_jellyseerr.png` | [selfh.st/icons](https://github.com/selfhst/icons) | CC BY 4.0 |
| `logo_qbittorrent.png` | [selfh.st/icons](https://github.com/selfhst/icons) | CC BY 4.0 |
| `logo_kavita.png` | The installed Kavita web app, `assets/icons/android-chrome-256x256.png` (256 px, retrieved 2026-10-04) | Kavita's own icon; mark retained by its owners |
| `logo_storyteller.png` | The installed Storyteller web app, `Storyteller_Logo.png` (2048 px, scaled to 512 px, retrieved 2026-10-04) | Storyteller's own logo; mark retained by its owners |
| `logo_bookkeeprr.png` | The installed BookKeeprr web app, `img/icon-512.png` (512 px, retrieved 2026-10-04) | BookKeeprr's own icon; mark retained by its owners |

The three book services' logos read on both light and dark cards, so each has one file in
`drawable-nodpi` and none in `drawable-night-nodpi`. The Apple app carries the same files as
imagesets in `apple/Hub/Resources/Assets.xcassets/Logos`, with no dark appearance.

## Fonts

Both apps bundle two typefaces under the SIL Open Font License 1.1, each with its licence text:
Android in `app/src/main/assets/licenses`, the Apple app in `apple/Hub/Resources/Licenses`.

| Font | Use | Files | Licence |
|---|---|---|---|
| [Bricolage Grotesque](https://github.com/ateliertriay/bricolage) | Titles, heroes and headings | `app/src/main/res/font/bricolage_{semibold,bold,extrabold}.ttf`; `apple/Hub/Resources/Fonts/Bricolage-{SemiBold,Bold,ExtraBold}.ttf` | SIL OFL 1.1, Copyright 2022 The Bricolage Grotesque Project Authors |
| [Figtree](https://github.com/erikdkennedy/figtree) | Everything else | `app/src/main/res/font/figtree_*.ttf`; `apple/Hub/Resources/Fonts/Figtree-*.ttf` | SIL OFL 1.1, Copyright 2022 The Figtree Project Authors |

The Apple fonts are the Android files with only their name tables changed. Each Android Bricolage
weight names itself `BricolageGrotesque-96ptExtraBold` (and each Figtree weight `Figtree-Light`),
and iOS and macOS register a font by that name, so only one weight of each would load.

Product names and trademarks remain the property of their respective owners.
The Ayaneo Hub mark is this project's own launcher foreground.
