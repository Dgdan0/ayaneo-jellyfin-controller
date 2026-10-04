# Glass: the shared look for the Pocket DS and the Apple apps

Chosen on 2026-10-04 from three directions (Marquee, Glass and the current redesign). The
clickable prototype covers every screen on iPad, iPhone and the Pocket DS:
<https://claude.ai/artifact/EP7FSFUuF1W1XcRuRPZ9Qi> (private to the owner). Its brief is that the
whole page takes the colour of whatever is in focus, behind frosted panels, the way the Apple TV
app works with a remote.

This file describes the design. Status lives in the issues:

| Part | Issue |
|---|---|
| Hub: each artwork's colours | #10 |
| Android: Glass on the Pocket DS | #11 |
| Apple: Glass on iPad, iPhone and Mac | #12 |

## What Glass is

- **Always dark.** The page base is the focused artwork's `dark` colour. Above it sits an
  ambient layer, which is the artwork itself, small and heavily blurred, at about 60% brightness.
  A veil over that adds a faint light at the top and darkens the bottom, so type stays readable on
  any artwork.
- **Focus re-tints the page.** Moving to another card cross-fades the ambient layer and the
  colours over 0.6–0.8 s. Pages report their artwork:
  - Home: the focused card;
  - a title page: its backdrop;
  - Books: the cover;
  - the player and readers: what is playing or open.
- **Panels are glass.** These are the bars, side sheets, dashboard cards, pills and round
  buttons.
  - On Apple they are real material: a blur of what is behind, plus a hairline edge and a top
    highlight.
  - On the Pocket they are **tints**: `mix(dominant 30%, #12141C at 80%)`, with the same edge and
    highlight. Over an ambient layer that is already blurred, a tint looks frosted and costs
    nothing. Nothing blurs live, and never over video.
  - On Apple, Reduce transparency switches to the Pocket's tints.
- **Focus and pointer.** Cards get a 3 dp white ring and a small lift (scale 1.035–1.06). On the
  iPad a resting pointer counts as focus. Buttons get a white outline. Inside clipped containers
  (segmented controls, sheet rows) the ring is drawn inset.
- **Buttons.**
  - The main action is a white pill (Play, Resume, Request). Books use their accent instead:
    gold for Resume reading.
  - Secondary actions are glass pills (Details, Trailer).
  - Toggles are round glass buttons (watched, favourite, download, more), and turn white when on.
- **Accent.** Teal for Movies & TV and gold for Books, picked in Settings as today. It colours
  progress bars, eyebrows, UP NEXT, toggles and the selected Media/Books segment. The accent never
  fills a whole panel.
- **Type.**
  - Bricolage Grotesque for titles, heroes and headings. It is new: bundle it with its OFL licence
    and add it to `THIRD_PARTY_ASSETS.md`.
  - Figtree for everything else, as today.
  - Book covers drawn by the app use Cormorant Garamond only in the prototype. Real covers come
    from the servers.
- **Real artwork everywhere.** The prototype draws stand-ins. The apps use the hub's posters,
  covers, stills and backdrops as they do now.

## Navigation per device

| | iPad and Mac | iPhone | Pocket DS |
|---|---|---|---|
| Sections | Glass capsule centred at the top: Home, Discover, Library, Downloads, Activity | Glass tab bar floating at the bottom | Glass capsule at the top left between `L1` and `R1` key caps |
| Media / Books | Segmented glass pill, top left | Same, top left on root pages | Top right, before the icons |
| Notifications, Services, Settings | Round glass icons, top right, plus the profile avatar | Bell and avatar; the avatar opens a sheet with profiles, Notifications, Services and Settings | Round icons, top right. Ⓨ opens profiles on Home |
| Back | Glass pill with the previous page's name | Round glass back button | Ⓑ; the hint bar's Back can be tapped |
| Hint bar | — | — | Tinted panel along the bottom, recomputed on every focus change |

## Screens

The prototype's index lists them all. Notes that are not obvious from looking at it:

- **Home.**
  - The hero follows focus, as on Android today: no overview, and every line keeps its place.
  - On the Pocket the hero stays put and the focused row rests under it (`pinFocusedRows`). On
    Apple the whole page scrolls.
  - Continue watching and Next up are 16:9 tiles with captions. The other rows are posters.
    Coming up shows a day chip.
- **Title pages.**
  - The backdrop fills the top and fades through a mask (not a solid gradient) into the ambient.
  - Underline tabs: Episodes, More like this, Cast, Details.
  - Seasons are glass pills.
  - Episodes are wide tiles, with UP NEXT on the one you are on and a tick on watched ones.
- **Discover.**
  - A featured title, then Jellyseerr's rows with availability chips: In library, Partial, On the
    way, Requested.
  - A title you do not have opens on its pipeline as glass chips. The active stage pulses amber.
  - Request opens a glass side sheet. Quality and folder step with ◀ ▶ on the Pocket.
- **Upcoming.** Week capsule. The days list on the left; on iPad and the Pocket, a preview of the
  selected release on the right.
- **Library.**
  - The root is glass tiles, each fanned with three of its posters, plus search and Favourites.
  - A library is a capsule of library names, Sort and the direction, then the poster grid
    (seven columns on the Pocket) with unwatched counts or a watched tick.
- **Books Home.**
  - The book being read at full cover size, then Also reading as glass rows with format icons.
  - Your series as fans of covers.
  - Comics and manga with a kind label, then Recently added.
