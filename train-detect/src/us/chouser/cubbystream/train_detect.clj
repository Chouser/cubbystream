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

(defn get-field [obj field-name]
  (let [f (.getDeclaredField (class obj) field-name)]
    (.setAccessible f true)
    (.get f obj)))

(defn ordinal-split-value [^smile.base.cart.OrdinalNode node]
  (double (get-field node "value")))

(def ^java.lang.reflect.Field ordinal-node-value
  (doto (.getDeclaredField OrdinalNode "value")
    (.setAccessible true)))

(defn- walk-tree [^Node node feature-names]
  (cond
    (instance? DecisionNode node)
    (let [output (int (.output ^DecisionNode node))]
      (format "return %d; // %s" output (if (= 1 output) "ADS" "GAME")))

    (instance? OrdinalNode node)
    (let [o-node ^OrdinalNode node
          feature-idx (.feature o-node)
          ;; Modern Smile uses .split() for numeric thresholds
          threshold (.get ordinal-node-value o-node)
          fname (nth feature-names feature-idx)]
      (str "if (" fname " <= " threshold ") {\n"
           (walk-tree (.trueChild o-node) feature-names)
           "\n} else {\n"
           (walk-tree (.falseChild o-node) feature-names)
           "\n}"))

    :else
    (throw (IllegalArgumentException. 
            (str "Unsupported tree node type: " (type node))))))

(defn- indent [text]
  (str/replace text #"(?m)^" "        "))

(defn- deserialize-smile-model [bytes]
  (with-open [bais (ByteArrayInputStream. bytes)
              ois (ObjectInputStream. bais)]
    (.readObject ois)))

(defn generate-java [model feature-names class-name]
  (let [;; 1. Pull the raw bytes out of the metadata map
        model-bytes (-> model :model-data :model-as-bytes)
        
        ;; 2. Reconstitute the live Smile DecisionTree instance
        ^DecisionTree raw-tree (deserialize-smile-model model-bytes)
        
        ;; 3. Crawl the nodes starting at the root
        tree-body (walk-tree (.root raw-tree) feature-names)]
    (str "public class " class-name " {\n\n"
         "    /**\n     * Optimized Audio Classifier\n     * @return 1 for ADS, 0 for GAME\n     */\n"
         "    public static int predict(" 
         (str/join ", " (map #(str "double " %) feature-names)) ") {\n"
         (indent tree-body) "\n"
         "    }\n"
         "}\n")))

(defn- walk-tree2 [^Node node feature-names]
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
       :true (walk-tree2 (.trueChild o-node) feature-names)
       :false (walk-tree2 (.falseChild o-node) feature-names)})

    :else
    (throw (IllegalArgumentException. 
            (str "Unsupported tree node type: " (type node))))))

(defn model->tree [model feature-names]
  (let [model-bytes (-> model :model-data :model-as-bytes)
        ^DecisionTree raw-tree (deserialize-smile-model model-bytes)]
    (walk-tree2 (.root raw-tree) feature-names)))

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

(defn train-pipeline2 [csv-dir]
  (let [files (->> (io/file csv-dir)
                   file-seq
                   (filter #(str/ends-with? (.getName ^java.io.File %) ".csv")))
        ;; Define exactly what features the tree is allowed to split on
        feature-keys [:energy_mean :energy_std :mid_high_ratio :flux_energy_ratio]
        feature-strings (map name feature-keys)]

    (println "Training...")
    (let [;; Fix 1: Pass {:key-fn keyword} to force string headers into keywords
          raw-ds (-> (apply tc/concat (map #(tc/dataset % {:key-fn keyword}) files))
                     (tc/order-by :timestamp_ms))

          processed-ds (-> raw-ds
                           fix-user-latency
                           clean-short-spans
                           add-features
                           (tc/map-columns :volume_mode (fn [v] (if (= v "ads") 1 0)))
                           ;; Fix 2: Isolate ONLY the target and training features
                           (tc/select-columns (conj feature-keys :volume_mode))
                           (ds-mod/set-inference-target :volume_mode))

          model (ml/train processed-ds {:model-type :smile.classification/decision-tree
                                        :max-nodes 24
                                        :split-rule :gini})]

      (println "Training complete.")
      (model->tree model feature-strings))))

(defn train-pipeline! [csv-dir]
  (let [files (->> (io/file csv-dir)
                   file-seq
                   (filter #(str/ends-with? (.getName ^java.io.File %) ".csv")))
        ;; Define exactly what features the tree is allowed to split on
        feature-keys [:energy_mean :energy_std :mid_high_ratio :flux_energy_ratio]
        feature-strings (map name feature-keys)]
    
    (println "Aggregating data...")
    (let [;; Fix 1: Pass {:key-fn keyword} to force string headers into keywords
          raw-ds (-> (apply tc/concat (map #(tc/dataset % {:key-fn keyword}) files))
                     (tc/order-by :timestamp_ms))
          
          processed-ds (-> raw-ds
                           fix-user-latency
                           clean-short-spans
                           add-features
                           (tc/map-columns :volume_mode (fn [v] (if (= v "ads") 1 0)))
                           ;; Fix 2: Isolate ONLY the target and training features
                           (tc/select-columns (conj feature-keys :volume_mode))
                           (ds-mod/set-inference-target :volume_mode))
          
          model (ml/train processed-ds {:model-type :smile.classification/decision-tree
                                       :max-nodes 24
                                       :split-rule :gini})]
      
      (println "Training complete. Writing AudioClassifier.java...")
      (spit "AudioClassifier.java" (generate-java model feature-strings "AudioClassifier"))
      (println "Success."))))

(comment

  (train-pipeline! "../logs/")
  (train-pipeline2 "../logs/")

  )