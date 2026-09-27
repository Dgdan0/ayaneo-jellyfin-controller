# Reading lists, media removal, and comic controls

## Where to find the changes

Books → Library → Reading lists displays Kavita's server reading lists, including unpromoted lists, in ascending title/year order. Opening a year shows its numbered entries in Kavita's explicit order. Opening an issue retains that list: next/previous issue follows the list across different series; going backward opens the previous issue at its last page. The existing device-only Want to Read and custom lists remain available from their existing menus.

Live validation found the requested 19 lists from 1962–1980 and 436 entries. During implementation a 1981 list became available too: the live server now returns 20 lists and 457 entries. Nothing is hardcoded to the initial years or list count. Hub reads the supplied Kavita endpoints without modifying lists or promotion state.

The existing three-dot menus on media and individual book details now offer:

- **Remove offline copy** — removes only this profile's local video downloads or the selected book's saved EPUB, read-along audio, audiobook cache and comic pages. Server files, bookmarks and reading/watch progress remain. Comic page caching still does not imply that an entire issue has been downloaded.
- **Delete from server…** — opens a file preview, followed by a separate confirmation with Keep media selected first. It removes server files and the corresponding catalogue record; local copies remain. Movie/TV deletion uses Jellyfin. Reader deletion resolves files from Kavita/Storyteller and explicitly configured server folder mappings. A comic-series detail deletes the listed issues in that series; a book detail includes its listed formats/editions. Open individual books inside an author/series collection to delete those books.

Offline video menus expose both actions too. Server deletion needs a working Hub connection. Offline removal does not contact the Hub. Retained library shelves refresh after a server mutation.

## Current comic/manga controls

| Key | Reader action |
| --- | --- |
| A | Next page/region with controls hidden; activate selected control when visible |
| R1 / L1 | Next / previous page or region |
| D-pad / left stick | Move between pages/regions; navigate controls while the menu is visible. Left/right respects right-to-left reading direction |
| X | Toggle whole-page versus reading in thirds |
| Y | Open Navigator, focused on the page slider |
| L2 / R2 | Zoom in / out immediately |
| Start | Show/hide controls |
| Select | Reload the current page |
| B | Close an options panel first, otherwise hide visible controls, otherwise exit |

These comic mappings were retained. The D-pad does not currently provide free panning of a zoomed page. The EPUB/read-along 600 ms chapter hold does not apply to comics.

## Implementation and operating limits

Removal requires the control scope, and reader removal additionally requires reading. A short-lived confirmation is bound to the authenticated device and selected profile, consumed once, and checked again against current upstream metadata and file identity before mutation. Client-supplied filesystem paths are never accepted. Reader deletion rejects directories, path traversal, linked files, missing mappings, and files shared with an unselected Storyteller book. Only enumerated regular files are removed; library folders are never recursively deleted by Hub. Storyteller also cleans its own managed book assets through its native delete endpoint.

If an upstream deletion fails after file removal has begun, the app reports possible partial completion and refreshes the library; it does not automatically retry. Download-manager monitoring remains unchanged, so a monitored title may be reacquired. Existing archives and backups elsewhere are not searched or removed.

Configuration: `server.media_removal_roots` maps a service/container prefix to a local library directory. The installation mappings were derived from the actual Docker bind mounts. Other deployments must provide their own mappings; the default is empty.

Validation and deployment status are recorded in `QUARTERMASTER_IMPLEMENTATION_STATUS.md`. Tests use temporary files and fake provider endpoints for destructive actions. Real-library checks use only catalogue reads and deletion previews.
