---
name: Feature or change
about: Anything new or changed. A hub change must say what each app has to do.
labels: enhancement
---

## What and why

<!-- What the person using the app gets, in a sentence or two. Link a plan file if there is one. -->

## Hub

<!-- Write "No hub change." if there is none. Otherwise fill in every line. -->

- **Endpoints:** `METHOD /v1/...` (new or changed)
- **Request:** parameters and body fields
- **Response:** new or changed fields, with a short example
- **Rules the hub now decides:** labels, thresholds or ordering the apps must not re-implement
- **Scope and rate budget:**
- **Older apps:** what an app that has not been updated sees (changes are additive only)

## Android

<!-- What the Android app must do, or "Not needed: <reason>." -->

## Apple

<!-- What the Apple app must do, or "Not needed: <reason>." -->

## Progress

<!-- Tick a line when that part is done and verified; put the commit beside it. Delete lines that do not apply. -->

- [ ] Hub: implemented with tests
- [ ] Hub: deployed on the media PC
- [ ] Android: done and verified
- [ ] Apple: done and verified
