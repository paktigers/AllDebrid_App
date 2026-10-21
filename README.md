# AllDebrid Android App: How to build the APK

**Updating the app without uninstalling or recreating the repository: read `UPDATING.md` first.**

An Android app (WebView interface + native bridge + built-in ExoPlayer video player) that brings the
AllDebrid PowerShell tool to your phone. You build the APK yourself in about 10 minutes.

## Player controls (v2.3)

- **Double-tap** the right side of the screen = +10 seconds, the left side = -10 seconds.
- **Swipe up / down on the right half** = volume slider. **On the left half** = brightness slider.
- **Mute button**, **aspect ratio** (Fit, Fill, Zoom, 16:9, 4:3, 21:9).
- **Options menu (three dots):** **Media info** (file name, size, duration, container, video / audio / subtitle details,
  decoder mode), playback speed, decoder choice and "open in another player".
- All player icons are drawn by the app itself, so they look the same on every phone (no emoji).
- **Pinch to zoom** the picture (up to 5x, drag with two fingers to move it). **Reset zoom** is in the Options menu.
- **Screen lock:** the lock icon in the bottom bar ignores all touches; tap the screen and press the unlock icon to unlock.
- **Subtitle style:** size, colour, background and edge. **Subtitle delay:** shift subtitles later or earlier in 0.1 s steps
  (earlier works for loaded subtitle files; embedded subtitles can only be delayed). Loads .srt, .vtt and .ass files with a
  choice of file encoding (Arabic / Urdu, Western, Cyrillic).
- **Resume:** the player remembers how much you watched and asks "Resume from 12:34" or "Start over".
- **Chromecast:** tap the cast icon in the top bar, pick your TV, and the video plays on the TV (the TV
  downloads the video directly from AllDebrid). Chromecast supports MP4 (H.264/AAC), WebM and HLS best;
  MKV, HEVC or DTS files may not play on the TV. The phone and the TV must be on the same Wi-Fi network.
  Needs Google Play services on the phone.

## App features (v2.5)

- **Clipboard detect:** copy a link or magnet, open the app, and it offers to add or generate it (can be switched off in Settings).
- **.torrent files:** open a .torrent file from your file manager or browser (or Share it) and the app takes it directly.
- **Usage & quota** (More menu): premium days left, quota bars for every limited host, and a 14-day graph of the links you generated.
  AllDebrid does not publish a traffic history, so the graph is built from your own history in the app.
- **App lock** (Settings): protect the app with your phone's lock (fingerprint, face, PIN or pattern) or an app PIN. It locks when the app
  opens and after 30 seconds in the background, hides the app in the recent-apps list, and blocks screenshots while it is on.
- **Torrent filters fixed:** All / Ready / Active / Error respond reliably (answers can no longer overwrite each other and the
  list is no longer redrawn under your finger).

## What's new in v2

- **Tap-to-open menus** (no checkboxes by default): tap a torrent, a generated link or a history entry and
  its actions open right under it (Download, Choose files, Stream, Show links, Copy, Share, Restart, Delete...).
  "Select multiple" is still available for bulk actions.
- **Built-in streaming player** for premium links and torrent files: play/pause, previous/next,
  -10s / +10s, seek bar, subtitles (embedded tracks or your own .srt/.vtt/.ass file), audio track,
  quality, playback speed and decoder choice. A button opens the video in VLC / MX Player if a format is not supported.
- **AllDebrid logo** as the app icon and inside the app.
- Fixes: "Stop waiting" on torrents now opens the Torrents tab and keeps watching the torrent; the
  Torrents filters no longer blank the list; errors are shown on screen and in Help > Error log.

## Method A: GitHub (easiest, nothing to install)

1. Create a free account on https://github.com and log in.
2. Tap "+" > **New repository**, pick a name (e.g. `alldebrid-app`), then **Create repository**.
3. Extract this zip. In the repository choose **uploading an existing file** and drag in
   **everything inside the AllDebridApp folder** (`app`, `.github`, `build.gradle`, `settings.gradle`,
   `gradle.properties`, `.gitignore`). The `.github` folder must be uploaded too (it holds the build workflow).
   If hidden folders can't be dragged in, use **Add file > Create new file**, type
   `.github/workflows/build-apk.yml` as the name and paste the contents of that file.
