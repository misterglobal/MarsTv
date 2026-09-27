# Live TV guide

The TV layout uses compact channel rows, a fixed channel column, and a shared two-hour timeline. Earlier, Now, and Later move the window; pressing Right at the last programme advances it. Categories open in a collapsible sidebar. The category button displays only the active category (or All channels). Gaps in provider data retain their actual place on the timeline.

## Remote and playback

TV screens enter keyboard/remote input mode and establish child focus on opening or returning to the window. An existing focused control is preserved. OK activates the focused control on the first press; users do not need a preliminary OK to establish focus. Phones retain normal touch behavior.

- Press Left from a channel name to open categories without navigating back to the toolbar. The channel header shows this shortcut on TV.
- Opening categories focuses the selected category and scrolls it into view. Up/Down browses; OK selects a category and focuses its first channel.
- Back or Right closes categories and restores the previous guide focus without changing playback. Back while a parental PIN dialog is open dismisses the dialog first.

- Select a live channel/programme to watch. With the `picture_in_picture` entitlement, it starts in the guide preview.
- Select the playing channel again, or select the preview, to open fullscreen. Back from fullscreen live playback returns to the guide with the same stream still playing in the mini player, including for Free users and channels opened from search or favourites.
- Highlighting programmes does not change the stream. Selecting upcoming programmes opens details; eligible past programmes offer catch-up.
- The heart beside each channel toggles favourites. Stop closes the preview. Leaving Live TV stops playback; backgrounding the app pauses it.
- Free users retain fullscreen playback and current/next guide access. Full timeline access and starting directly in preview use separate feature grants; minimizing an already-playing live channel does not require a Pro preview grant.

The player is owned by the root UI and positioned over a placeholder in the guide. Resizing between preview and fullscreen keeps the same ExoPlayer instance. Guide scroll/category state remains composed during fullscreen live playback.

## Guide import

The XMLTV importer previously retained the first listings up to its per-channel cap. Historical listings could exhaust that cap before reaching current/upcoming shows. Retention now reserves three quarters of each channel's budget for the nearest current/upcoming listings, while keeping recent history and sharing unused capacity. Selection is bounded per channel and emitted in database-sized batches after parsing.

Existing imported schedules need an account refresh to use this retention policy. The app cannot display schedules omitted by the provider. The number of retained programmes is still capped, so the provider's entire date range is not guaranteed.

## Validation

Unit tests cover timeline clipping/gaps/overlaps, retention order and capacity, and the specific preview feature grant. Instrumentation tests cover XMLTV import and a synthetic TV guide with upcoming details. Real provider playback and remote focus transitions should also be checked on the target TV.

The API 25 navigation smoke test exercises D-pad entry, Left to open categories, selected-category focus, Back to restore channel focus, category selection without tuning, and upcoming-programme details. The signed navigation test APK still requires remote testing on the physical Firestick.

The API 25 single-press regression test starts in touch mode and verifies initial TV focus, exactly one action per OK press, and focus after replacing a screen. It also checks that the opening press does not activate the next screen.
