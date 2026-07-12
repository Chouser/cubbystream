(ns us.chouser.cubbystream.train-detect
  (:require [tablecloth.api :as tc]
            [tablecloth.column.api :as tcc]
            [scicloj.ml.smile.classification]
            [scicloj.metamorph.ml :as ml]
            [tech.v3.dataset.rolling :as ds-roll]
            [tech.v3.dataset.modelling :as ds-mod]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import (smile.base.cart Node OrdinalNode DecisionNode)
           (smile.classification DecisionTree)
           (java.io ByteArrayInputStream ObjectInputStream)))

(set! *warn-on-reflection* true)

;; --- Label Correction Logic ---

(defn fix-user-latency
  "Backfills 'game' labels when a user is slow to switch to 'auto'."
  [ds]
  (let [rows (vec (tc/rows ds :as-maps))]
    (tc/dataset
     (reduce (fn [acc idx]
               (let [curr (get rows idx)
                     prev (get rows (dec idx) {})]
                 (if (and (= (:volume_mode prev) "ads")
                          (= (:volume_mode curr) "auto"))
                   (let [lookback-limit (max 0 (- idx 80))
                         corrected-acc 
                         (loop [i (dec idx) temp-acc acc]
                           (let [row-to-fix (get temp-acc i)]
                             (if (and (>= i lookback-limit)
                                      (= (:detector_state row-to-fix) "game"))
                               (recur (dec i) (assoc temp-acc i (assoc row-to-fix :volume_mode "game")))
                               temp-acc)))]
                     (conj corrected-acc (assoc curr :volume_mode "game")))
                   (conj acc curr))))
             []
             (range (count rows))))))