- **Books pages.**
  - The series page opens on its fan and a Continue card.
  - A book shows its formats (Ebook, Audiobook, Read along), with missing ones dimmed.
  - Comics have volume pills and issue covers.
  - Audiobook covers are square.
- **Readers.**
  - **Comic reader:** frosted top and bottom bars that hide with a tap in the middle. **Thirds**
    reads a page in three steps and is the default on the Pocket's wide, short screen.
  - **Read along:** the sentence being read glows in the accent, and a glass player sits at the
    bottom.
- **Player.**
  - Glass pills along the top: Audio & subtitles, Chapters, This video. Then round Cast, Lock and
    PiP.
  - The middle row is unchanged, and the timeline sits in a frosted bar.
  - The panels are glass side sheets. On the Pocket the chrome takes the playing title's colour.
- **Activity, Notifications and Services.**
  - Glass cards and columns. The attention card is amber-edged.
  - Notifications put the reading services first while in Books. On the iPhone the columns stack.
- **Settings.** Appearance adds Ambient colour (on/off) and Reduce transparency. Both are always
  on for the Pocket.

## Artwork colours from the hub (#10)

One implementation instead of two picture-analysis copies, and identical colours on every device.

`GET /v1/img/colors?src=<image path>&src=…` takes up to 60 hub image paths, exactly as they
appear in other responses. Authenticated; no extra scope, like the images themselves. It spends the
artwork rate budget.

```json
{
  "colors": {
    "/v1/img/jf/d151b13906a376f2a95e6bc56a43b4a9/Backdrop?tag=c677&w=1280": {
      "dominant": "#8c1f24", "dark": "#2a0f10", "vivid": "#e3343c", "light": "#f6dcd9"
    }
  },
  "pending": ["/v1/img/tmdb/w342/kaMisKeOoTBPxPkbC3OW7Wgt6ON.jpg"],
  "missing": []
}
```

- Keys echo each `src` exactly as sent.
- `pending` paths are still being worked out. Ask again in a few seconds.
- `missing` paths could not be read (not found, not an image, an unsupported format). Do not ask
  again this session.
- An invalid `src` fails the whole request with `400 invalid_request`. That means one that is not
  a hub image path, an external URL, or more than 60 of them.

What each colour is for:

| Colour | Use |
|---|---|
| `dominant` | Tints panels (Pocket, Reduce transparency) and is the base hue for everything else |
| `dark` | Page base under the ambient layer; never pure black |
| `vivid` | The most saturated colour present, for glows; falls back to `dominant` |
| `light` | Pale tone of the dominant hue, for text drawn straight on artwork |

**How the hub decides.** It samples about 4,000 pixels and clusters them in Oklab (k-means with a
fixed start, so the same image always gives the same answer). It then picks:

- **dominant:** by population, weighted towards colourful clusters and away from near-black and
  near-white. A black poster with gold type is gold, not black.
- **dark and light:** the dominant hue at fixed lightness.
- **vivid:** the most saturated cluster with at least 3% of the pixels.

**Caching.**
- The key ignores width, so `w=360` and `w=1280` of one image share one entry.
- Results persist beside the other registries in `artwork-colors.json`, so a restart keeps them.
- Work that misses the request's 2.5 s budget finishes in the background.
- All images arrive as JPEG or PNG, which Go decodes natively. The hub's only external module
  stays `gopkg.in/yaml.v3`.

**Apps.**
- Ask in batches: a row or page asks for its visible artwork when it binds, and focus asks for one
  item that is still unknown.
- Keep an in-memory cache, persisted on the device.
- Use a neutral tint until the colours arrive.
- Older apps never call it.

## Building it

**Android (#11), in this order:**

1. **Foundations.**
   - `ArtworkColors`: client, batching and cache.
   - A pure `GlassColors` class (mix, contrast, tint) with tests.
   - `AmbientLayerView`: the focused artwork decoded at 1/16 size and drawn scaled up with
     filtering, then `RenderEffect` blur on Android 12+, with a cross-fade.
   - `GlassPanelDrawable`: a tinted panel with an edge and a highlight.
   - Bricolage Grotesque.
2. **Shell.**
   - `TopBarView` as the glass capsule, the hint bar as a tinted panel.
   - The ambient layer behind every screen in `HubActivity`; screens report their artwork.
3. **Media Home:** hero art with a mask fade, rows, ring and lift, tint on focus.
4. **Titles:** title pages, Discover with the request page and sheet (`SidePanelView` in glass),
   Upcoming.
5. **Dashboards:** Library, Downloads, Activity, Notifications, Services, Settings with the Glass
   switches.
6. **Books:** Home, Discover, Libraries, series, book and author pages, comic runs.
7. **Readers and player:** comic reader with Thirds, the epub and read-along chrome, player chrome.
8. **On the device:** checked on the Pocket (120 Hz, focus paths, the hint bar on every screen),
   with screenshots in `shots/`.

**Apple (#12):**

1. **Foundations.**
   - HubKit `ArtworkColors` with tests.
   - An ambient background modifier.
   - Glass panel and button styles (material, or tint under Reduce transparency).
   - Bricolage Grotesque.
2. **Shell:**
   - iPad and Mac: the top capsule and Media/Books.
   - iPhone: the bottom glass tab bar and the avatar sheet.
3. **The screens that exist:** Home, Library and title pages (#9), and Services, restyled.
4. **The rest:** each screen the Apple app still lacks gets its own issue and is built in Glass
   from the start.
