# Storage: how the frame keeps photos and videos

Since version 1.4 the frame has a single storage for everything it downloads:
photo copies and whole video files. Below: the terms, the settings, the process
from the Disk walk to the screen, and what happens in special cases. The same
process as a flowchart: [flow-1.4.en.pdf](flow-1.4.en.pdf). Русский:
[storage.ru.md](storage.ru.md).

## 1. Terms

- **Storage** — one folder where the frame keeps everything it downloaded:
  photo copies (1280 px on the long side, about 200 KB each) and whole video
  files. Two branches inside: `previews/` with photo copies under hash names
  and `videos/` with a path tree as on Yandex Disk.
- **Storage place** — where that folder lives: in TV memory (the app's
  `cache/storage`) or on a USB flash drive
  (`Android/media/ru.dvedev.me.yaphotoframe/Фоторамка`). One setting.
- **Capacity** — how much space the frame may take. Either an explicit number
  from the slider, or "by free space": all free space on the disk minus the
  **reserve**.
- **Reserve** — how much free space the frame leaves alone when capacity is
  "by free space". Set with the same slider as capacity: the toggle changes the
  meaning of the slider. One gigabyte by default.
- **Network speed** — how fast the TV downloads from Disk. The frame measures
  it on every video download and keeps the average of the last three; the
  owner can type in a value instead.

## 2. Settings

Section **"Media files"**:

| Setting | Key | Default | What it does |
|---|---|---|---|
| Video | `showVideo` | on | Show videos alongside photos. The index always knows about videos; no rescan needed. |
| Sound in video | `videoSoundEnabled` | off | Also toggled from the remote with ↑. |
| Portraits in pairs | `pairPortraits` | on | Two portrait photos side by side. |
| Skip small ones | `minPhotoFraction` | 25 % | Do not show photos smaller than this fraction of the screen. |
| New more often | `freshnessWindowDays` | 14 days | How many days after upload a photo comes up more often. |
| Clock, Capture date | `showClock`, `showDate` | on | Over the frame. |
| Video no longer than | `videoMaxDurationMillis` | 2 min | After that the frame moves to the next slide. Zero — play to the end. |
| **File no heavier than** | `maxFileBytes` | 2 GB | A file heavier than the threshold is skipped: not downloaded, not streamed, not shown. Zero — no limit. |
| **Don't keep lighter than** | `minStorePhotoBytes`, `minStoreVideoBytes` | photo 0, video 0 | One row, two sliders. A photo lighter than the "photo" threshold is not put into storage but downloaded anew for every show; a video lighter than the "video" threshold is streamed on every show. Zero — keep everything. Photo copies weigh about 200 KB: a higher threshold turns off keeping photos, and the frame has nothing to show without network. |
| Downloads during video | `downloadsDuringVideo` | on | Off stops video downloads while a video is on screen; photos are always downloaded. |

Section **"Storage"**:

| Setting | Key | Default | What it does |
|---|---|---|---|
| **Storage place** | `storageVolumeUuid` | TV memory | List: "TV memory" and the inserted flash drives. A drive is checked with a test write; unusable ones are shown in red. |
| **Storage capacity** | `storageBytes`, `storageByFree`, `storageReserveBytes` | TV memory: 2 GB; flash: by free space, reserve 1 GB | One row: a slider from 0 to the size of the chosen disk and a "By free space" toggle. Toggle off — the slider sets the capacity. Toggle on — the same slider becomes the "Reserve". |
| **Network speed** | `networkBps` | auto | Auto — the average of the last three downloads, shown right there. A typed value overrides the measurement; the measurement is still displayed. |
| Prefetch ahead | `prefetchCount` | 10 | How many files ahead the frame prepares the queue. A long video download does not hold the queue. |
| Check Yandex Disk | `indexRefreshIntervalMillis` | 1.5 h | How often to rescan the folder. |

Gone: "Photo cache", "Video to cache up to", "Video buffer", "Network speed to
Disk" in its old meaning, "Video no heavier than" (now "File no heavier
than"), "Space reserve" (now the reserve in the "Storage capacity" row).

## 3. The process from A to Z

**A. Walk.** The frame walks the Disk folder and records every file in the
index: path, size, date, the preview link for a photo. Zero-size files are
dropped.

**B. Queue.** The frame picks slides into the queue by how long ago they were
shown, by freshness and by the folder selection. Videos stand in the queue on
equal terms with photos when "Video" is on. At most one video waiting for
download is in the queue at a time: they download one by one, and five waiting
in a row would leave the screen without photos.