4. Tap **Commit changes**.
5. Open the **Actions** tab. "Build APK" runs for about 3 to 6 minutes
   (if it doesn't start, open it and tap **Run workflow**).
6. When it shows a green tick, open the run and download **AllDebrid-APK** under **Artifacts**
   (a zip that contains `app-debug.apk`).
7. Send the APK to your phone and install it.

## Method B: Android Studio

1. Install Android Studio: https://developer.android.com/studio
2. **Open** the `AllDebridApp` folder and wait for Gradle sync to finish.
3. **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Installing

1. **Uninstall the old version first.** Debug APKs built on GitHub are signed with a different key each
   time, so Android will not install the new one over the old one. (Your saved API keys and history are
   stored inside the app, so add your key again after installing.)
2. Tap `app-debug.apk`, allow "Install unknown apps" if asked, and tap **Install anyway** if Play Protect warns.
3. Open the app and add your API key (key button at the top, or More > Account), or use **Browser PIN login**.

## Build requirements

The project uses Android Gradle Plugin 8.7.3, Gradle 8.9 (set in the GitHub workflow), compileSdk 35 and Media3 1.7.1.
Android Studio users need a recent version (Ladybug or newer).

## Using the app

- **Generate:** paste hoster links and magnets in one box (or pick a .txt / .torrent file). Tap **Generate**.
  In the results, **tap a file** for Download / Stream / Direct link / Original link / Share, or use the buttons
  on top for "Download all", "Play videos" and "Copy all links".
- **Torrents:** tap a torrent for Download all, Choose files, Stream, Show links, Restart, Delete.
  Torrents that are still downloading on the AllDebrid server show live progress, speed and seeders.
- **Stop waiting:** if a torrent is not cached, AllDebrid downloads it on its own servers first. You can tap
  **Stop waiting** at any time; it keeps downloading and you will be notified (red dot on the Torrents tab) when ready.
- **History:** separate lists for hoster links and torrents, with the same tap menu.
- **Player controls:** tap the screen to show or hide the controls. Top bar: **CC** (subtitles, including
  "Load subtitle file"), **Audio**, **Quality**, **Speed**, and **↗** (open in another player).
- **Downloads:** run through Android's Download Manager (notifications, automatic resume).
  Files are saved in `Downloads/AllDebrid/`.

## About quality, subtitles and audio

- **Subtitles / audio:** all tracks embedded in the file (for example in MKV) are listed. You can also load
  an external subtitle file.
- **Quality:** AllDebrid does not transcode ordinary files or torrents, so those have one quality (the original).
  When a link offers several qualities (for example streaming sites), they are listed under Quality.
- **Audio and video decoders:** the player includes FFmpeg software decoders (NextLib), so audio such as DTS,
  DTS-HD, AC3, E-AC3 (Dolby Digital Plus), TrueHD, FLAC, ALAC, Opus, Vorbis, MP3 and AAC, and video such as
  H.264, HEVC (H.265, including 10-bit), VP8 and VP9 can be decoded even when your phone has no hardware decoder.
- **Automatic fallback:** by default the phone's hardware decoders are used first (smooth and battery friendly).
  If a decoder error happens (for example "Decoding failed"), the player automatically switches to software
  decoding and continues from the same position.
- **Decoder button:** in the player's top bar you can switch manually between "Hardware first" and
  "Software first". Software decoding of very high resolutions (4K HEVC) can stutter on weaker phones and uses
  more battery; if so, switch back to Hardware first, or use the **↗** button to open the video in VLC / MX Player.
- **Size and license:** the FFmpeg decoders add roughly 15 to 25 MB to the APK. They are GPL-3.0 licensed: fine
  for your own use, but if you share the APK publicly you must follow the GPL terms.

## Good to know

- Your API key is stored inside the app on your phone. Do not share it with anyone.
- Direct links expire after a while; the app creates a fresh one when you download or play from History.
- No speed limit, auto-extract or aria2 export on Android (these exist only in the PowerShell script).
- This is a debug APK. If the build fails, check the Actions error log (or Android Studio's message).
