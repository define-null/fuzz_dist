(ns fuzz-dist.core
  (:require
   [aleph.http :as http]
   [cheshire.core :as json]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.logging :refer :all]
   [fuzz-dist [db :as db]
    [nemesis :as nemesis]
    [util :as util]]
   [jepsen [cli :as cli]
    [checker :as checker]
    [client :as client]
    [generator :as gen]
    [tests :as tests]]
   [jepsen.checker.timeline :as timeline]
   [jepsen.os.debian :as debian]
   [manifold.stream :as s]))

(defn g-set-read [_ _]
  (repeat {:type :invoke, :f :read, :value nil}))
(defn g-set-add  [_ _]
  (map (fn [x] {:type :invoke, :f :add, :value (str x)}) (range)))

(defn ws-invoke
  "Invokes the op over the ws connection.
  On the BEAM side a :cowboy_websocket_handler dispatches to an Elixir @behavior."
  [conn mod fun op]
  (s/try-put! conn
              (json/generate-string
               {:mod mod
                :fun fun
                :args op})
              5000)

  (json/parse-string @(s/try-take! conn 60000) true)
  ;; TODO: catch timeout
  )

(defrecord GSetClient [conn]
  client/Client
  (open! [this test node]
    (info node "Client/open" (util/node-url node))
    (assoc this :conn @(http/websocket-client (util/node-url node))))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
      :add (let [resp (ws-invoke conn :g_set :add op)]
             (case (:type resp)
               "ok"   (assoc op :type :ok)
               "fail" (assoc op :type :fail, :error (:error resp))))
      :read (let [resp (ws-invoke conn :g_set :read op)]
              (case (:type resp)
                ;; sort returned set for human readability, json unorders
                "ok"   (assoc op :type :ok,   :value (sort (:value resp)))
                "fail" (assoc op :type :fail, :error (:error resp))))))

  (teardown! [this test])

  (close! [_ test]
    (s/close! conn)))

(defn g-set-workload
  "Constructs a workload for a grow-only set, given options from the CLI
  test constructor."
  [opts]
  {:client    (GSetClient. nil)
   :generator (gen/mix [(map (fn [x] {:type :invoke, :f :add, :value (str x)}) (range))
                        (repeat {:type :invoke, :f :read, :value nil})])
   :final-generator (gen/each-thread {:type :invoke, :f :read, :value nil})
   :checker (checker/set-full)})

(defn g-set-package
  "Construct a
    :generator
    :nemesis
    :perf
  using a jepsen.nemesis.combined/nemesis-package"
  [db workload opts]
  (let [nemesis-package (nemesis/g-set-nemesis-package {:db       db
                                                        :interval (:nemesis-interval opts)
                                                        :faults   (:nemesis opts)
                                                        :partition {:targets [:one :minority-third :majority :majorities-ring]}})
        generator       (->> (if (pos? (:rate opts))
                               (gen/stagger (/ (:rate opts)) (:generator workload))
                               (gen/sleep (:time-limit opts)))
                             (gen/nemesis (:generator nemesis-package))
                             (gen/time-limit (:time-limit opts)))
        ; If this workload has a final generator, end the nemesis, wait for
        ; recovery, and perform final ops.
        generator (if-let [final (:final-generator workload)]
                    (gen/phases generator
                                (gen/nemesis (:final-generator nemesis-package))
                                (gen/log "Waiting for recovery...")
                                (gen/sleep 5)
                                (gen/clients final))
                    generator)]
    {:generator generator
     :nemesis (:nemesis nemesis-package)
     :perf    (:perf    nemesis-package)}))

(defn gen-rand-nemesis
  [opts]
  (let [nemeses       (case (:faults opts)
                        #{:all} (set (keys nemesis/all-nemeses))
                        #{:none} #{}
                        (:faults opts))
        nemesis       ((nemesis/all-nemeses (rand-nth (seq nemeses))) opts)
        [[pre-min, pre-max]
         [dur-min, dur-max]
         [post-min, post-max]] (:faults-times opts)
        nemesis-pre-quiet  (+ pre-min
                              (rand-int (+ (- pre-max
                                              pre-min)
                                           1)))
        nemesis-duration   (+ dur-min
                              (rand-int (+ (- dur-max
                                              dur-min)
                                           1)))
        nemesis-post-quiet (+ post-min
                              (rand-int (+ (- post-max
                                              post-min)
                                           1)))]
    (gen/phases
     (gen/sleep nemesis-pre-quiet)
     {:type :info, :f (:start nemesis)}
     (gen/sleep nemesis-duration)
     {:type :info, :f (:stop nemesis)}
     (gen/sleep nemesis-post-quiet))))

