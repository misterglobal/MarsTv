# Guide refresh throttling

Issue: #12

## Outcome and acceptance
- Reopening MarsTV should immediately reuse a complete stored catalog instead of repeatedly downloading the playlist and guide.
- Automatic refresh occurs only when the stored catalog is at least three days old.
- If an automatic refresh is interrupted or fails while an older complete catalog exists, reopening the app reuses that catalog for a six-hour retry cooldown instead of immediately downloading again.
- A missing or interrupted first-time catalog still refreshes automatically so setup can recover.
- The existing Refresh action remains an explicit force refresh and bypasses the age check.
- Account switching, playback, guide browsing, Free/Pro behavior, and all licensing logic remain unchanged.

## Architecture and verification
Keep the persisted Room import timestamp as the freshness source of truth and save the last automatic attempt per account in the existing encrypted local state. Centralize the decision in a small pure function. Manual refresh still calls the forced path and bypasses the cooldown. Add boundary unit tests, then run the debug build, unit tests, lint, and the existing targeted emulator suite. No backend, provider, billing, signing, deployment, or database-schema changes.
