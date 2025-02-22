(ns fuzz-dist.nemesis
  (:require
   [clojure.set :as set]
   [fuzz-dist.db :as db]
   [jepsen.nemesis :as nemesis]
   [jepsen.nemesis.combined :as nc]
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
        :start-dc2dc-netfail (let [nodes (shuffle (:nodes test))
                                   src   (first  nodes)
                                   dest  (second nodes)]
                               (net/drop! (:net test) test src dest))

        :stop-dc2dc-netfail  (net/heal! (:net test) test)))

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
     :perf  {:name "isolated 1 dc"
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
     :f     (nemesis/partitioner (fn grudge [nodes]
                                   (nemesis/complete-grudge
                                    (reduce (fn [acc node] (conj acc #{node}))
                                            #{}
                                            nodes))))

     :perf  {:name "isolated all dcs"
             :start #{gen-start}
             :stop  #{gen-stop}
             :color tomato}}))

(defn dc2dc-netfail
  [opts]
  (let [gen-start :start-dc2dc-netfail
        gen-stop  :stop-dc2dc-netfail]
    {:start gen-start
     :stop  gen-stop
     :msgs  {gen-start :start-dc2dc-netfail
             gen-stop  :stop-dc2dc-netfail}
     :f     (dc2dc-nemesis)
     :perf  {:name "dc2dc netfail"
             :start #{gen-start}
             :stop  #{gen-stop}
             :color orangered}}))

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

(def all-nemeses
  {:partition-maj-min  partition-maj-min
   :partition-maj-ring partition-maj-ring
   :partition-bridge   partition-bridge
   :isolated-dc        isolated-dc
   :isolated-all       isolated-all})

(def all-noops
  {:noop-quiesce    noop-quiesce
   :noop-final-read noop-final-read})

(defn some-nemesis
  "Merges together given nemeses and all noops to a singled composed nemesis."
  [nemeses opts]
  (nemesis/compose
   (reduce (fn [acc [_ nemesis]] (let [nem (nemesis opts)]
                                   (assoc acc
                                          (:msgs nem) (:f nem))))

           {}
           (merge (select-keys all-nemeses nemeses) all-noops))))

(defn some-perf
  "Merges together all given nemeses perfs and all noops into a set."
  [nemeses opts]
  (reduce (fn [acc [_ nemesis]] (let [nem (nemesis opts)]
                                  (conj acc
                                        (:perf nem))))
          #{}
          (merge (select-keys all-nemeses nemeses) all-noops)))

(defn g-set-nemesis-package
  "A full nemesis package. Options are those for
  jepsen.nemesis.combined/nemesis-package."
  [opts]
  (nc/compose-packages
   [(nc/partition-package opts)]))