(defn g-set-compose
  "Construct a
    :generator
    :nemesis
    :perf
  using jepsen.nemesis.compose"
  [db workload opts]
  {:nemesis (nemesis/full-nemesis opts)
   :perf    (nemesis/full-perf opts)
   :generator  (gen/phases
                (->> (if (pos? (:rate opts))
                       (gen/stagger (/ (:rate opts)) (:generator workload))
                       (gen/sleep (:time-limit opts)))
                     (gen/nemesis (gen/cycle
                                   (fn [] (gen-rand-nemesis opts))))
                     (gen/time-limit (:time-limit opts)))

                ;; TODO :final-generator pattern
                (map (fn [[_ nem]]
                       (let [nemesis (nem opts)]
                         (gen/nemesis {:type :info, :f (:stop nemesis)})))
                     nemesis/all-nemeses)

                (gen/log "Let database quiesce...")
                (gen/nemesis {:type :info, :f :start-quiesce})
                (gen/sleep 5)
                (gen/nemesis {:type :info, :f :stop-quiesce})

                (gen/log "Final read...")
                (gen/sleep 1)
                (gen/nemesis {:type :info, :f :start-final-read})
                (gen/clients (gen/each-thread {:type :invoke :f :read :value nil}))
                (gen/nemesis {:type :info, :f :stop-final-read}))})

(def workloads
  "A map of workload names to functions that construct workloads, given opts."
  {:g-set g-set-workload})

(defn fuzz-dist-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
                      :concurrency, ...), constructs a test map."
  [opts]
  (let [db            (db/db :git)
        workload-name (:workload opts)
        workload      ((workloads workload-name) opts)
        package       (if (:nemesis opts)
                        (g-set-package db workload opts)
                        (g-set-compose db workload opts))]

    (merge tests/noop-test
           opts
           workload
           {:name       (str "fuzz-dist-AntidoteDB"
                             "-" (count (:nodes opts)) "-dc"
                             "-" (seq (:faults opts)) "@" (:faults-times opts)
                             "-" (:time-limit opts) "s"
                             "-" (:rate opts) "s"
                             "-" workload-name)
            :nodes      (:nodes opts)
            :os         debian/os
            :db         db
            :client     (:client workload)
            :nemesis    (:nemesis package)
            :pure-generators true
            :generator  (:generator package)
            :checker    (checker/compose
                         {:workload   (:checker workload)
                          :perf       (checker/perf
                                       {:nemeses (:perf package)})
                          :timeline   (timeline/html)
                          :stats      (checker/stats)
                          :exceptions (checker/unhandled-exceptions)})})))

(def nemeses
  "A set of valid nemeses you can pass at the CLI."
  #{:partition})

(def test-opt-spec
  "Options for single tests."
  [["-w" "--workload NAME" "What workload to run."
    :default :g-set
    :parse-fn keyword
    :validate [workloads (cli/one-of workloads)]]])

(def opt-spec
  "Additional command line options."
  [[nil "--nemesis FAULTS" "A comma-separated list of packaged faults to inject."
    ;; :default #{:partition} let --faults default?
    :parse-fn (fn [string]
                (->> (str/split string #"\s*,\s*")
                     (map keyword)
                     set))
    :validate [(partial every? nemeses) (cli/one-of nemeses)]]

   [nil "--nemesis-interval SECONDS" "How many seconds between nemesis operations, on average?"
    :default  10
    :parse-fn read-string
    :validate [pos? "Must be positive"]]

   [nil "--faults FAULTS" "A comma-separated list of specific faults to inject."
    :default #{:all}
    :parse-fn (fn [string]
                (->> (str/split string #"\s*,\s*")
                     (map keyword)
                     set))
    :validate [(partial every? nemesis/all-nemeses) (cli/one-of nemesis/all-nemeses)]]

   [nil "--faults-times [[T,T] [T,T] [T,T]" "[Minimum, Maximum] of [Pre-quiet, Duration, Post-quiet] times."
    :default  [[1,1] [5,5] [1,1]]
    :parse-fn read-string
    ;; TODO :validate [#(and (number? %) (pos? %)) "Must be a positive number"]
    ]

   ["-r" "--rate HZ" "Approximate number of requests per second, per thread."
    :default  10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]])

(defn opt-fn
  "Post-processes the parsed CLI options structure."
  [parsed]
  (if (= #{:all} (:faults parsed))
    (assoc parsed :faults (set (keys nemesis/all-nemeses)))
    parsed))

(defn all-tests
  "Takes parsed CLI options and constructs a sequence of test options, by
  combining all workloads, faults, and fault duration range ."
  [opts]
  (let [faults                (:faults opts)
        [[pre-min,pre-max]
         [dur-min,dur-max]
         [post-min,post-max]] (:faults-times opts)
        durations             (range dur-min (+ dur-max 1))
        workloads             [(:workload opts)]
        counts                (range (:test-count opts))]
    (->> (for [i counts, w workloads, f faults, d durations]
           (assoc opts
                  :workload w
                  :faults #{f}
                  :faults-times [[pre-min,pre-max]
                                 [d,d]
                                 [post-min,post-max]]))
         (map fuzz-dist-test))))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
                browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  fuzz-dist-test
                                         :opt-spec (concat test-opt-spec opt-spec)
                                         :opt-fn   opt-fn})
                   (cli/test-all-cmd    {:tests-fn all-tests
                                         :opt-spec (concat test-opt-spec opt-spec)
                                         :opt-fn   opt-fn})
                   (cli/serve-cmd))
            args))
