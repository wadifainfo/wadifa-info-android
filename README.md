# Wadifa Info for Android

Free Android app for public-sector job competitions (concours) in Morocco, in Arabic and French: open notices with deadlines, search by diploma and city, deadline reminders, past exam papers and practice QCM. No account needed.

It reads the free public API of [Wadifa Info](https://www.wadifa-info.com/) ([API docs](https://www.wadifa-info.com/en/api)).

## Build

```
./gradlew assembleFdroidRelease
```

Requires JDK 17. Two flavours exist:

- `fdroid`: no ads and no proprietary components.
- `play`: the Google Play build, with AdMob banners.

## Licences

- Code: GPL-3.0-or-later, see `LICENSE`.
- Bundled practice questions and exam data: CC BY 4.0, Wadifa Info.
- IBM Plex fonts: SIL Open Font License, see `docs/licenses`.
- The name "Wadifa Info" and the logo are not covered by these licences.

Independent project, not affiliated with any Moroccan administration.
