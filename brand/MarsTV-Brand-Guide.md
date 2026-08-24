# MarsTV identity

## Positioning

MarsTV is a premium, provider-neutral television player that brings a user's authorized IPTV accounts into one familiar cable-style experience.

**Tagline:** Your channels. One orbit.

## Logo idea

The logo combines three signals:

- A coral planet for the Mars name
- An orbital ring for multiple accounts and content types gathered into one interface
- A strong geometric `M` that remains recognizable at app-icon size

Use the horizontal wordmark for onboarding and marketing. Use the square planet mark for the Android launcher icon and profile-independent app avatar.

## Colour system

| Role | Name | Hex |
| --- | --- | --- |
| Main background | Orbital Midnight | `#070A12` |
| Cards and navigation | Deep Surface | `#111827` |
| Raised or focused cards | Elevated Navy | `#1A2333` |
| Primary action | Mars Coral | `#FF5A4F` |
| Secondary accent | Orbit Violet | `#7C5CFF` |
| Main text | Signal White | `#F8FAFC` |
| Secondary text | Satellite Grey | `#94A3B8` |
| Live indicator | Broadcast Green | `#34D399` |

Mars Coral should identify primary actions, focus, favourites, and brand moments. It should not cover large backgrounds. Orbit Violet distinguishes profiles, replay, and secondary selection states.

## Typography

Use Inter where brand fonts can be bundled. Use the Android system sans-serif in the APK to keep the first build small and reliable. Headlines use heavy weights with tight spacing. Guide information uses medium and regular weights for fast scanning from a distance.

## Interface principles

- Live television opens to the guide, not a promotional homepage.
- Remote focus must always be visible with a coral outline and raised surface.
- Channel names and programme times take priority over artwork.
- Movies and series can use poster-led layouts because browsing behaviour is different from live TV.
- Locked categories remain hidden from aggregate, search, and library views until the profile PIN unlocks them.

## Asset locations

- `brand/marstv-logo.svg`: horizontal brand mark
- `brand/marstv-icon.svg`: general square icon
- `app/src/main/res/drawable/ic_launcher_foreground.xml`: Android adaptive-icon foreground
- `app/src/main/res/drawable/tv_banner.xml`: Android TV launcher banner
