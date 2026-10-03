# My TV guide-style library

Issue: #10. Marcel clarified that "Catch up" means Continue watching, not replaying past broadcasts.

## Outcome and acceptance
- Keep Favourites, Continue watching and History accessible above the content.
- Replace horizontal favourite strips with full-width, vertically scrolling rows containing readable titles and artwork fallbacks.
- Offer All, Channels, Movies and Series filters; episodes belong with Series.
- Keep channels, movies and series separated in the All favourites view.
- Preserve channel playback, media details, resume/history playback, favourite removal and clear-history callbacks.
- Preserve existing locked-favourite presentation and all upstream entitlement and parental restrictions.
- Support TV remote focus and compact touch screens without placing title text below clipped poster strips.

## Architecture and verification
Change only LibraryScreen and its private presentation helpers in SearchLibraryScreens.kt. Retain existing data sources, models and view-model callbacks. Give the scrolling content an explicit remaining-height viewport and reset its position when switching tabs or filters. Keep SearchScreen unchanged.

Run debug build, unit tests, lint and targeted device tests. Cover type filters, title visibility, long-list scrolling and callbacks. Independently review the diff, then publish a PR and test APK for Marcel. No merge, deployment, production keys, billing/licensing changes or new programme catch-up feature.
