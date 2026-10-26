# Drastic RA

Companion app that watches DraStic (unmodified) via root and brings RetroAchievements (softcore) to it.

## How it works
- A root helper (`RootServer`) finds DraStic's 4 MB DS main RAM and serves 4 KiB pages over loopback.
- rcheevos (`rc_client`, fetched by the workflow at build time) runs in the app at 60 Hz on that RAM.
- Unlocks, progress and "game loaded" show as liquid-glass popups over DraStic (root screenshot as backdrop).
- Achievements earned while you play are celebrated the next time you open the app.

## Build (no PC needed)
1. Upload the contents of this folder to a GitHub repo (keep the `.github` folder).
2. Actions -> "Build APK" runs on push. When done, grab the APK from Releases.

## Use
1. Menu (hamburger or START) -> Account -> log in. Add your Web API key for the home screen.
2. Library -> set your ROM folder. Tap **Start watching**, then launch a game in DraStic.

Gamepad: L1/R1 switch tabs, START opens settings, B goes back. The moving highlight follows the D-pad.
