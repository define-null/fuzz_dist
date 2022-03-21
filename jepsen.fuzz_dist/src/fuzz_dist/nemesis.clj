(ns fuzz-dist.nemesis
  (:require
   [fuzz-dist.db :as db]
   [jepsen.nemesis :as nemesis]
   [jepsen.net :as net]))

;; reds
(def lightcoral "#F08080")
(def indianred  "#CD5C5C")
(def crimson    "#DC143C")
;; oranges
(def coral     "#FF7F50")
(def tomato    "#FF6347")
(def orangered "#FF4500")
;; blues
(def cornflowerblue "#6495ED")
;; greens
(def mediumseagreen "#3CB371")

(defn noop-nemesis
  "A nemesis that does nothing."
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (assoc op :value :noop))
    (teardown! [this test])))

;; TODO: net/drop! stops test?!?
;; nemesis crash, signature mismatch?
(defn dc2dc-nemesis
  "A nemesis that creates a network failure between 2 random datacenters."
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)

    (invoke! [this test op]
      (case (:f op)
        :start-dc2dc-net-fail (net/drop! (:net test)
                                         test
                                         (rand-nth (:nodes test))
                                         (rand-nth (:nodes test)))
        :stop-dc2dc-net-fail (net/heal! (:net test)
                                        test)))

    (teardown! [this test])))

(defn partition-maj-min
  [opts]
  (let [gen-start :start-maj-min
        gen-stop  :stop-maj-min]
    {:start gen-start
     :stop  gen-stop
     :msgs  {gen-start :start
             gen-stop  :stop}
     :f     (nemesis/partition-random-halves)
     :perf  {:name "maj/min partition"
             :start #{gen-start}
             :stop  #{gen-stop}
             :color lightcoral}}))

(defn partition-maj-ring
  [opts]
  (let [gen-start :start-maj-ring
        gen-stop  :stop-maj-ring]
    {:start gen-start
     :stop  gen-stop
     :msgs  {gen-start :start
             gen-stop  :stop}
     :f     (nemesis/partition-majorities-ring)
     :perf  {:name "maj rings partition"
             :start #{gen-start}
             :stop  #{gen-stop}
             :color indianred}}))

(defn partition-bridge
  [opts]
  (let [gen-start :start-bridge
        gen-stop  :stop-bridge]
    {:start gen-start
     :stop  gen-stop
     :msgs  {gen-start :start
             gen-stop  :stop}
     :f     (nemesis/partitioner (comp nemesis/bridge shuffle))
     :perf  {:name "bridge partition"
             :start #{gen-start}
             :stop  #{gen-stop}
             :color crimson}}))

(defn isolated-dc
  [opts]
  (let [gen-start :start-isolated-dc
        gen-stop  :stop-isolated-dc]
    {:start gen-start
     :stop  gen-stop
     :msgs  {gen-start :start
             gen-stop  :stop}
     :f     (nemesis/partition-random-node)
     :perf  {:name "isolated dc"
             :start #{gen-start}
             :stop  #{gen-stop}
             :color coral}}))

(defn isolated-all
  [opts]
  (let [gen-start :start-isolated-all
        gen-stop  :stop-isolated-all]
    {:start gen-start
     :stop  gen-stop
     :msgs  {gen-start :start
             gen-stop  :stop}
     :f     (nemesis/partitioner (nemesis/complete-grudge [(:nodes opts) (:nodes opts)]))
     :perf  {:name "all isolated"
             :start #{gen-start}
             :stop  #{gen-stop}
             :color tomato}}))

(defn noop-quiesce
  [opts]
  (let [gen-start :start-quiesce
        gen-stop  :stop-quiesce]
    {:start gen-start
     :stop  gen-stop
     :msgs  {gen-start :start-quiesce
             gen-stop  :stop-quiesce}
     :f     (noop-nemesis)
     :perf  {:name "quiesce"
             :start #{gen-start}
             :stop  #{gen-stop}
             :color cornflowerblue}}))

(defn noop-final-read
  [opts]
  (let [gen-start :start-final-read
        gen-stop  :stop-final-read]
    {:start gen-start
     :stop  gen-stop
     :msgs  {gen-start :start-final-read
             gen-stop  :stop-final-read}
     :f     (noop-nemesis)
     :perf  {:name "final read"
             :start #{gen-start}
             :stop  #{gen-stop}
             :color mediumseagreen}}))

(def all-nemeses [partition-maj-min,
                  partition-maj-ring,
                  partition-bridge,
                  isolated-dc,
                  isolated-all])

(def all-noops [noop-quiesce,
                noop-final-read])

(defn full-nemesis
  "Merges together all nemeses and noops"
  [opts]
  (nemesis/compose
   (reduce (fn [acc nemesis] (let [nem (nemesis opts)]
                               (assoc acc
                                      (:msgs nem) (:f nem))))

           {}
           (into [] (concat all-nemeses all-noops)))))

(defn full-perf
  "Merges together all perfs"
  [opts]
  {:nemeses (reduce (fn [acc nemesis] (let [nem (nemesis opts)]
                                        (conj acc
                                              (:perf nem))))
                    #{}
                    (into [] (concat all-nemeses all-noops)))})