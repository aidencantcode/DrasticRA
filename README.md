# Drastic RA

Companion app that watches DraStic (unmodified) via root, to bring RetroAchievements to it.

## Status
- Step 1 (this build): find DraStic's 4 MB DS main RAM block in its process memory.
- Step 2: rcheevos (rc_client) reading that RAM at ~60 Hz, RA login + ROM hash.
- Step 3: overlay popups for unlocks / progress.

## Build (no PC needed)
1. Create a GitHub repo, upload the contents of this folder (keep the `.github` folder).
2. Actions tab -> "Build APK" runs automatically (or Run workflow).
3. Open the finished run -> Artifacts -> `DrasticRA-apk` -> install the APK.

The APK is signed with the bundled `app/dsra.keystore` so later builds update in place.

## Use
Start DraStic, load a game, open Drastic RA, tap **Scan DraStic RAM**, grant root, then
**Share / copy log** and send the log back.
