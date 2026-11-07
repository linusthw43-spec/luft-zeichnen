# Luft Zeichnen ✋🌈🤚

Android-App für den viralen Hand-Tracking-Effekt: Halte beide Hände vor die Kamera –
zwischen deinen Händen erscheint ein buntes „Wärmebild“-Band mit schraffiertem Streifen,
Tracking-Boxen und `x:… y:…`-Koordinaten, das deinen Händen in Echtzeit folgt.

## 📲 APK herunterladen

**[➡ LuftZeichnen.apk herunterladen](https://github.com/linusthw43-spec/luft-zeichnen/releases/latest/download/LuftZeichnen.apk)**

1. Den Link oben auf dem Handy öffnen – die APK wird heruntergeladen.
2. Die Datei öffnen. Falls Android fragt: „Installation aus unbekannten Quellen“ für den Browser/Dateimanager erlauben.
3. App öffnen und die Kamera-Erlaubnis geben.

Jede Änderung am Code baut automatisch eine neue APK (GitHub Actions → *Releases*).
Neue Versionen lassen sich einfach über die alte installieren.

## So funktioniert's

- **Band:** Zeigefinger und Daumen beider Hände bilden die vier Ecken. Finger auseinander = breiteres Band.
- **Filter-Knopf (links):** Thermal → Regenbogen → Negativ → Röntgen → Neon → Glitch
- **📸** Foto speichern (Galerie → *Pictures/LuftZeichnen*)
- **⏺** Video aufnehmen (Galerie → *Movies/LuftZeichnen*) – ideal für Reels/TikTok
- **🔄** Front-/Rückkamera wechseln
- **Aufs Bild tippen** blendet die Knöpfe aus (für saubere Bildschirmaufnahmen)

## Technik

- Kotlin, CameraX, [MediaPipe Hand Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker) (läuft komplett offline auf dem Handy)
- Mindestens Android 8.0
- Selbst bauen: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
