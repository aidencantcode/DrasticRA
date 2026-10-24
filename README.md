# Drastic RA

Companion app that watches DraStic (unmodified) via root and brings RetroAchievements (softcore) to it.

## How it works
- A root helper (`RootServer`) finds DraStic's 4 MB DS main RAM and serves 4 KiB pages over loopback.
- rcheevos (`rc_client`, fetched by the workflow at build time) runs in the app at 60 Hz on that RAM.
- Unlocks and progress show as an overlay popup at the top of the screen.

## Build (no PC needed)
1. Upload the contents of this folder to a GitHub repo (keep the `.github` folder).
2. Actions -> "Build APK" runs on push. When done, grab the APK from Releases.

## Use
1. Start DraStic and load a game.
2. Open Drastic RA, enter your RA username + password (password only the first time; a token is saved),
   set the ROM folder, tap **Start watching**.
3. Grant "All files access" when asked, then go back to DraStic and play.

Diagnostics: **RAM scan** / **Live test** / **Share log**.
