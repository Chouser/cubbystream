(ns us.chouser.cubbystream.replay-recording
  "Reprocesses an old AudioRecorder .wav recording through the same DSP the
   live app uses, plus a faithful port of ClassicMidBandEnergyDetector's
   threshold/hysteresis decision logic, and writes a CSV in exactly the
   format DetectionLogger writes live -- so old recordings can be used for
   detector tuning/training on days with no live game to capture fresh logs.

   STEREO: AudioRecorder now preserves the source's real channel count
   (previously it always mixed to mono at recording time, which permanently
   discarded any stereo separation before a WAV file even existed). This
   code reads whatever channel count the WAV header reports and processes
   accordingly -- old mono recordings still work exactly as before.
   stereo_corr/stereo_width are logged the same way DetectionLogger does
   live (see its class doc comment for the formulas); for mono input these
   are trivially 1.0/0.0.

   NUMERIC FIDELITY: the FFT / band-energy / spectral-stats math is NOT
   reimplemented here -- this calls the real, compiled
   us.chouser.cubbystream.AudioFrameUtils directly via Java interop, so
   those numbers are guaranteed identical to the live app. AudioFrameUtils
   has zero Android dependencies, so it compiles standalone:

     javac --release 11 -d classes \\
       ../app/src/main/java/us/chouser/cubbystream/AudioFrameUtils.java

   (already done once; only re-run if that file changes. classes/ is on
   this project's classpath via deps.edn.)

   MidBandEnergyDetector / ClassicMidBandEnergyDetector themselves extend an
   Android Handler/Looper-dependent base and can't be loaded on a desktop
   JVM, so their decision logic is hand-ported below from the current
   source (byte-swap formula, 40-frame rolling mean, 25-frame hysteresis
   threshold) -- keep in sync if those files change. The byte-swap
   translation was verified against the real compiled method (via
   reflection, since it's package-private) across 15 test values, all
   matching to full float precision.

   CAVEATS, discussed at length in conversation -- worth rereading before
   trusting output from this against a live capture:
   - AudioRecorder decimates the live tap down to ~16 kHz for storage
     (e.g. 44100 Hz -> 14700 Hz for a 3:1 ratio, read straight from the
     .wav header). This code upsamples (zero-order hold) back to an
     assumed native rate before analysis, so FRAME_SIZE/sampleRate
     constants match the live pipeline exactly instead of representing a
     different time window per frame. It does NOT recover content above
     the recording's original Nyquist limit (half the recorded rate) --
     that's permanently gone from the file, so e.g. high_band will read
     differently than a live capture would have.
   - The classic (byte-swapped) path can only be faithfully reproduced if
     the WAV genuinely preserved the source's real channel count. If this
     is a mono recording of what was actually a stereo live source (any
     recording made before the AudioRecorder stereo fix, or a source that
     just happens to be stereo), mid_band_classic from replay will NOT
     match a live capture of the same broadcast, no matter what -- the
     per-channel information needed for the correct swap-then-mix order
     was discarded at recording time, not lost here. Check the WAV's own
     channel count (or a live session's logcat) if a mismatch shows up.
   - detector_state/volume_mode are NOT a replay of any real historical
     decision -- they're what THIS threshold would decide, computed fresh
     against this recording. volume_mode is just a copy of detector_state,
     since there's no separate 'live user override' concept for a
     recording played back after the fact."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io RandomAccessFile File)
           (java.nio ByteBuffer ByteOrder)
           (java.text SimpleDateFormat)
           (java.util Locale)
           (us.chouser.cubbystream AudioFrameUtils AudioFrameUtils$SpectralStats
                                   AudioFrameUtils$StereoStats)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Constants mirrored from the app -- keep these in sync if those files change
;; ---------------------------------------------------------------------------

(def frame-size 2048)            ;; AudioTap.FRAME_SIZE / MidBandEnergyDetector.FFT_SIZE
(def window-frames 40)           ;; DetectionLogger.WINDOW_FRAMES
(def write-every 20)             ;; DetectionLogger.WRITE_EVERY
(def smooth-frames 40)           ;; MidBandEnergyDetector.SMOOTH_FRAMES
(def trigger-frames 25)          ;; MidBandEnergyDetector.TRIGGER_FRAMES
(def default-native-rate 44100)  ;; assumed pre-decimation live tap rate

(def ^String csv-header
  (str "timestamp_ms,total_volume,min_volume,mid_band_classic,mid_band,low_band,high_band,"
       "flatness,flux,papr,zcr,stereo_corr,stereo_width,threshold,detector_state,volume_mode,stream_title\n"))

;; ---------------------------------------------------------------------------
;; WAV reading (16-bit PCM, little-endian, mono or multi-channel interleaved
;; -- whatever AudioRecorder wrote; channel count comes from the header)
;; ---------------------------------------------------------------------------

(defn read-wav
  "Reads a 16-bit PCM .wav file, mono or multi-channel interleaved.
   Returns {:sample-rate int :channels int :samples floats-in-[-1,1]}
   where :samples is the RAW INTERLEAVED array (ch0,ch1,...,ch0,ch1,...
   per frame) -- deliberately NOT pre-mixed to mono here, since the classic
   byte-swap path needs genuine per-channel data to reproduce the live
   detector's real swap-then-mix order (see process-frame!)."
  [path]
  (with-open [raf (RandomAccessFile. (io/file path) "r")]
    (let [len (.length raf)
          hdr (byte-array 44)]
      (.readFully raf hdr)
      (let [hb          (doto (ByteBuffer/wrap hdr) (.order ByteOrder/LITTLE_ENDIAN))
            channels    (max 1 (int (.getShort hb 22)))
            sample-rate (.getInt hb 24)
            n-samples   (long (quot (- len 44) 2)) ; total interleaved shorts
            data        (byte-array (* n-samples 2))]
        (.readFully raf data)
        (let [sb  (.asShortBuffer (doto (ByteBuffer/wrap data) (.order ByteOrder/LITTLE_ENDIAN)))
              n   (.remaining sb)
              out (float-array n)]
          (dotimes [i n]
            (aset out i (/ (float (.get sb i)) 32768.0)))
          {:sample-rate sample-rate :channels channels :samples out})))))

;; ---------------------------------------------------------------------------
;; Upsampling (zero-order hold): see conversation for why this instead of
;; shrinking frame-size to match the recording's native rate -- FFT requires
;; a power-of-two length, and 2048 doesn't divide evenly by most decimation
;; ratios anyway, so this keeps FRAME_SIZE/sampleRate identical to the live
;; pipeline rather than introducing a second, independent approximation.
;; ---------------------------------------------------------------------------

(defn upsample-ratio
  "Nearest integer ratio to reconstruct assumed-native-rate from recorded-rate."
  [recorded-rate assumed-native-rate]
  (max 1 (Math/round (/ (double assumed-native-rate) (double recorded-rate)))))

(defn upsample-repeat
  "Zero-order-hold upsample of interleaved multi-channel samples: repeats
   each whole frame (all `channels` consecutive values) `ratio` times,
   preserving interleaving. For channels=1 this is equivalent to repeating
   each scalar sample, as it always did before stereo support existed."
  ^floats [^floats samples ^long channels ^long ratio]
  (if (<= ratio 1)
    samples
    (let [n-frames (quot (alength samples) channels)
          out (float-array (* n-frames channels ratio))]
      (dotimes [i n-frames]
        (let [in-base (* i channels)]
          (dotimes [r ratio]
            (let [out-base (* (+ (* i ratio) r) channels)]
              (dotimes [c channels]
                (aset out (+ out-base c) (aget samples (+ in-base c))))))))
      out)))

;; ---------------------------------------------------------------------------
;; Byte-swap -- ported from ClassicMidBandEnergyDetector.byteSwapSamples.
;; Verified via reflection against the real compiled method across 15 test
;; values spanning the full [-1,1] range; all matched to full float precision.
;; ---------------------------------------------------------------------------

(defn byte-swap-sample
  "Swaps the two bytes of this sample's underlying 16-bit PCM value,
   replicating the old big-endian getShort() mis-read on little-endian data
   that ClassicMidBandEnergyDetector intentionally reproduces.

   Clojure has no primitive float (only long/double), so this works in
   double throughout rather than trying to fake single precision. That's
   safe specifically for this computation: the only real arithmetic here is
   multiply/divide by exactly 32768.0 (2^15), and IEEE-754 scaling by an
   exact power of two is precision-independent -- float and double give the
   same value (short of overflow/denormal extremes far outside audio
   sample range). Verified exhaustively across all 65536 possible 16-bit
   PCM values against Java's actual `(short) Math.round(src[i] * 32768f)`:
   zero mismatches. This guarantee does NOT extend to general arithmetic
   (e.g. summing many values) -- see window-aggregate's docstring."
  ^double [^double v]
  (let [pcm        (unchecked-short (Math/round (* v 32768.0))) ; v is already ^double, so
        pcm-int    (int pcm)                    ; (* v 32768.0) is an unboxed primitive
        lo         (bit-and pcm-int 0xFF)        ; double expr already -- Math/round
        hi         (bit-and (bit-shift-right pcm-int 8) 0xFF) ; resolves statically to
        scrambled  (unchecked-short (bit-or (bit-shift-left lo 8) hi))] ; the long overload
    (/ (double scrambled) 32768.0)))

(defn byte-swap-samples!
  [^floats src ^floats dst ^long n]
  (dotimes [i n]
    (aset dst i (byte-swap-sample (aget src i)))))

;; ---------------------------------------------------------------------------
;; Classic mid-band-energy detector state -- ported from
;; MidBandEnergyDetector's updateStateMachine()/onAudioFrame(), minus the
;; Handler/Looper listener-posting glue, which offline replay has no use for.
;; ---------------------------------------------------------------------------

(defn new-detector-state [threshold]
  {:threshold    (double threshold)
   :smooth-buf   (float-array smooth-frames 0.0)
   :smooth-idx   0
   :smooth-sum   0.0
   :below-count  0
   :above-count  0
   :in-ad-break? false})

(defn detector-step
  "Feeds one frame's classic mid-band energy value through the 40-frame
   rolling mean + 25-frame hysteresis threshold, mirroring
   MidBandEnergyDetector.onAudioFrame/updateStateMachine exactly.

   Like window-aggregate, this rolling sum runs in double rather than
   Java's float accumulator -- same negligible (~1e-7 relative) precision
   difference, not expected to flip any real threshold decision."
  [state ^double mid-e-classic]
  (let [{:keys [^floats smooth-buf smooth-idx smooth-sum threshold
                below-count above-count in-ad-break?]} state
        prev        (aget smooth-buf smooth-idx)
        new-sum     (+ (- smooth-sum prev) mid-e-classic)
        _           (aset smooth-buf smooth-idx mid-e-classic)
        new-idx     (mod (inc smooth-idx) smooth-frames)
        avg         (/ new-sum smooth-frames)
        below?      (< avg threshold)]
    (if below?
      (let [new-below (inc below-count)
            trigger?  (and (not in-ad-break?) (>= new-below trigger-frames))]
        (assoc state
               :smooth-idx new-idx :smooth-sum new-sum
               :below-count new-below :above-count 0
               :in-ad-break? (or in-ad-break? trigger?)))
      (let [new-above (inc above-count)
            trigger?  (and in-ad-break? (>= new-above trigger-frames))]
        (assoc state
               :smooth-idx new-idx :smooth-sum new-sum
               :above-count new-above :below-count 0
               :in-ad-break? (if trigger? false in-ad-break?))))))

;; ---------------------------------------------------------------------------
;; Stereo correlation / width now calls the real, compiled
;; AudioFrameUtils.stereoStats via interop (see the numeric-fidelity note in
;; the namespace docstring) rather than reimplementing the formula here --
;; same reasoning as the FFT/band-energy calls below.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Per-frame DSP -- calls the real AudioFrameUtils via interop for every
;; number that matters (FFT, band energy, spectral stats), so these are
;; guaranteed identical to what the live app would have computed.
;; ---------------------------------------------------------------------------

(defrecord FrameCtx [^floats mono ^floats swapped-interleaved ^floats swapped-mono
                     ^floats re ^floats im
                     ^floats prev-mag ^floats prev-mag-class])

(defn new-frame-ctx [channels]
  (->FrameCtx (float-array frame-size)
              (float-array (* frame-size channels))
              (float-array frame-size)
              (float-array frame-size) (float-array frame-size)
              (float-array (quot frame-size 2)) (float-array (quot frame-size 2))))

(defn process-frame!
  "raw-interleaved-frame must be exactly frame-size*channels samples of the
   (upsampled) recording, already in [-1,1], interleaved as
   ch0,ch1,...,ch0,ch1,... per frame (matching AudioTap's live layout).
   Returns a map of this frame's metrics plus last-sample (for cross-frame
   ZCR continuity)."
  [^FrameCtx ctx ^floats raw-interleaved-frame last-sample sample-rate channels]
  (let [^floats mono (:mono ctx)
        ^floats swapped-interleaved (:swapped-interleaved ctx)
        ^floats swapped-mono (:swapped-mono ctx)
        ^floats re (:re ctx)
        ^floats im (:im ctx)
        ^floats prev-mag (:prev-mag ctx)
        ^floats prev-mag-class (:prev-mag-class ctx)
        n   (int frame-size)
        ch  (int channels)
        sr  (int sample-rate)]

    ;; Mono-mix for the modern path -- mirrors MidBandEnergyDetector's own
    ;; order (mix first, analyze second), so this path is unaffected by
    ;; channel count either way; matches the near-perfect live/replay
    ;; agreement already confirmed for mid_band etc.
    (AudioFrameUtils/toMono raw-interleaved-frame n ch mono)

    (let [zcr (AudioFrameUtils/zcr mono n (float last-sample))
          new-last (aget mono (dec n))
          ^AudioFrameUtils$StereoStats stereo
          (AudioFrameUtils/stereoStats raw-interleaved-frame n ch)]

      (AudioFrameUtils/hannWindow mono re im n)
      (AudioFrameUtils/fft re im n)
      (let [^AudioFrameUtils$SpectralStats ss
            (AudioFrameUtils/spectralStats re im prev-mag n)
            rms    (AudioFrameUtils/rms mono n)
            low-e  (AudioFrameUtils/bandEnergy re im (int 20) (int 120) sr n)
            mid-e  (AudioFrameUtils/bandEnergy re im (int 120) (int 1800) sr n)
            high-e (AudioFrameUtils/bandEnergy re im (int 1800) (int 8000) sr n)]

        ;; --- Classic path: byte-swap the RAW INTERLEAVED samples FIRST,
        ;; mono-mix SECOND -- matching ClassicMidBandEnergyDetector's real
        ;; order (and DetectionLogger's now-fixed order), NOT mix-then-swap.
        ;; Byte-swap is a nonlinear bit-level op and does not commute with
        ;; averaging, so getting this order right only matters -- and can
        ;; only reproduce a live capture -- when the WAV genuinely preserved
        ;; per-channel data. See the namespace docstring's caveat.
        (byte-swap-samples! raw-interleaved-frame swapped-interleaved (* n ch))
        (AudioFrameUtils/toMono swapped-interleaved n ch swapped-mono)
        (AudioFrameUtils/hannWindow swapped-mono re im n)
        (AudioFrameUtils/fft re im n)
        (AudioFrameUtils/spectralStats re im prev-mag-class n)
        (let [mid-e-classic (AudioFrameUtils/bandEnergy re im (int 120) (int 1800) sr n)]
          {:zcr zcr :last-sample new-last
           :rms rms :low-e low-e :mid-e mid-e :high-e high-e
           :flatness (.-flatness ss) :flux (.-flux ss) :papr (.-papr ss)
           :mid-e-classic mid-e-classic
           :stereo-corr (.-corr stereo) :stereo-width (.-width stereo)})))))

;; ---------------------------------------------------------------------------
;; Diagnostic window (DetectionLogger.onAudioFrame's ring buffer + aggregation)
;; ---------------------------------------------------------------------------

(defn new-window []
  {:pos 0 :fill 0 :frames-since-write 0
   :mid-classic (float-array window-frames) :mid (float-array window-frames)
   :low (float-array window-frames) :high (float-array window-frames)
   :rms (float-array window-frames) :flatness (float-array window-frames)
   :flux (float-array window-frames) :papr (float-array window-frames)
   :zcr (float-array window-frames)
   :stereo-corr (float-array window-frames) :stereo-width (float-array window-frames)})

(defn window-push!
  "Pushes one frame's metrics into the ring buffer. Returns the updated
   window map; :ready? true means a CSV row should be aggregated/written now."
  [w frame]
  (let [slot (:pos w)]
    (aset ^floats (:mid-classic w) slot (float (:mid-e-classic frame)))
    (aset ^floats (:mid w)          slot (float (:mid-e frame)))
    (aset ^floats (:low w)          slot (float (:low-e frame)))
    (aset ^floats (:high w)         slot (float (:high-e frame)))
    (aset ^floats (:rms w)          slot (float (:rms frame)))
    (aset ^floats (:flatness w)     slot (float (:flatness frame)))
    (aset ^floats (:flux w)         slot (float (:flux frame)))
    (aset ^floats (:papr w)         slot (float (:papr frame)))
    (aset ^floats (:zcr w)          slot (float (:zcr frame)))
    (aset ^floats (:stereo-corr w)  slot (float (:stereo-corr frame)))
    (aset ^floats (:stereo-width w) slot (float (:stereo-width frame)))
    (let [new-pos  (mod (inc slot) window-frames)
          new-fill (min window-frames (inc (:fill w)))
          primed?  (= new-fill window-frames)
          new-fsw  (if primed? (inc (:frames-since-write w)) 0)
          ready?   (and primed? (>= new-fsw write-every))]
      (assoc w :pos new-pos :fill new-fill
             :frames-since-write (if ready? 0 new-fsw)
             :ready? ready?))))

(defn window-aggregate
  "Mean over the window for most metrics, sum for flux, max for papr,
   min for rms -- matching DetectionLogger's aggregation exactly.
   min-rms preserves brief near-silence transients (e.g. the gap between
   two back-to-back ad spots) that averaging alone would smooth away.
   stereo-corr/stereo-width are means, same as most other metrics.

   NOTE ON PRECISION: unlike byte-swap-sample, this accumulation is
   general addition (not scaling by a power of two), so summing in
   double here vs Java's float accumulator in DetectionLogger is NOT
   guaranteed bit-identical -- verified by simulation to differ by at
   most ~1e-7 relative (float32 machine epsilon), which is negligible
   for any real threshold decision but worth knowing if you ever need
   exact reproduction rather than a very close match."
  [w]
  (let [mean (fn [^floats a] (/ (areduce a i acc 0.0 (+ acc (aget a i)))
                                 (double window-frames)))
        sum  (fn [^floats a] (areduce a i acc 0.0 (+ acc (aget a i))))
        maxv (fn [^floats a] (areduce a i acc 0.0 (max acc (aget a i))))
        minv (fn [^floats a] (areduce a i acc Double/MAX_VALUE (min acc (aget a i))))]
    {:mid-classic (mean (:mid-classic w)) :mid (mean (:mid w))
     :low (mean (:low w)) :high (mean (:high w))
     :rms (mean (:rms w)) :min-rms (minv (:rms w))
     :flatness (mean (:flatness w))
     :flux (sum (:flux w)) :papr (maxv (:papr w))
     :zcr (mean (:zcr w))
     :stereo-corr (mean (:stereo-corr w)) :stereo-width (mean (:stereo-width w))}))

;; ---------------------------------------------------------------------------
;; Filename timestamp parsing (best-effort) -- AudioRecorder names files
;; "yyyyMMdd_HHmmss_<title>.wav"; if this file follows that convention we can
;; anchor timestamp_ms at the real recording start instead of at zero.
;; ---------------------------------------------------------------------------

(defn start-epoch-from-filename
  "Returns epoch ms parsed from a leading yyyyMMdd_HHmmss prefix, or nil."
  [filename]
  (when-let [[_ ts] (re-find #"^(\d{8}_\d{6})" (.getName (io/file filename)))]
    (try
      (.getTime (.parse (SimpleDateFormat. "yyyyMMdd_HHmmss" Locale/US) ts))
      (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Main entry point
;; ---------------------------------------------------------------------------

(defn replay-wav
  "Reprocesses wav-path through the same DSP + classic mid-band detector the
   live app uses and writes a CSV in DetectionLogger's exact format to
   out-path.

   threshold        -- required; same units/scale as MidBandEnergyDetector's
                        `threshold` field (default live value is 200.0).
   :native-rate     -- assumed pre-decimation live-tap rate to upsample back
                        to (default 44100).
   :stream-title    -- defaults to the file's base name.
   :start-epoch-ms  -- overrides filename-based timestamp parsing.

   Channel count is read from the WAV header itself (see read-wav) -- mono
   recordings work exactly as before; stereo recordings additionally get a
   faithful classic-path reproduction and real stereo_corr/stereo_width
   (see the namespace docstring's caveat on what stereo requires).

   Returns the number of CSV rows written."
  [wav-path threshold out-path
   & {:keys [native-rate stream-title start-epoch-ms]
      :or   {native-rate default-native-rate}}]
  (let [{:keys [sample-rate channels samples]} (read-wav wav-path)
        ratio       (upsample-ratio sample-rate native-rate)
        upsampled   ^floats (upsample-repeat samples channels ratio)
        actual-rate (* sample-rate ratio) ; == native-rate when ratio divides evenly
        frame-len   (* frame-size channels)
        n-frames    (quot (alength upsampled) frame-len)
        title       (or stream-title
                        (let [n (.getName (io/file wav-path))
                              i (str/last-index-of n ".")]
                          (if i (subs n 0 i) n)))
        start-ms    (or start-epoch-ms (start-epoch-from-filename wav-path) 0)
        frame-ms    (/ (* 1000.0 frame-size) actual-rate)
        ctx         (new-frame-ctx channels)]
    (when (zero? n-frames)
      (throw (ex-info "Recording shorter than one frame after upsampling" {:wav-path wav-path})))
    (with-open [w (io/writer out-path)]
      (.write w csv-header)
      (loop [i 0, last-sample 0.0, window (new-window)
             detector (new-detector-state (double threshold)), rows 0]
        (if (>= i n-frames)
          rows
          (let [frame-slice (float-array frame-len)
                _ (System/arraycopy upsampled (* i frame-len) frame-slice 0 frame-len)
                frame (process-frame! ctx frame-slice last-sample actual-rate channels)
                window' (window-push! window frame)
                detector' (detector-step detector (:mid-e-classic frame))]
            (when (:ready? window')
              (let [agg (window-aggregate window')
                    ts  (long (+ start-ms (* i frame-ms)))
                    state-str (if (:in-ad-break? detector') "ads" "game")
                    safe-title (str "\"" (str/replace title "\"" "\"\"") "\"")]
                (.write w ^String
                        (String/format Locale/US
                                       "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s,%s\n"
                                       (into-array Object
                                                   [ts (:rms agg) (:min-rms agg) (:mid-classic agg) (:mid agg)
                                                    (:low agg) (:high agg) (:flatness agg)
                                                    (:flux agg) (:papr agg) (:zcr agg)
                                                    (:stereo-corr agg) (:stereo-width agg)
                                                    (double threshold) state-str state-str safe-title])))))
            (recur (inc i) (double (:last-sample frame)) window' detector'
                   (if (:ready? window') (inc rows) rows))))))))

(comment

  ;; Basic usage: reprocess one old recording, threshold 200.0 (the live
  ;; MidBandEnergyDetector default -- tune per your actual detector setting).
  ;; Works the same whether the WAV is mono or stereo -- channel count is
  ;; read from the file itself, nothing to specify.
  (replay-wav "../recordings/20260410_183000_WSCR.wav" 200.0
              "../replayed-logs/20260410_183000_WSCR.csv")

  ;; Point train-pipeline at a directory containing both real live logs and
  ;; replayed ones (or just replayed ones) once you've generated some:
  (require '[us.chouser.cubbystream.train-detect :as td])
  (td/train-pipeline "../replayed-logs/")

  )
