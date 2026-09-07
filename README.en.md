# ya-photo-frame

A photo-frame screensaver for Android TV. Shows photos and videos from a
Yandex Disk folder shared by a public link. No account on the TV is needed.
Slides are shown without stretching on a blurred background, with a slow
drift; portrait photos are paired; videos play muted. Configured from a phone.

[Русский](README.md) · [How storage works](docs/storage.en.md) · [Flowchart](docs/flow-1.4.en.pdf)

It is an ordinary `DreamService`: it starts by itself when the TV is idle and
goes away on a remote button. Kotlin, `minSdk 26`. Tested on a Xiaomi Mi TV P1
43" (Android 11) with a library of six thousand photos.

## Install

1. Download the APK from [releases](https://github.com/nikallass/ya-photo-frame/releases)
   and install it on the TV (USB stick, "Downloader", browser — developer mode
   is not needed, only permission to install from unknown sources). Google
   Play Protect may warn that the app was not verified — that is normal for any
   APK outside the store; tap "Install anyway".
2. Settings → Device Preferences → Screensaver → "Фоторамка". There you also
   set after how many idle minutes it starts.
3. Start the screensaver. A hint with a QR code appears — point a phone on the
   same network at it, the control page opens.
4. On Yandex Disk: the photo folder → "Share" → copy the link of the form
   `https://disk.yandex.ru/d/…`. Paste it on the "Настройка" (Setup) tab and
   press "Задать папку" (Set folder).

A large library takes a couple of minutes to walk; the first photo found is on
screen during the walk already. The hint can be shown again with ↓, and ↑
turns on sound in videos.

The page and the app are in Russian.

## How it works

- **The slide** is never enlarged (Disk copies are 1280 px), portrait ones fit
  86 % of the height. It is shifted towards the golden-ratio point by a share
  of the free room up to the margin and during the show slowly drifts to or
  from it and grows a little; the path and growth are set per twenty seconds
  and scaled by the show time; the edge margin is never crossed. Every show of
  a photo is a different quarter and direction. The background is a heavily
  blurred, darkened copy of the photo. Transition — a 1.5-second dissolve; a
  slide stays for twenty seconds.
- **Order** is not merely random: half of the recently shown is excluded, the
  rest is weighted by how long ago it was shown. Photos uploaded to Disk in
  the last two weeks or seen by the frame for the first time (for example from
  a freshly selected subfolder) come up noticeably more often. The capture
  date does not matter. Show history survives a reboot.
- **Small stuff** — thumbnails, crops, messenger pictures — is skipped: by
  default anything narrower than a quarter of the screen is not shown. The
  threshold is adjustable.
- **The index** is built over the whole folder with subfolders and refreshed
  at most every 1.5 hours. What is deleted on Disk disappears from the frame
  together with its copies in storage; what is added enters the queue at
  once. On the "Папки" (Folders) tab you can tick which subfolders to show.
- **Storage** — one folder where the frame keeps everything it downloaded:
  photo copies (Disk previews, 1280 px on the long side, ~200 KB) and whole
  videos. The place — TV memory or a USB flash drive — is one setting. The
  capacity is either a slider (2 GB by default for TV memory) or "by free
  space": then the same slider becomes the reserve the frame leaves alone and
  takes the rest. The photo library gradually settles entirely on the TV and
  is shown without network; when space runs out the least recently shown is
  evicted, videos before photos. Network gone — the frame shows from storage
  and retries; expired Disk links refresh themselves. Details in
  [docs/storage.en.md](docs/storage.en.md).
- **Video** has three outcomes: into storage, stream, skip. A file heavier
  than "File no heavier than" (2 GB) is skipped at once without downloading.
  Then the frame reads the file header with two small requests: duration,
  bitrate and codec profile; what the TV cannot decode (for example 10-bit
  HEVC from a camera) is marked and not downloaded. A video that fits the
  storage is downloaded whole, one at a time, and plays from disk without
  stutter; until it has finished downloading the show goes past it. A video
  that does not fit even after eviction is streamed if its bitrate is not
  above the network speed and skipped if it is. The frame measures the network
  speed itself over the last three downloads; you can type your own. "Don't
  keep lighter than" — two thresholds, for photos and videos: what is lighter
  is not kept but downloaded anew for every show (videos — streamed). Sound is
  off (toggled from the remote), the duration is capped at two minutes. What
  the decoder paints as green stripes instead of a picture is skipped too, by
  the frame itself.
- **Flash drive.** Insert a drive (exFAT is best, NTFS or FAT32; FAT32 cannot
  hold files over 4 GB; if the TV offers to "set up as internal storage" —
  decline and choose "removable storage") and select it as the storage place
  on the page or in the app. Files live in
  `Android/media/ru.dvedev.me.yaphotoframe/Фоторамка/`: photo copies in
  `previews`, videos in `videos` as an ordinary tree like on Disk — the drive
  can be taken out and shown anywhere. What is deleted on Disk is removed from
  the drive too; changing the folder clears storage in both places. Drive
  removed — the frame temporarily lives in TV memory and does not go empty;
  returned — it continues with the same files. Download progress is visible in
  the diary and on the "Состояние" (State) tab.
- **Portrait** photos that follow each other are placed side by side.
- **Over the slide** — a clock (slowly drifts and changes corner to avoid
  burn-in) and the EXIF capture date in the corner of the photo; both can be
  turned off.

## Control

**From a phone** — `http://<tv-address>:8099`, the address is shown in the
hint. The page answers while the screensaver runs or the app is open on the
TV. Tabs: "Настройка" (Setup: the link, toggles and sliders by section —
applied immediately; every setting has a hint line and an ⓘ with details),
"Папки" (Folders: a tree of subfolders with checkboxes; branches with
something ticked inside are highlighted), "Состояние" (State: the library,
the storage with download progress, recently shown and the queue with links
to files on Disk, errors, the diary, shows by hour). At the bottom of the
Setup tab — save the settings to a file and load them on another TV, folder
and selection included; a flash drive as the storage place transfers only if
that volume exists on the new TV. The open tab is remembered in the address.
Add the page to the phone's home screen so you do not look for the address
again.

**From the remote** during the screensaver: ← → flip slides, "OK" pauses and
resumes (the clock keeps going; after ten minutes the pause lifts itself), ↑
toggles sound in videos (remembered; a note icon in the corner while a video
plays with sound), ↓ shows the hint, any other button closes the screensaver.
The "Фоторамка" app in the launcher — the same settings for the remote.

**Via adb** — the same set of keys (`--es` strings, `--el` integers, `--ef`
floats, `--ez` booleans); only `selectedFolders` cannot be set via adb:

```
adb shell am broadcast -a ru.dvedev.me.yaphotoframe.SET \
  -n ru.dvedev.me.yaphotoframe/.settings.SettingsReceiver \
  --es folderUrl https://disk.yandex.ru/d/XXXXXXXX \
  --el showDurationMillis 30000 --ez showClock true
```

## Settings

Values outside the bounds are clamped. In quotes — the name on the page and in
the app (Russian).

| Key | Default | Bounds | What it is |
|---|---|---|---|
| `folderUrl` | empty | — | Public link to the Yandex Disk folder |
| `selectedFolders` | empty (whole folder) | — | Subfolders to show; a ticked one includes its children |
| `showDurationMillis` | 20 000 | 5 s … 1 h | "Время показа кадра": how long a slide stays |
| `crossfadeMillis` | 1 500 | 0 … 10 s | "Длительность перехода" |
| `driftAmplitude` | 0.04 | 0 … 0.30 | "Путь кадра": path per 20 seconds of show as a fraction of screen width; scaled by show time |
| `zoomAmount` | 0.08 | 0 … 0.30 | "Приближение": growth per 20 seconds as a fraction of own size; scaled by show time, at most 20 %; 0 — none |
| `frameInsetLandscape` | 0.92 | 0.3 … 1 | "Размер горизонтальных": share of the screen; photos are never enlarged (old key `frameInset` accepted) |
| `frameInsetPortrait` | 0.86 | 0.3 … 1 | "Размер вертикальных": share of screen height for portrait photos and pairs |
| `edgeMargin` | 0.03 | 0 … 0.25 | "Отступ от края": the slide never comes closer, neither at the start nor at the end of the show |
| `placementStrength` | 0.75 | 0 … 1 | "Смещение от центра": share of the free room up to the margin; 0 — centre |
| `backgroundDim` | 0.4 | 0 … 1 | "Затемнение фона" |
| `blurSampleLongSide` | 32 | 2 … 64 | Down to how many pixels the background is shrunk; less — blurrier. On the page — "Сила размытия" 0…100 %, right is blurrier |
| `minPhotoFraction` | 0.25 | 0 … 0.6 | "Пропускать мелкие": photos narrower than this fraction of the screen are not shown; 0 — show all |
| `pairPortraits` | `true` | — | "Вертикальные парами": two portrait photos side by side |
| `showVideo` | `true` | — | "Видео": show videos |
| `videoMaxDurationMillis` | 120 000 | 0 … 1 h | "Видео не дольше": how long to hold a video; 0 — to the end |
| `videoSoundEnabled` | `false` | — | "Звук в видео" |
| `downloadsDuringVideo` | `true` | — | "Закачки во время видео": turn off on a weak TV or slow internet, then video downloads pause while a video is on screen; photos are always downloaded |
| `maxFileBytes` | 2 GB | 0 … 64 GB | "Файл не тяжелее": heavier is skipped without downloading; 0 — no limit (old key `videoMaxSizeBytes` accepted) |
| `minStorePhotoBytes` | 0 | 0 … 1 GB | "Не хранить легче", the "фото" slider: a lighter photo is not put into storage and is downloaded anew for every show; 0 — keep all |
| `minStoreVideoBytes` | 0 | 0 … 64 GB | "Не хранить легче", the "видео" slider: a lighter video is streamed on every show; 0 — keep all |
| `showClock` | `true` | — | "Часы" |
| `pauseAutoResumeMillis` | 600 000 (10 min) | 0 … 24 h | "Снимать с паузы через": after how long the pause lifts itself; 0 — never |
| `showDate` | `true` | — | "Дата съёмки": month and year in the photo corner |
| `freshnessWindowDays` | 14 | 1 … 3650 | "Новое чаще": for how many days after upload or first appearance a photo comes up more often; the page slider goes up to a year |
| `storageVolumeUuid` | empty | — | "Место хранилища": empty — TV memory, otherwise the UUID of a flash drive volume; chosen from a list (old key `externalStorageUuid` accepted) |
| `storageBytes` | 2 GB | 0 … disk size | "Объём хранилища": how much space to take for photos and videos when "По свободному месту" is off |
| `storageByFree` | `false` | — | "По свободному месту": take all free space minus the reserve |
| `storageReserveBytes` | 1 GB | 0 … 1 TB | "Запас": how much free space to leave alone with "По свободному месту" (old key `externalReserveBytes` accepted) |
| `networkBps` | 0 | 0 … 2 Gbit/s | "Скорость сети": 0 — auto, the average of the last three downloads; otherwise the typed value decides |
| `prefetchCount` | 10 | 1 … 50 | "Подгружать заранее": how many slides ahead to prepare; a long video download does not hold the queue |
| `indexRefreshIntervalMillis` | 1.5 h | 1 min … 3 h | "Проверять Я.Диск": how often to rescan the folder; Disk links live about three hours |

## Build

JDK 17, Android SDK (compileSdk 35), the Gradle wrapper is in the repository.

```
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # signed from keystore/keystore.properties if present
./gradlew testDebugUnitTest      # JUnit over MockWebServer, no device needed
adb connect <tv-address>:5555 && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## The flash-drive dialog on every power-on

On Android TV the system "Settings" show a dialog when a drive is mounted:
"new device detected: set up as internal storage or as removable storage",
and after sleep the TV mounts the drive again and the dialog is back. An app
cannot turn this off: it needs system privileges.

- Choose **"Removable storage"** in that dialog once for every drive: the
  system marks it as set up and stops asking. "Internal storage" turns the
  drive into an encrypted partition; files cannot be taken off it.
- Do **not** run `sm set-force-adoptable off`: without that flag the system
  mounts the drive invisibly to apps and the frame loses access to it.
- On Xiaomi the media explorer also asks what to open; the only way to remove
  that is to disable it entirely: `adb shell pm disable-user --user 0
  com.xiaomi.mitv.mediaexplorer` (the "Explorer" itself disappears too;
  `pm enable` brings it back).

## Upgrading from versions before 1.4

Settings migrate by themselves: a chosen flash drive becomes the storage place
with "By free space" and the old reserve; without a drive the place is TV
memory with the capacity at the sum of the old "Photo cache" and "Video
buffer", but not less than 2 GB. "Video no heavier than" becomes "File no
heavier than" as is. Photo copies from the old cache move into storage with a
single rename, the stream buffer is deleted; videos on the drive move into
the `videos` folder.

## Limitations

- Only a public Yandex Disk link; private folders and other clouds — no.
- Previews up to 1280 px are shown; photos without a preview (some RAW) are
  skipped, videos play even without one.
- The control page is HTTP without a password: anyone on the home network can
  control it.
- The adb receiver is exported; any app on the TV can change the settings.
- The walk goes up to 12 levels and 50 000 files; if the Disk is larger, tick
  subfolders. An incomplete walk deletes nothing from the index. Until
  subfolders are ticked everything is walked, and on a large Disk that takes
  minutes — progress is visible on the State tab. The folder tree — up to
  5 000 folders.
- On a tablet or phone the APK installs and should work as the system
  screensaver (usually only while charging), but this was not tested; the
  settings screen in the app is made for a remote, the browser page is more
  convenient.
- Tested on one TV model. Works on another — write in issues.

## License

GPL-3.0, see [LICENSE](LICENSE). If the frame found a home —
[buy a coffee](https://www.tbank.ru/cf/BcjzatrF9O).
