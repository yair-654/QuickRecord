# QuickRecord

**Start recording video in one second, straight from the lock screen, without unlocking.** Tap a Quick Settings tile, or use a volume-button gesture, optionally even with the screen off.

Every recording is saved in crash-safe pieces, fingerprinted, and documented, so you can show later that it hasn't been changed.

Built for **GrapheneOS** and Android 14 or newer. No internet access, no accounts, no tracking.

Made in Israel 🇮🇱

---

## Features

**Start fast**
- **Quick Settings tile:** one tap on the lock screen starts recording, a second tap stops and saves. The phone stays locked.
- **Volume-button shortcut:** start and stop without looking. Choose your gesture: 3× or 2× Volume Down, 3× or 2× Volume Up, either button 3×, or Up and Down together. Your volume buttons keep working normally.
- **Works with the screen off** (optional).
- **Big record button** in the app, with pause, resume and flashlight controls.

**Record safely**
- **Crash-safe pieces:** long recordings are saved as files of about 5 minutes each (you choose the length), one straight after the other with no gap. If the phone crashes or the battery dies, only the last few minutes are at risk.
- **Safety stops:** stops and saves before the battery dies or the storage fills up, or after a time you choose.
- **Silent notification** with Stop, Pause and Light buttons.
- **Discreet mode:** no vibration, and the notification and tile just say "QuickRecord".

**Evidence you can rely on**
- **Fingerprints:** a SHA-256 fingerprint of every file, taken the moment it's saved, before any other app can touch it. If even one byte changes later, the fingerprint no longer matches.
- **Chained pieces:** each piece records the fingerprint of the one before, so a missing or swapped piece is noticed.
- **Evidence record:** a small file for each recording, with when it started and ended (in UTC and local time), where, which phone and system version, the settings used, pauses, and how it ended.
- **Verify:** checks any time that the files are unchanged.
- **Export evidence:** the videos, their records and a list of fingerprints in one zip, ready to share or archive. Several recordings can go in one zip.
- **Untouched files:** recordings are never altered after they are sealed.

**Your recordings**
- **A list with thumbnails:** play, share, rename, verify, export or delete.
- **Select several:** press and hold to select, then export, verify, share or delete them together.
- **Location** (optional) is saved inside the video and its record.

**Quality you choose**
- **Video:** back or front camera, 720p, 1080p or 4K, 30 or 60 fps, three quality levels, H.264 or HEVC, and stabilization.
- **Good defaults:** every setting starts at a recommended value and explains what it does. "Reset settings" puts them all back.

## Install

1. Download the latest `QuickRecord-x.y.apk` from **[Releases](../../releases)**.
2. Open it on your phone and allow installing from that app if Android asks.
3. Open QuickRecord. The **Finish setting up** card walks you through it:
   - **Camera and microphone:** allow.
   - **Notifications:** allow.
   - **Quick Settings tile:** tap **Add**.
   - **Button shortcut:** tap **Set up** (see below).
   - **Location** (optional).

QuickRecord reads the motion sensor only when a recording starts, so landscape videos are saved the right way up. On GrapheneOS, allow the **Sensors** permission for this.

## The button shortcut

Android only lets an app notice the volume buttons through **Accessibility**. QuickRecord uses it for nothing else: it can't read your screen or what you type.

1. In QuickRecord: **Settings › Button shortcut › Open Accessibility settings**, then switch on **QuickRecord button shortcut**.
2. If Android says **"Restricted setting"** (normal for apps installed from a file):
   - Go to **App info › ⋮ › Allow restricted settings**.
   - Go back and switch the shortcut on.
3. Pick your gesture, and use **Try it** to check it's noticed. Nothing is recorded while that screen is open.

| When | Works? |
|---|---|
| Screen on, locked or unlocked, even while music plays | ✅ |
| Screen off | ✅ if **Works with the screen off** is on |
| Screen off while music or a video plays | ❌ the playing app gets the buttons |
| Screen off during a phone call | ❌ the call gets the buttons |
| After a restart, before you unlock once | ❌ Android allows no saving until then |

**How the screen-off mode works:** while the screen is off, QuickRecord tells Android it controls the volume of a "remote speaker", the same way casting apps let the volume buttons control a TV. Android then passes volume presses to it. As soon as the screen turns on, it lets go and the buttons work normally again. Nothing runs between presses, so it uses close to no battery.

When a recording starts with the screen off, the screen lights up for a moment over the lock screen. Android only gives camera access to an app that's on screen at that instant. The phone stays locked.

## Where your files are

| What | Folder |
|---|---|
| Videos | `Movies/QuickRecord/` |
| Sound-only recordings | `Recordings/QuickRecord/` |
| Evidence records | `Documents/QuickRecord/` |
| Exported evidence zips | `Download/QuickRecord/` |

Android only lets an app put video files in `Movies/`, so the evidence records are kept in `Documents/`. **Export evidence** puts them together. You can rename the folder in Settings.

File names look like `VID_20261125_220512_p01.mp4`: the date and time recording started, then the piece number.

### Dates and times

Each file's date in the gallery is the moment that piece began. Its evidence record lists every start and end time twice: in UTC and in the phone's local time with its offset, for example `2026-11-25T22:05:12+02:00`. It also records the time zone and whether the phone's clock was set automatically. Recordings are never altered after they are sealed.

### Checking the fingerprints yourself

Unzip an evidence export on a computer and run, in that folder:

```
sha256sum -c SHA256SUMS.txt              # Linux
shasum -a 256 -c SHA256SUMS.txt          # macOS
Get-FileHash <file> -Algorithm SHA256    # Windows PowerShell
```

Every file should say **OK**, and match the `sha256` values in its `REC_….json` record.

> QuickRecord helps show that a recording hasn't changed and when, where and with which phone it was made. Whether a court accepts a recording depends on the country and the case; this isn't legal advice.

## Privacy

- **No internet permission:** the app can't send anything anywhere.
- **Everything stays on your phone** until you share it.
- **Nothing is backed up or transferred** to other devices.
- **Permissions:** camera, microphone, notifications, location (optional), the volume-button accessibility service (optional), media access (optional, only to show recordings made before a reinstall), and the Sensors permission on GrapheneOS (for landscape videos).

## Problems and ideas

Open an issue: **[Report a problem](https://github.com/yair-654/quickrecord/issues)** (also under Settings › Help in the app).

The app keeps a problems log under **Settings › Help › Problems log**, which helps explain what went wrong.

## Building

Every push to `main` builds a signed release APK with GitHub Actions (Actions tab › latest run › **QuickRecord-apk**). Pushing a `v*` tag also creates a GitHub Release with the APK attached.

Signing uses four repository secrets: `QR_KEYSTORE_BASE64`, `QR_KEYSTORE_PASSWORD`, `QR_KEY_ALIAS` and `QR_KEY_PASSWORD`.

Built with Kotlin and Jetpack Compose (Material 3). Requires Android 14 (API 34) or newer; targets API 37.

## License

Copyright (c) 2026 yair-654

QuickRecord is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

QuickRecord is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU General Public License](LICENSE) for more details.

## Credits

Icons: [Material Icons](https://fonts.google.com/icons) by Google, Apache License 2.0.