(defn clean-short-spans
  "Filters out noise by flipping 'game' blocks shorter than 10s back to 'ads'."
  [ds]
  (let [ms-threshold 10000
        rows (tc/rows ds :as-maps)
        chunks (partition-by :volume_mode rows)]
    (tc/dataset
     (mapcat (fn [chunk]
               (let [mode (:volume_mode (first chunk))
                     duration (- (:timestamp_ms (last chunk)) (:timestamp_ms (first chunk)))]
                 (if (and (= mode "game") (< duration ms-threshold))
                   (map #(assoc % :volume_mode "ads") chunk)
                   chunk)))
             chunks))))

;; --- Feature Engineering ---

(defn add-features [ds]
  (let [;; 1. Compute rolling windows first
        rolled-ds (ds-roll/rolling ds 8 {:energy_mean (ds-roll/mean :energy)
                                         :energy_std  (ds-roll/standard-deviation :energy)})]
    (-> rolled-ds
        ;; 2. Explicitly extract columns for the mathematical operations
        (tc/add-columns
         {:mid_high_ratio (tcc// (rolled-ds :mid_band) 
                                 (tcc/max (rolled-ds :high_band) 1.0))
          :flux_energy_ratio (tcc// (rolled-ds :flux) 
                                    (tcc/max (rolled-ds :energy) 1.0))})
        ;; 3. Trim window warmup padding
        (tc/drop-rows (range 8)))))

;; --- Java Source Generation ---

(def ^java.lang.reflect.Field ordinal-node-value
  (doto (.getDeclaredField OrdinalNode "value")
    (.setAccessible true)))

(defn- deserialize-smile-model [bytes]
  (with-open [bais (ByteArrayInputStream. bytes)
              ois (ObjectInputStream. bais)]
    (.readObject ois)))

(defn node-to-java [{:keys [output] :as node} depth]
  (let [indent (apply str (repeat depth "  "))]
    (if output
      (format "%sreturn %d; // %s" indent output (if (= 1 output) "GAME" "ADS"))
      (format "%sif (%s <= %f) {\n%s\n%s} else {\n%s\n%s}"
              indent (:feature node) (:<= node)
              (node-to-java (:true node) (inc depth)) indent
              (node-to-java (:false node) (inc depth)) indent))))

;; Maps each training feature name to the Java scaffolding needed to compute it.
;;   :fields      — field declarations (deduped; energy_mean/std share energyRoll)
;;   :update      — statements in onAudioFrame that push/compute rolling state
;;   :var         — Java expression yielding the feature value for predict()
;;   :ready-check — Java boolean expression; nil if no warm-up needed
(def feature-meta
  {"energy_mean"
   {:fields      ["private final AudioFrameUtils.RollingStats energyRoll = new AudioFrameUtils.RollingStats(8);"]
    :update      ["energyRoll.push(midE);"]
    :var         "energyRoll.mean()"
    :ready-check "energyRoll.ready()"}

   "energy_std"
   {:fields      ["private final AudioFrameUtils.RollingStats energyRoll = new AudioFrameUtils.RollingStats(8);"]
    :update      ["energyRoll.push(midE);"]   ; deduped with energy_mean if both present
    :var         "energyRoll.std()"
    :ready-check "energyRoll.ready()"}

   "mid_high_ratio"
   {:fields      []
    :update      []
    :var         "AudioFrameUtils.midHighRatio(midE, highE)"
    :ready-check nil}

   "flux_energy_ratio"
   {:fields      []
    :update      []
    :var         "AudioFrameUtils.fluxEnergyRatio(ss.flux, midE)"
    :ready-check nil}})

(defn generate-java
  "Emit a complete AdDetector implementation for the given decision tree.
   The class declares only the rolling state it needs, calls AudioFrameUtils
   for all DSP, and contains no shared base class."
  [node]
  (let [class-name "GeneratedDetector"
        alg-key    "generated"

        used-features (->> node
                           (tree-seq :feature (comp concat (juxt :true :false)))
                           (keep :feature)
                           distinct)

        metas        (map feature-meta used-features)

        fields       (->> metas (mapcat :fields) distinct)
        updates      (->> metas (mapcat :update) distinct)
        ready-checks (->> metas (keep :ready-check) distinct)

        feature->var (into {} (map (fn [f] [f (:var (feature-meta f))]) used-features))

        needs-high   (some #{"mid_high_ratio"} used-features)
        needs-flux   (some #{"flux_energy_ratio"} used-features)

        predict-params (str/join ", " (map #(str "double " %) used-features))
        predict-args   (str/join ", " (map feature->var used-features))

        i4 "    "
        i8 "        "

        field-block (str/join "\n" (map #(str i4 %) fields))

        on-audio-lines
        (remove nil?
                [(str i8 "AudioFrameUtils.toMono(samples, frameSize, channelCount, monoBuf);")
                 (str i8 "AudioFrameUtils.hannWindow(monoBuf, re, im, FRAME_SIZE);")
                 (str i8 "AudioFrameUtils.fft(re, im, FRAME_SIZE);")
                 (str i8 "float midE = AudioFrameUtils.bandEnergy(re, im, 120, 1800, sampleRate, FRAME_SIZE);")
                 (when needs-high
                   (str i8 "float highE = AudioFrameUtils.bandEnergy(re, im, 1800, 8000, sampleRate, FRAME_SIZE);"))
                 (when needs-flux
                   (str i8 "AudioFrameUtils.SpectralStats ss = AudioFrameUtils.spectralStats(re, im, prevMag, FRAME_SIZE);"))
                 (when (seq updates)
                   (str/join "\n" (map #(str i8 %) updates)))
                 (when (seq ready-checks)
                   (str i8 "if (!(" (str/join " && " ready-checks) ")) return;"))
                 (str i8 "latestSignal = (float) predict(" predict-args ");")
                 (str i8 "updateStateMachine(latestSignal);")])

        on-audio-block (str/join "\n" on-audio-lines)]

    (str
     "package us.chouser.cubbystream;\n\n"
     "import android.os.Handler;\n"
     "import android.os.Looper;\n"
     "import java.util.Locale;\n\n"
     "/**\n"
     " * Generated by train_detect.clj — do not edit by hand.\n"
     " */\n"
     "public class " class-name " implements AdDetector {\n\n"
     i4 "public static final String ALGORITHM_KEY = \"" alg-key "\";\n\n"
     i4 "private static final int FRAME_SIZE     = AudioTap.FRAME_SIZE;\n"
     i4 "private static final int TRIGGER_FRAMES = 25;\n\n"
     i4 "// ---- DSP buffers ----\n"
     i4 "private final float[] monoBuf = new float[FRAME_SIZE];\n"
     i4 "private final float[] re      = new float[FRAME_SIZE];\n"
     i4 "private final float[] im      = new float[FRAME_SIZE];\n"
     (when needs-flux
       (str i4 "private final float[] prevMag = new float[FRAME_SIZE / 2];\n"))
     "\n"
     (when (seq fields)
       (str i4 "// ---- Feature state ----\n"
            field-block "\n\n"))
     i4 "// ---- Detection state ----\n"
     i4 "private int     belowCount  = 0;\n"
     i4 "private int     aboveCount  = 0;\n"
     i4 "private boolean inAdBreak   = false;\n"
     i4 "private volatile float latestSignal = Float.NaN;\n\n"
     i4 "private AdDetector.Listener listener;\n"
     i4 "private final Handler mainHandler = new Handler(Looper.getMainLooper());\n\n"
     i4 "@Override public String  getDisplayName()  { return \"Generated Tree\"; }\n"
     i4 "@Override public String  getAlgorithmKey() { return ALGORITHM_KEY; }\n"
     i4 "@Override public boolean hasThreshold()    { return false; }\n"
     i4 "@Override public float   getThreshold()    { return Float.NaN; }\n"
     i4 "@Override public float   getSignalLevel()  { return latestSignal; }\n"
     i4 "@Override public String  getStatusText() {\n"
     i4 "    float s = latestSignal;\n"
     i4 "    if (Float.isNaN(s)) return null;\n"
     i4 "    return String.format(Locale.US, \"%s (%.0f%%)\", s >= 0.5f ? \"game\" : \"ads\", s * 100f);\n"
     i4 "}\n\n"
     i4 "@Override public void    setListener(AdDetector.Listener l) { this.listener = l; }\n"
     i4 "@Override public boolean isInAdBreak()   { return inAdBreak; }\n"
     i4 "@Override public void    resetCounters() { belowCount = 0; aboveCount = 0; }\n"
     i4 "@Override public void    reset() {\n"
     i4 "    belowCount = aboveCount = 0; inAdBreak = false; latestSignal = Float.NaN;\n"
     i4 "}\n\n"
     i4 "@Override\n"
     i4 "public void onAudioFrame(float[] samples, int frameSize, int channelCount, int sampleRate) {\n"
     on-audio-block "\n"
     i4 "}\n\n"
     i4 "private static double predict(" predict-params ") {\n"
     (node-to-java node 2) "\n"
     i4 "}\n\n"
     i4 "private void updateStateMachine(float signal) {\n"
     i8 "// predict() returns 1=GAME, 0=ADS\n"
     i8 "if (signal > 0.5f) {\n"
     i8 "    aboveCount = 0; belowCount++;\n"
     i8 "    if (inAdBreak && belowCount >= TRIGGER_FRAMES) {\n"
     i8 "        inAdBreak = false;\n"
     i8 "        mainHandler.post(() -> { if (listener != null) listener.onGameResumed(); });\n"
     i8 "    }\n"
     i8 "} else {\n"
     i8 "    belowCount = 0; aboveCount++;\n"
     i8 "    if (!inAdBreak && aboveCount >= TRIGGER_FRAMES) {\n"
     i8 "        inAdBreak = true;\n"
     i8 "        mainHandler.post(() -> { if (listener != null) listener.onAdBreakStarted(); });\n"
     i8 "    }\n"
     i8 "}\n"
     i4 "}\n"
     "}\n")))


(defn- walk-model [^Node node feature-names]
  (cond
    (instance? DecisionNode node)
    {:output (.output ^DecisionNode node)
     :count (.count ^DecisionNode node)
     :deviance (.deviance ^DecisionNode node)}

    (instance? OrdinalNode node)
    (let [o-node ^OrdinalNode node]
      {:feature (nth feature-names (.feature o-node))
       :<= (.get ordinal-node-value o-node)
       :size (.size o-node)
       :score (.score o-node)
       :deviance  (.deviance o-node)
       :true (walk-model (.trueChild o-node) feature-names)
       :false (walk-model (.falseChild o-node) feature-names)})

    :else
    (throw (IllegalArgumentException. 
            (str "Unsupported tree node type: " (type node))))))

(defn model->tree [model feature-names]
  (let [model-bytes (-> model :model-data :model-as-bytes)
        ^DecisionTree raw-tree (deserialize-smile-model model-bytes)]
    (walk-model (.root raw-tree) feature-names)))

(defn collapse-identical-nodes [tree]
  (if (:output tree)
    tree
    (let [t (collapse-identical-nodes (:true tree))
          f (collapse-identical-nodes (:false tree))]
      (if (and (:output t) (= (:output t) (:output f)))
        (do (prn :hit)
            {:output (:output t)})
        tree))))

;; --- Orchestration ---

(defn read-ds [csv-dir]
  (let [files (->> (io/file csv-dir)
                   file-seq
                   (filter #(str/ends-with? (.getName ^java.io.File %) ".csv")))]
    (-> (apply tc/concat (map #(tc/dataset % {:key-fn keyword}) files))
        (tc/order-by :timestamp_ms))))

(defn train-pipeline [csv-dir]
  (println "Training...")
  (let [feature-keys [:energy_mean :energy_std :mid_high_ratio :flux_energy_ratio]
        feature-strings (map name feature-keys)
        processed-ds (-> (read-ds csv-dir)
                         fix-user-latency
                         clean-short-spans
                         add-features
                         (tc/map-columns :volume_mode (fn [v] (if (= v "game") 1 0)))
                         ;; Fix 2: Isolate ONLY the target and training features
                         (tc/select-columns (conj feature-keys :volume_mode))
                         (ds-mod/set-inference-target :volume_mode))

        model (ml/train processed-ds {:model-type :smile.classification/decision-tree
                                      :max-nodes 24
                                      :split-rule :gini})]
    (println "Training complete.")
    (model->tree model feature-strings)))

(comment

  (-> (train-pipeline "../logs/")
      (collapse-identical-nodes))

  (-> (train-pipeline "../selected-logs/")
      (generate-java)
      (->> (spit "../app/src/main/java/us/chouser/cubbystream/GeneratedDetector.java")))

  (->> (train-pipeline "../logs/")
       (tree-seq :feature (comp concat (juxt :true :false)))
       (map :feature)
       distinct)
  )
