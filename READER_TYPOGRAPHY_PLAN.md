# Reader typography like Kindle (#47)

Decided by the owner on 2026-10-08:
- **Font:** Literata is the default on every device (existing devices switch on update). Atkinson Hyperlegible Next and Original are in the menu, plus Apple's built-in Charter, Georgia and Iowan on Apple.
- **Themes:** a true-black Dark, with the grey kept as Dim. Comfort's black page goes.
- **Top line:** the book title in the centre, the time top right.
- **Defaults:** Kindle's margins (90 pt and a 48 pt column gap on the iPad, scaled elsewhere), 130% text (120% on iPhone), line spacing choices 1.3, 1.5 and 1.8.

The measured comparison, the recommendations and the implementation appendix follow, as the analysis agent wrote them.


*Light Bringer, chapter 85 "DARROW", on the same iPad. This is research only: nothing in the apps or the hub was changed.*

**How it was measured.** The screenshots are of an iPad mini screen (744 × 1133 points), saved at 1313 × 2000 pixels, so **1.765 pixels = 1 point**. Every number below comes from scanning the pixels for ink with Python. Colours are accurate to about ±2 per channel because WebP compression shifts them slightly.

**About our screenshots.** They show Readium's own typography with the text aligned left. You can tell from the ragged right edge and from "85", "DARROW" and "Dusk and Dawn" all coming out the same size. The text was at about 140%, with the **Wide** margin (68 pt, which is Readium's 40 pt × 1.7). The new build (justified, hyphenated, 1.5 spacing, publisher styling off, the corners) is accounted for under each heading.

---

## 1. The main differences, ranked by how much each changes the feel

### 1. The typeface (the biggest one)

Kindle uses **Bookerly** at 22 pt. That figure comes from the book's own heading sizes, which Kindle keeps. Our page uses whatever the book asks for, which is a generic "serif": **Times New Roman** on the iPad and Noto Serif on Android. Our "Serif" choice in the menu is that same Times.

