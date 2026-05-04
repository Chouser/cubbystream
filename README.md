# BaseballStream — Android App

A background-capable baseball audio streaming app with automatic commercial detection.

---

## Building the App

**Requirements:** Android Studio Hedgehog (2023.1) or later, JDK 11+.

1. Open the `BaseballStream` folder in Android Studio.
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

Install:

adb install app/build/outputs/apk/debug/app-debug.apk

### Version compatibility table

| Component      | This project | JDK needed |
|---------------|-------------|------------|
| AGP            | 7.4.2       | 11+        |
| Gradle wrapper | 7.6.4       | 11+        |
| Source/target  | Java 11     | —          |

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
| `title` | Yes | Shown in list and player |
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
MainActivity          — Fetches JSON, shows stream list, Reload button
  └─ PlayerActivity   — Player UI (Pause/Resume/Stop/Skip-to-Live/Volume)
       ├─ PlaybackService (Foreground Service) — owns ExoPlayer, survives app switching
       └─ CrowdNoiseDetector — FFT analysis thread, fires commercial/game events
```

**Background playback:** `PlaybackService` is a foreground service (`mediaPlayback` type)
with a persistent notification. Android will not kill it during normal operation.
`START_STICKY` ensures it restarts if killed under extreme memory pressure.

---

## Commercial Detection

### How it works

Every ~100ms the detector:
1. Reads a 2048-sample PCM buffer from the microphone (22050 Hz)
2. Applies a Hann window to reduce spectral leakage
3. Runs a Cooley-Tukey FFT (in-place, no external library)
4. Measures average power in the **120 Hz – 1800 Hz** band (where crowd noise lives)
5. Maintains a rolling average over 40 frames (~4 seconds) to smooth transients
6. After 25 consecutive frames (≈2.5s) below threshold → commercial detected
7. After 25 consecutive frames above threshold → game resumed

### Tuning the threshold

In `CrowdNoiseDetector.java`:
```java
public float threshold = 0.012f;
```
- **Raise it** (e.g., `0.020f`) if commercials are being falsely flagged as game play.
- **Lower it** (e.g., `0.008f`) if game play is being falsely flagged as commercial.

You can also adjust trigger sensitivity:
```java
private static final int TRIGGER_FRAMES = 25; // ~2.5 seconds before switching
```

### Manual override

The **🔊 Game** and **🔇 Commercial** buttons immediately override the auto-detector.
After pressing either, auto-detection pauses for 8 seconds (grace period) then resumes.

### Microphone note

Android does not provide a public API to tap the speaker output directly.
The detector listens through the microphone, which works well when:
- Playing through the phone speaker
- Playing through a wired headset (mic picks up the earpiece)
- Bluetooth headsets (less reliable — mic and speaker are on different paths)

For a future enhancement, ExoPlayer's `AudioProcessor` interface can be used to
intercept the PCM data before it reaches the DAC, giving perfect signal quality
regardless of output device. The FFT logic in `CrowdNoiseDetector` is unchanged.

---

## Player Controls

| Control | Location | Purpose |
|---------|----------|---------|
| **⏸ PAUSE** | Top of screen | Pause playback |
| **▶ RESUME** | Bottom of screen | Resume (far from Pause to prevent accidental toggle) |
| **⏭ Live** | Bottom row | Jump to live edge (HLS streams) |
| **■ Stop** | Bottom row | Close connection and exit player |
| **🔊 Game** | Middle | Force full volume (100%) |
| **🔇 Commercial** | Middle | Force reduced volume (20%) |

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
| `RECORD_AUDIO` | FFT commercial detection (user prompted, gracefully degraded if denied) |

---

## Customization

**Commercial volume level** — in `PlaybackService.java`:
```java
public static final float REDUCED_VOLUME = 0.20f; // 20%
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