**C. Thresholds.** When a file enters the prefetch window the frame looks at
its size first: heavier than "File no heavier than" — removed from the queue.
A photo lighter than the "photo" threshold in "Don't keep lighter than" is
marked "don't keep": it is downloaded into a temporary cache for the show and
the next photos push it out; a video lighter than the "video" threshold is
streamed. Then the frame reads the video header with two small requests and
takes the duration, bitrate and codec profile; it records the result in the
index so the header is never read twice. If the TV cannot decode the profile
(on Mi TV that is 10-bit HEVC), the frame marks the video undecodable, removes
it from the queue and writes to the diary. Nothing is downloaded.

**D. Room in storage.** Before a download the frame computes the capacity.
With "By free space" on it is the free space on the storage disk minus the
reserve plus what the frame's own files already take; with it off it is the
slider value, but not more than the disk has. If the file fits the capacity
but there is not enough room right now, the frame deletes the oldest-shown
files from storage until there is. Videos are deleted before photos: photos
are small, and without them the screen is empty when there is no network. The
frame does the same when someone else ate the disk space: between prefetches
it re-reads the file list and the free space and trims its own files.

**E. Photo.** The frame downloads the photo copy into storage and shows it
from there.

**F. Video that fits.** The frame downloads the whole video into storage, one
at a time in queue order, into a temporary file renamed on completion. Until
the video has finished downloading the show goes past it to the next slide; on
the page it is grey with a "waiting for download" mark; the prefetch window
keeps filling with photos meanwhile. As soon as the download finishes the
frame shows the video at the next slide change, from storage as an ordinary
file; the poster and background are taken from the file's own first frame.

**G. Video that does not fit.** This happens when the video is larger than
everything the storage can give even after evicting all. The frame compares
the bitrate with the measured network speed: not higher — plays it as a
stream straight from Disk without saving anything; higher — skips it and
writes to the diary.

**H. Network speed.** For every video download the frame computes the speed
and keeps the average of the last three downloads; small downloads (photo
copies) are not measured. The value is shown in the "Network speed" setting
and on the "State" tab and takes part in step G. If the owner typed a speed,
the typed one decides and the measurements are only displayed. While there
are no measurements yet the frame considers the network slow: a video that
does not fit is skipped rather than streamed.

**I. Repeat.** A video or photo already in storage is not downloaded twice.
Evicted files are downloaded again when they next enter the queue. A failed
download is not retried until restart: such a video is streamed if the
network can carry it, otherwise skipped.

**J. Flash drive removed.** The frame notices within seconds, writes to the
diary and the "State" tab, and temporarily switches to TV memory under the
same rules: photos and videos download there, old files are evicted there.
The screen does not go empty. When the drive returns the frame works with it
again and sees its old files; new files download to the drive.

**K. Place changed by hand.** New files go to the new place, old ones stay in
the old one: files on a drive can be taken away and shown anywhere. After
switching back the frame sees them again.

**L. Disk folder changed.** The frame clears the storage in both places and
the index.

**M. No network.** The frame shows what it has in storage and tries again;
expired Disk links refresh themselves.

## 4. What the "State" tab shows

The **"Storage"** block: where it is (TV memory or a flash drive with its
label and path; if a drive is chosen but absent, it says so), used, capacity
or "may take", free on disk, photos and videos separately, how many videos
wait for download, the measured network speed, what is downloading now with a
percentage. In the queue a video waiting for download is grey; a video that
will be streamed is marked "stream".

## 5. Migration from versions before 1.4

- A chosen flash drive becomes the storage place with "By free space" and the
  old reserve as the reserve; otherwise the place is TV memory with the slider
  at the sum of the old "Photo cache" and "Video buffer", but not less than
  2 GB.
- Photo copies from the old cache move into storage with a single folder
  rename so they are not downloaded again; the stream buffer is deleted.
  Videos that earlier builds put into the root of the drive folder move into
  `videos/`.
- "Video no heavier than" moves into "File no heavier than" as is.
- Old keys in settings files and via adb are accepted under the new names:
  `externalStorageUuid` → `storageVolumeUuid`, `externalReserveBytes` →
  `storageReserveBytes`, `videoMaxSizeBytes` → `maxFileBytes`. Other old keys
  are ignored.

## 6. Where things are in the code

- `cache/Storage.kt` — the storage: keys, capacity, eviction; the file list
  is kept in memory and re-read between prefetches.
- `cache/NetworkGauge.kt` — speed measurement over three downloads.
- `video/Mp4Duration.kt` — duration and codec from the MP4/MOV header.
- `engine/FrameEngine.kt` — the `plan` ladder: skip → header → "don't keep" →
  into storage → stream or skip; one download at a time.
- `FrameDreamService.kt` — the storage place, the fallback place when the
  drive is removed, migration of the old cache, the download diary.