| | Kindle (Bookerly) | Ours (Times) |
|---|---|---|
| Capital height | 15.3 pt | 15.3 pt |
| Lowercase height (x-height) | 10.8 pt | 10.2 pt |
| Upright strokes | 3.9 px | 3.5 px (Kindle's are 12% heavier) |
| Thin strokes (serifs, the tops of o and e) | 3.1 px | 2.5 px (Kindle's are 24% heavier) |
| Share of the lowercase band that is ink | 34% | 31% |

The letters are the same size, so size isn't the cause. Times is a newspaper face whose thin strokes are hairlines: thick strokes are 2.9 times the thin ones. On a backlit screen those hairlines fade, and the text looks spindly and busy. Bookerly was drawn for screens: its strokes are sturdier and closer to each other in weight. That accounts for most of "easier to read".

**After the new build:** unchanged. It is still Times.

### 2. Page colour and ink

| Theme | Page | Text | Contrast |
|---|---|---|---|
| Kindle Sepia | `#FCF0D9` | `#5A4931` | 7.7 : 1 |
| Our Sepia | `#EFE2C6` | `#3E3526` | 9.4 : 1 |
| Kindle Dark | `#000000` | `#AFAFAF` | 9.6 : 1 |
| Our Night | `#202020` | `#DFDFD8` | 12.2 : 1 |
| Our Comfort "black page" | `#000000` | `#C9C3B6` | 12.0 : 1 |

Kindle's sepia page is lighter and creamier than ours (92% lightness against 86%). Its ink is a soft brown, not near-black. Kindle's dark theme is pure black with mid-grey text. Both have less glare than ours and both stay above the 7:1 mark for comfortable reading. Kindle's strokes are heavier, so the block of text is about as dark as ours overall; it just sits on a calmer page.

**After the new build:** unchanged.

### 3. Margins, line length and the gap between two columns

**Portrait**

| | Kindle | Ours (screenshot, Wide) | Ours (new default, Balanced) |
|---|---|---|---|
| Side margins | 90 pt (12.1%) | 68 pt (9.1%) | 40 pt (5.4%) |
| Text width | 564 pt | 608 pt | 664 pt |
| Characters per line | **58** | 66 | about 73 |

**Landscape, two columns**

| | Kindle | Ours (screenshot) | Ours (Balanced) |
|---|---|---|---|
| Outer margins | 90 pt (7.9%) | 68 pt (6.0%) | 40 pt (3.5%) |
| Gap between the columns | **48 pt (4.2%)** | **136 pt (12.0%)** | 80 pt (7.1%) |
| Column width | 452 pt (39.9%) | 430 pt (38.0%) | 487 pt (43%) |
| Characters per line | 45 | 46 | about 55 |

Kindle keeps the same 90 pt margin in both orientations, with a narrow gap, so the two columns read as one open book. Ours is the other way round: thin outer margins and a gap **twice as wide as either of them**. That is how Readium is built: it pads every column on both sides (appendix A3). Our lines are also long: 66 to 73 characters, against Kindle's 58, which sits in the middle of the comfortable 45 to 75 range. Long lines make it harder to find the start of the next one.

The page is also fuller top to bottom. In two columns Kindle's text starts 96 pt from the top and ends about 128 pt from the bottom. Ours starts at 70 pt and ends at 97 pt.

**After the new build:** unchanged, and at the default Balanced margin the gap is still twice the outer margin.

### 4. Justification, hyphenation and line spacing

Kindle justifies and hyphenates ("arti-", "dis-", "cit-"). **The new build does the same.**

Kindle's lines are 31.5 pt apart, which is 1.43 times Bookerly's size. Our screenshot had 33.0 pt. The new default of 1.5 with Times at 140% gives 33.6 pt. Measured against the height of the small letters, that is 15% looser than Kindle, because Times has small lowercase. With Literata (see 2.1), **1.5 matches Kindle's spacing** (31.2 pt at 130%).

### 5. The chapter opening

| | Kindle | Ours |
|---|---|---|
| "85" | Trajan, 42 pt tall | Times, 23 pt |
| "DARROW" | Trajan, capitals 33 pt | Times, 23 pt |
| "Dusk and Dawn" | Bookerly italic, 18 pt | Times italic, 23 pt (larger than Kindle's) |
| Drop cap "N" | Trajan, grey `#585858`, 3 lines (80 pt) | Times, ink colour, 2 lines (49 pt) |
| Paragraph indent | 25.5 pt, the same in every layout | 22 pt in portrait, shrinking to **16 pt** in a column |

There are two causes:

- **A bug.** The book carries its own Trajan Pro font for headings and the drop cap. That font is scrambled (Adobe's "font mangling"), and the key to unscramble it is the book's UUID. But the ID the book declares first is "25103610", which is the one Readium uses, so the font comes out as garbage and the page falls back to Times. Kindle decodes it properly. **Two of the 61 EPUBs in the library are affected: Light Bringer and Dark Age.** One hub fix solves it for both apps.
- **Readium's heading sizes.** With publisher styling off, Readium sets every second-level heading to 1.5 times the text. "85", "DARROW" and "Dusk and Dawn" are all that level, so they come out the same size. Kindle keeps the book's own sizes (2.54×, 2.03× and 1.1×).

### 6. The corners

| | Kindle | Ours (new build) |
|---|---|---|
| Top | time on the **left**, book title in the **centre** (capitals) | time on the right, no title |
| Size | about 13.5 pt, regular weight (capitals 9.6 pt) | 11 pt, medium weight |
| Colour | the page's full ink (`#5B4B33` on sepia, 7.4:1) | ink at 60% (`#857A66`, 3.3:1) |
| Placement | lined up with the edges of the text; baselines 50 pt from the top and 61 pt from the bottom | 22 pt from the screen edges, centred in 62 pt strips |

Kindle's corner text is larger and darker than ours, so it is easier to read at a glance, and it lines up with the text so the page looks tidy.

### What is left after the new build

Everything except justification and hyphenation is still different: the typeface, the colours, the margins and the gap between columns, the chapter openings, and the corners. Line spacing is also slightly too loose until the typeface changes.

---

## 2. What we should change

### 2.1 Fonts

- **Default: Literata.** It is free to ship (SIL Open Font License) and is the reading font of Google Play Books, made for the same job as Bookerly. It measured closest of everything tested:
  - its capitals are the same height relative to its small letters (1.38 against Bookerly's 1.42);
  - it is the same width (57 characters on Kindle's line against 58);
  - its thick and thin strokes are the closest in weight of any serif tested (1.9:1, against 2.9:1 for Times).
- **Bundle in both apps:**
  - Literata, upright and italic (1.8 MB; covers every weight);
  - Atkinson Hyperlegible Next, about 0.2 MB, free to ship (Kindle's menu has the original Atkinson Hyperlegible);
  - Charis SIL on Android only. It is Charter, extended and free to ship, and Apple devices already have Charter.
- **Apple-only extras** (built into iPad, iPhone and Mac, so they cost nothing): Charter, Georgia and Iowan Old Style.
- **Menu:** Original (the book's own) · **Literata** · Charter · Georgia (Apple) · Iowan Old Style (Apple) · Atkinson Hyperlegible · System sans.
- **Drop the "Serif" choice.** It *is* Times, the look you don't like.
- **Bundled, not system, for the default.** That way the iPad, iPhone, Mac and Pocket DS all look the same. Apple's built-in fonts are a bonus on Apple devices.
- **Your devices already store "Publisher".** Either "Reset text style" also switches the typeface to Literata, or the update switches it once. (Question 1.)
- **Not chosen:**
  - Merriweather: its lowercase is so large that it reads as a bigger, wider face, and the file is 4.5 MB;
  - Source Serif 4: a close second, but with more stroke contrast than Literata;
  - Newsreader: its lowercase is too small.

### 2.2 Size and weight

- **Default 130%** with Literata. Its small letters then measure 10.6 pt, against Kindle's 10.8 pt in your screenshots (step 5 of Kindle's 18).
- **iPhone: 120%.** On the Pocket DS, 130% gives the same physical letter size as Kindle on the iPad, because both screens have about 160 points to the inch.
- At the same percentage, Literata looks 14% larger than Times. You read at about 140% today, so 130% is your current size.
- **Weight:** Literata's regular weight is already as sturdy as Bookerly's. A "Bolder text" switch can come later. Literata comes in every weight and Readium has a weight setting, which covers what Kindle's "Amazon Ember Bold" offers.

### 2.3 Line spacing

- Keep **1.5** as the default. With Literata it equals Kindle's spacing (31.2 pt against 31.5 pt).
- Change the three choices from 1.1 / 1.5 / 1.9 to **1.3 / 1.5 / 1.8**. At 1.1 the lines are cramped.

### 2.4 Margins

| | Narrow | **Balanced (default)** | Wide |
|---|---|---|---|
| iPad, Mac | 48 pt | **90 pt (Kindle's)** | 120 pt |
| iPhone, portrait | 16 pt | **24 pt** | 36 pt |
| Pocket DS | 24 dp | **36 dp** | 56 dp |

The corners line up with the edges of the text, as on Kindle.

### 2.5 Two-column layout

- **Target on the iPad, landscape:** outer margins 90 pt, gap **48 pt**, columns 452 pt. That is exactly Kindle's.
- **Pocket DS:** outer margins 36 dp, gap 32 dp, columns about 374 dp (about 38 to 42 characters per line).
- **How:** cut Readium's padding on each side of a column to half the gap. Add the rest of the outer margin outside the page, by insetting the page view (appendix A3). This is an app change in both apps; the hub isn't involved.

### 2.6 Themes

| Name | Page | Text | Contrast | |
|---|---|---|---|---|
| Paper | `#FBFAF6` | `#282B29` | 13.7 | unchanged |
| **Sepia** | **`#FCF0D9`** | **`#5A4931`** | 7.7 | Kindle's, measured |
| **Dim** (today's Night) | `#202020` | **`#C8C8C2`** | 9.7 | the same grey page, with softer text at Kindle's contrast |
| **Dark** (new) | **`#000000`** | **`#AFAFAF`** | 9.6 | Kindle's, measured |
| Blue | `#1D303D` | `#DCE6E8` | | unchanged |

**Recommendation: add a true-black Dark and keep the grey as Dim.**

- **Why both:** black is best in a dark room and on the iPhone's OLED screen. Grey is gentler in a lit room, and the iPad mini's LCD screen gains nothing from black.
- **Cost:** keeping the grey costs nothing, because it already exists.
- **Comfort's "black page" switch:** with Dark in the theme list, retire it, so there is one way to get a black page, not two.
- **"Use system colours":** Paper by day, Dark at night.

### 2.7 The corners

- **Add the book title, top centre**, in small capitals like Kindle ("LIGHT BRINGER"). It is cut short so it never reaches a corner.
- **Clock:** you asked for top right before. Kindle puts it top left, opposite nothing, because the title is in the middle. Both work with a centred title. I'd keep **top right**, your choice, where it mirrors the percentage at the bottom right. (Question 3.)
- **Style:**
  - iPad: **13 pt, regular weight, the page's full ink colour**, lined up with the edges of the text. Baselines about 50 pt from the top and 60 pt from the bottom, with the text starting about 84 pt down and ending about 96 pt up.
  - Pocket DS: 12 sp, with strips of about 30 dp.

### 2.8 The Appearance menu

Kindle's Font sheet has:

- a row of typefaces, each showing "Aa" in its own face;
- a size slider with 18 marked steps;
- a "Spacing" row that opens its own page;
- a brightness slider fixed at the bottom of the sheet.

Ours has:

- three tiles drawn in the system's own fonts, so they don't show what the page will use;
- − and + buttons with a percentage;
- margins and spacing on the Layout tab;
- brightness inside Comfort.

**Change it to:**

1. A typeface row of 5 to 7 tiles, each with "Aa" drawn in the real font and its name underneath.
2. A **stepped size slider** from 70% to 200% in 10% steps (14 marks), with a small A and a large A at the ends. Left and right on the D-pad move it one mark, so the controller keeps working.
3. A **Spacing** row on the Font tab that opens line spacing and margins (Kindle's grouping). Layout keeps columns, alignment, hyphenation, publisher styling and Reset.
4. **Brightness fixed at the bottom of every tab**, moved from Comfort. Warmth stays in Comfort.

---

## 3. Effort and order

| # | Change | Where | Effort |
|---|---|---|---|
| 1 | Sepia, Dark and Dim colours; retire the black-page switch | both apps | **quick**, a few hours each |
| 2 | Margins and two-column layout | both apps | **quick to medium**, about 1 day each |
| 3 | Corners: size, colour, alignment, title | both apps | **quick**, half a day each |
| 4 | Literata as default and the new font menu (bundles the fonts) | both apps | **larger**, 1 to 2 days each, about 2 MB per app |
| 5 | Menu shape: slider, Spacing page, brightness at the bottom | both apps | medium, about 1 day each |
| 6 | **Hub:** unscramble the book's embedded fonts in the reading copy | hub | small, half a day plus tests. Brings back the Trajan headings in Light Bringer and Dark Age |
| 7 | **Hub (optional):** keep the book's heading sizes; give paragraph indents a fixed size so they don't shrink in columns | hub | medium |

**Order:** 1, 2, 3, then 4, then 5. Item 6 can go in at any time, alongside. Items 1 to 3 and 6 bring most of the visible gain within days. Item 4 is the biggest single change.

---

## 4. Choices for you

1. **Literata as the default font**, bundled in both apps, and switch your existing devices to it on update? (yes / no)
2. **Dark:** add true-black Dark and keep today's grey as **Dim** (A, recommended), or replace the grey (B)?
3. **Clock:** top right as now (A), or top left like Kindle (B)? And **add the book title** at the top centre? (yes / no)
4. **Font menu:** the same on every device (A), or Apple devices also offer their built-in Charter, Georgia and Iowan (B, recommended)?
5. **Kindle's margins as the default** (90 pt on the iPad, a 48 pt gap between columns)? (yes / no)
6. **Default text size 130%**, your Kindle size, with 120% on the iPhone? (yes / no)

---

## Appendix (for the app agents)

### A1. Raw measurements (screenshot pixels; ÷ 1.765 = points)

| Portrait 1313 × 2000 | Kindle | Ours |
|---|---|---|
| Text left / right edge | 159 / 1154 | 120 / ≤ 1181 (ragged; box edge 1193) |
| x-height / cap / ascender / descender | 19 / 27 / 29 / 9 | 18 / 27 / 28 / 9 |
| Baseline pitch | 55.6 | 58.3 |
| Paragraph indent | 45 | 39 |
| Header: cap top / baseline; footer baseline | 72 / 88; 1893 | (none) |
| Corner text: cap / x-height | 17 / 12 | (none) |
| "85" / "DARROW" / "Dusk and Dawn", glyph height | 74 / 59 / 32 | 40 / 40 / 41 |
| Drop cap N, height × width | 142 × 146 (3 lines) | 86 × 92 (2 lines) |
| Rule under "85" | x 537–775, 3 px, `#5C5854` | x 527–785, 4 px, ink colour |
| Full-line characters (mean) | 58.3 | 65.8 |

| Landscape 2000 × 1313 | Kindle | Ours |
|---|---|---|
| Left column / right column | 159–958 / 1042–1840 | 120–880 / 1120–1880 |
| First line top / last baseline | 170 / 1087 | 124 / 1141 |
| Lines per column; characters per line | 17; 45.1 | 18; 45.9 |
| Indent in a column | 45 | 28 |
| Header / footer baseline | 88 / 1206 | (none) |

Other measurements:

- **Stroke weight.** Mean run of ink at 50% contrast: upright strokes 3.90 against 3.48 px; thin strokes 3.14 against 2.54 px. Share of the x-band that is ink: 0.339 against 0.306.
- **Bookerly's size, cross-checked.** Kindle keeps the book's heading sizes, so Trajan's capitals (0.75 of the font size) give the body size: "85" at 2.54 em gives 38.9 px; "DARROW" at 2.03 em gives 38.8 px. That is 22.0 pt, with a line height of 1.43.
- **Bookerly's proportions.** x-height ≈ 0.488 of the font size, capitals ≈ 0.694.
- **Colours.** Our sepia measured `#EEE3C7` / `#3E3320` and our Night `#1F1F1F` / `#E0E1DD`, both within compression error of the values in the code.
- **Kindle's size slider** has 18 marks; the owner's setting is the 5th.

### A2. Current settings, read from the code

Apple is on `origin/apple/client` (HubKit `EpubAppearance.swift`, `BookNavigator.swift`, `BookReaderSheets.swift`, `BookReaderCorners.swift`, `PageInfo.swift`, `Comfort.swift`). Android is on `origin/claude/consolidation` (`EpubReaderState.kt`, `EpubAppearancePanel.kt`, `EpubReaderScreen.kt`, `PageInfo.kt`, `ScreenComfort.kt`). The two use the same names and defaults.

- **Defaults.**
  - Sepia theme, `fontFamily "publisher"`, which sets no override, so the book's `body style="font-family:serif"` gives Times on Apple and Noto Serif on Android.
  - Font scale 1.0 (16 px), line height 1.5, page margins 1.0, columns AUTO.
  - Publisher styles off, justified, hyphens on.
- **Typefaces.** `publisher` / `serif` / `sans-serif`, the generic families only. Apple's tiles preview SwiftUI `.serif` and `.default`, not what Readium draws.
- **Size.** 0.7 to 2.0 in steps of 0.1, with − and + buttons (Apple) or the ValueAdjuster (Android).
- **Margins.** 0.5 / 1 / 1.7 × Readium's `pageGutter`, which is 40 px on any viewport of 720 px or more (iPad, Pocket DS) and 20 px on an iPhone in portrait.
- **Spacing.** 1.1 / 1.5 / 1.9.
- **Palettes** (`EpubPagePalette` in both apps):
  - Paper `#FBFAF6` / `#282B29`
  - Sepia `#EFE2C6` / `#3E3526`
  - Night (`DARK`) `#202020` / `#DFDFD8`
  - Blue `#1D303D` / `#DCE6E8`
  - Comfort black page `#000000` / `#C9C3B6`
- **Corners, Apple.**
  - 11 pt medium, ink at 60%;
  - horizontal padding `max(layout.side, 22)`, which is 22 pt on an iPad;
  - strips `PageInfo.strip` 62 pt (34 pt when height is compact), which double as Readium's `contentInset`.
- **Corners, Android.**
  - 11 sp, `INK_ALPHA` 0.6;
  - `STRIP_DP` 26;
  - `sideInsetDp = 20 × pageMargins` (between 14 and 40). Readium's gutter at 853 px is 40, not 20, so the Android corners sit 20 dp outside the text's edge.

### A3. Readium CSS and the two-column gap

Both toolkits ship the same Readium CSS 1.0.0-beta.3: Kotlin 3.0.0 in the `readium-navigator` AAR, Swift 3.11.0 in `Sources/Navigator/EPUB/Assets/Static/readium-css`.

**What the stylesheet does:**

- `:root { --RS__colGap: 0; --RS__pageGutter: 20px; --RS__maxLineLength: 40rem; --RS__colWidth: 45em }`.
- Media queries raise `pageGutter` to 30 px at ≥ 35em, 40 px at ≥ 45em and 50 px at ≥ 75em, and switch to two columns at ≥ 60em (`colCount 2`, `maxLineLength 39.99rem`).
- `body { max-width: var(--RS__maxLineLength); padding: 0 var(--RS__pageGutter); margin: 0 auto }`. With margins set, the padding becomes `0 calc(var(--RS__pageGutter) * var(--USER__pageMargins))`.

**Why our gap is double.** `<html>` is the column container and its gap is 0. The body is split across the columns, and **each piece keeps both of the body's side paddings**. So the outer margin is P and the gap between columns is 2P, where P = `pageGutter × pageMargins`. On the iPad mini, P = 40 × 1.7 = 68 pt. The screenshots measure exactly that: 68 pt outside and 136 pt between the columns.

**What does not help:**

- `maxLineLength` doesn't limit anything at these sizes (40rem = 832 px at 130%).
- `--RS__colGap` can't be used. The same gap would also fall between the last column of one page and the first of the next, while the navigators turn the page by exactly the viewport width, so pages would drift ("You must account for this gap when scrolling", Readium's own doc comment).
- No CSS rule can give the outer edges a different margin from the middle; there is no selector for one column.

**Recipe.** Let M be the outer margin and G the gap.

- Set **P = G/2** with an RS property. RS properties go inline on `<html>`, so they override the media queries.
- Inset the page view by **H = M − G/2** on each side.
- **Swift:** in `BookNavigator.makeController`, pass `EPUBNavigatorViewController.Configuration(readiumCSSRSProperties: CSSRSProperties(pageGutter: CSSPxLength(24)), …)`, then pad the navigator's view horizontally by H in the host, with the page colour behind it.
  - Readium's `contentInset` and `navigatorContentInset` only apply top and bottom (`EPUBReflowableSpreadView.updateContentInset`), so the side inset has to be done in the host.
  - Taps in the inset never reach Readium's `didTapAt`, so the host must turn the page itself: left inset back, right inset forward.
- **Kotlin:** in `attachNavigator`, pass `EpubNavigatorFragment.Configuration(readiumCssRsProperties = RsProperties(pageGutter = Length.Px(24.0)), …)`, then give `pageHost` left and right margins in `applyPageInfo`, which already sets top and bottom. `navigatorContainer` already paints the page colour. `PageInfo.sideInsetDp` becomes H + P.
- **Margin presets.** Keep sending `pageMargins = 1.0`, and have the presets choose H instead.
- **Values.**
  - iPad and Mac: G 48, M 90, so H = 66 and P = 24.
  - iPhone in portrait: M 24, with P 12 and H 12.
  - Pocket DS: G 32, M 36, so H = 20 and P = 16.
- **Check on the iPad mini.**
  - Portrait: 744 − 180 = 564 pt, as on Kindle.
  - Landscape: (1133 − 132)/2 − 48 = 452.5 pt, as on Kindle.
  - Automatic two columns still trigger: 1001 px is at least 960 px, and the hub's injected two-column rule applies from 30em.
- **Vertical, iPad.** Change the strips and `contentInset` from 62/62 to about 84 at the top and 96 at the bottom.

### A4. Fonts

Metrics are from the font files with fontTools, at weight 400 and optical size 20. Stroke contrast compares a rendered "n" stem with the top of an "o".

| Font | x-height / em | cap ÷ x | width (char ÷ x) | thick : thin | size for Kindle's x-height | characters in 564 pt | licence | files |
|---|---|---|---|---|---|---|---|---|
| Bookerly (Kindle) | 0.488 | 1.42 | 0.90 | lower than Times | 22.0 pt | 58 (measured) | proprietary | (none) |
| **Literata** | 0.508 | 1.38 | 0.92 | **1.9** | 21.2 px | 57 | OFL 1.1 | 932 KB + 881 KB variable (opsz, wght) |
| Charis SIL (Charter) | 0.482 | 1.39 | 0.91 | 2.3 | 22.3 | 57 | OFL; reserved names "Charis" and "SIL", so ship unmodified, no subsetting | 735 KB per style |
| Source Serif 4 | 0.475 | 1.41 | 0.93 | 2.1 | 22.7 | 57 | OFL | 1.2 MB variable |
| Georgia | 0.481 | 1.44 | 0.90 | 2.7 | 22.4 | 58 | system font, not bundleable | (none) |
| Atkinson Hyperlegible | 0.496 | 1.35 | 0.88 | 1.2 (sans) | 21.7 | 60 | OFL | 53 KB per style (Next: 111 KB variable) |
| Merriweather | 0.554 | 1.34 | 0.85 | 2.1 | 19.4 | 62 | OFL | 4.5 MB |
| Times New Roman (now) | 0.447 | 1.48 | 0.89 | 2.9 | 24.1 | 59 | system font | (none) |

The method checks out against our own screenshot: it predicts 67 characters a line for Times at our measured size, and we counted 66.

**Declaring the fonts:**

- **Swift:** `fontFamilyDeclarations: [CSSFontFamilyDeclaration(fontFamily: FontFamily(rawValue: "Literata"), fontFaces: [CSSFontFace(file: <bundled roman>, style: .normal, weight: .variable(200...900)), CSSFontFace(file: <bundled italic>, style: .italic, weight: .variable(200...900))]).eraseToAnyHTMLFontFamilyDeclaration()]`.
  - System fonts need only a name: `FontFamily(rawValue: "Charter")`, `.georgia`, `.iowanOldStyle`.
  - New York needs the bare keyword `ui-serif`, which Readium would put in quotes. Test it before offering it.
- **Kotlin 3.0.0:** `EpubNavigatorFragment.Configuration` has `servedAssets` and font-family declarations (`FontFamilyDeclaration` / `MutableFontFamilyDeclaration`). Serve `fonts/.*` from the app's assets.

**Behaviour to know about:**

- Readium's font override (`readium-font-on`) covers body, p, div, li, dt, dd and inline i, em, b, strong and span. It does **not** cover h1 to h6 or `::first-letter`. So with Literata, the book's Trajan headings and drop cap stay, which is Kindle's arrangement, once the fonts decode (A5).
- Literata has true small caps (`smcp`), which matters for "IVALNIGHT APPROACHES". Atkinson doesn't, so WebKit fakes them.
- None of these fonts has Hebrew; the system draws Hebrew in its own font. Consider defaulting `lang="he"` books to Original.
- Put the OFL texts in Android's `assets/licenses` and in Apple's acknowledgements.
- Weight: `fontWeight` exists in both toolkits' preferences. Swift writes it as `font-weight` on `:root`, which works smoothly with variable Literata.

### A5. Hub fixes

**Font unscrambling.**

- **The book.** The OPF says `unique-identifier="uid"` and `<dc:identifier id="uid">25103610</dc:identifier>`, and also carries `urn:uuid:39b44fd9-3b9a-4fbc-9a39-34147f45dfb0` (the book went through Calibre). `encryption.xml` lists four fonts under `http://ns.adobe.com/pdf/enc#RC`.
- **The cause.** Adobe's key is the UUID's 16 bytes, XORed over the first 1024 bytes of each font. Both toolkits' `EPUBDeobfuscator` / `EpubDeobfuscator` take the key from `metadata.identifier`, which here is "25103610", so it is wrong.
- **Checked.** With the urn:uuid the first bytes become `OTTO`, and the fonts read as "Trajan Pro 3" Regular and Bold, and "Shift" Bold and Bold Italic.
- **Scale.** In `D:\Media\Reading\books`, 5 of 61 EPUBs have scrambled fonts and 2 fail (Light Bringer, Dark Age). Storyteller's copy of Light Bringer has the same problem.
- **Fix, in `hub/internal/reading/epub_copy.go`.** For each font listed with the Adobe or IDPF method, try the unique identifier and then every urn:uuid identifier. Use the first that gives a valid sfnt or WOFF header, write the decoded font, and drop its `EncryptedData` entry; drop `encryption.xml` if it ends up empty.

**Optional restyle:**

- **Heading sizes.** Readium's `:root[style*="readium-advanced-on"] h2 { font-size: 1.5rem !important }` has specificity 0,2,1 and flattens every h2. The hub could re-emit the book's own heading `font-size` rules as `!important` with higher specificity.
- **Indents.** Turn `text-indent: N%` into em (3.7% becomes about 1.2em). Readium's own `paragraphIndent` isn't suitable: it indents every paragraph, including the drop-cap opener.

### A6. Theme IDs and migration

- Keep stored theme IDs stable: `DARK` stays the grey, relabelled "Dim" with ink `#C8C8C2`. Add `BLACK` as "Dark", `#000000` / `#AFAFAF`.
- Point "Use system colours" at night to `BLACK`.
- Devices with Comfort's `blackPage` turned on move to `BLACK`.
- The corners use the page's ink at full strength.
- Both palettes, `EpubAppearance.swift` and `EpubAppearancePanel.kt`, change together, with their tests.
