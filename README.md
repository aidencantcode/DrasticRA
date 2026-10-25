# Drastic RA

Companion app that watches DraStic (unmodified) via root and brings RetroAchievements (softcore) to it.

## How it works
- A root helper (`RootServer`) finds DraStic's 4 MB DS main RAM and serves 4 KiB pages over loopback.
- rcheevos (`rc_client`, fetched by the workflow at build time) runs in the app at 60 Hz on that RAM.
- Unlocks, progress and "game loaded" show as Xbox-360-style popups over DraStic.

## Build (no PC needed)
1. Upload the contents of this folder to a GitHub repo (keep the `.github` folder).
2. Actions -> "Build APK" runs on push. When done, grab the APK from Releases.

## Use
1. Open the menu (hamburger, or START on the gamepad) -> Account -> log in.
2. Optional: paste your Web API key to fill the home screen with your games and achievements.
3. Set your ROM folder, then tap **Start watching** and launch a game in DraStic.

Gamepad: L1/R1 switch tabs, START opens settings, B closes them.
