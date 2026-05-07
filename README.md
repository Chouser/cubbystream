# CubbyStream — Android App

A background-capable baseball audio streaming app with automatic commercial detection.

---

## Building the App

**Requirements:** Android Studio Hedgehog (2023.1) or later, JDK 11+.

1. Open the `cubbystream` folder in Android Studio.
2. Let Gradle sync complete.
3. Connect a device (Android 8.0 / API 26+) or start an emulator.
4. Run → **Run 'app'**.

## Building with Docker

```bash
docker run -it --rm \
  -v $(pwd):/root/build \
  -v gradle-cache:/root/.gradle \
  mingc/android-build-box:latest \
  bash -c "cd /root/build && ./gradlew assembleDebug"
```

### Building release version

Use `./gradlew assembleRelease`

This will pick up `app/src/main/res/xml/network_security_config.xml` instead of `app/src/debug/res/xml/network_security_config.xml`

## Install:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Version compatibility table

| Component      | This project | JDK needed |
|----------------|--------------|------------|
| AGP            | 7.4.2        | 11+        |
| Gradle wrapper | 7.6.4        | 11+        |
| Source/target  | Java 11      | —          |

To upgrade to AGP 8.x in future: switch to `mingc/android-build-box` (JDK 17), bump the classpath to `com.android.tools.build:gradle:8.3.2`, and update `gradle-wrapper.properties` to `gradle-8.9-bin.zip`.

---

## Setting Your Feed URL

Open `FeedFetcher.java` and change:
```java
public static final String FEED_URL = "https://myserver.com/streams.json";
```
to your actual server address.

---

## JSON Feed Format

Your server must return `Content-Type: application/json` with this structure:

```json
{
  "updated": "2026-04-29T18:00:00Z",
  "streams": [
    {
      "title": "Yankees vs Red Sox",
      "subtitle": "ESPN Radio • 7:05 PM ET",
      "url": "https://example.com/yankees.m3u8"
    },
    {
      "title": "Cubs vs Cardinals",
      "subtitle": "670 The Score",
      "url": "https://stream.example.com/cubs.mp3",
      "type": "mp3"
    }
  ]
}
```

**Fields:**
| Field | Required | Notes |
|-------|----------|-------|
| `title` | Yes | Shown in spinner and info panel |
| `subtitle` | No | Secondary info (broadcaster, time) |
| `url` | Yes | The stream URL |
| `type` | No | `hls`, `mp3`, `aac`, `ogg` — omit and ExoPlayer auto-detects |

**Supported stream formats (auto-detected):**
- HLS (`.m3u8`) — live streams, best for "skip to live"
- MP3 (Shoutcast / Icecast direct streams)
- AAC (direct or inside HLS)
- OGG Vorbis

---

## App Architecture

```
MainActivity  — Single screen: stream picker, player controls, volume mode
  ├─ PlaybackService (Foreground Service) — owns ExoPlayer, survives app switching
  └─ CrowdNoiseDetector — FFT audio processor, fires commercial/game events
```

**Background playback:** `PlaybackService` is a foreground service (`mediaPlayback` type)
with a persistent notification. Android will not kill it during normal operation.
`START_STICKY` ensures it restarts if killed under extreme memory pressure.

---

## Commercial Detection

### How it works

Every ~100ms the detector:
1. Taps the decoded PCM stream inside ExoPlayer via the `AudioProcessor` interface
2. Applies a Hann window to reduce spectral leakage
3. Runs a Cooley-Tukey FFT (in-place, no external library)
4. Measures average power in the **120 Hz – 1800 Hz** band (where crowd noise lives)
5. Maintains a rolling average over 40 frames (~4 seconds) to smooth transients
6. After 25 consecutive frames (≈2.5s) below threshold → commercial detected
7. After 25 consecutive frames above threshold → game resumed

### Tuning the threshold

In `CrowdNoiseDetector.java`:
```java
public float threshold = 200f;
```
- **Raise it** if commercials are being falsely flagged as game play.
- **Lower it** if game play is being falsely flagged as commercial.

You can also adjust trigger sensitivity:
```java
private static final int TRIGGER_FRAMES = 25; // ~2.5 seconds before switching
```

### Manual override

The **🔊 Game** and **🔇 Ads** buttons immediately override the auto-detector and stay
in effect until you tap **🤖 Auto** to resume automatic detection.

---

## UI Layout

The single `MainActivity` screen has three zones:

**Top bar** — A drop-down spinner showing all streams from the JSON feed (first item
selected by default) and a **■ Stop** button. Stop is disabled unless a stream is
playing or paused. Changing the spinner selection while a stream is active stops it.

**Info panel** (shown only when a stream has been started) — Title, subtitle, and a
row of three labels: `Level: N.N` | mode indicator | `Thr: N.N`. Below them a
progress bar showing energy relative to threshold. The mode indicator reads one of:
`Auto: Game`, `Auto: Ads`, `Manual: Game`, or `Manual: Ads`.

**Bottom bar** — identical layout to the original player screen:
- **▶▶ Live** — jump to the live edge (HLS streams)
- **⏸ Pause** — pause; disabled when stopped or already paused
- **▶ Play** — resume if paused, or start the selected stream from the live edge if stopped; disabled when already playing
- **🔊 Game / 🔇 Ads / 🤖 Auto** — volume mode buttons

---

## Permissions Requested

| Permission | Why |
|-----------|-----|
| `INTERNET` | Stream audio and fetch JSON |
| `ACCESS_NETWORK_STATE` | Check connectivity |
| `WAKE_LOCK` | Keep CPU alive during background playback |
| `FOREGROUND_SERVICE` | Background audio service |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Classify service type (API 34+) |
| `POST_NOTIFICATIONS` | Show playback notification (Android 13+) |

---

## Customization

**Commercial volume level** — in `PlaybackService.java`:
```java
public static final float REDUCED_VOLUME = 0.10f; // 10%
```

**Feed URL** — in `FeedFetcher.java`:
```java
public static final String FEED_URL = "https://myserver.com/streams.json";
```

**Crowd noise band** — in `CrowdNoiseDetector.java`:
```java
private static final int CROWD_LOW_HZ  = 120;
private static final int CROWD_HIGH_HZ = 1800;
```
