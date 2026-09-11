(ns formation.telemetry
  "Sensor-data ingestion + the grounding logic the ToolFleetGovernor uses
  to enforce business-model.md's invariant: 'public safety/condition claims
  must reference measured sensor data'. This namespace has NO counterpart
  in the company-formation reference (cloud-itonami-isic-6910 is
  robotics:false; UNSPSC segment 27 is robotics:true) -- it is the minimal,
  well-tested layer that makes a condition/safety *claim* provably
  traceable to measured sensor readings.

  Two responsibilities:

  1. Sensor-reading shape + constructor. A reading is an immutable measured
     fact: what metric, what value, which sensor, when, for which tool. The
     STORE owns persistence (both MemStore and DatomicStore carry readings
     through the same Store protocol, so MemStore ≡ DatomicStore parity
     covers telemetry too -- see store_contract_test); this namespace owns
     the shape, the validation, and the pure grounding logic.

  2. Pure grounding logic. `grounds-verdict?` answers: do the readings an
     inspection proposal cites as its `:sensor-basis` actually cover every
     sensor-metric this tool's class requires? This is the technical kernel
     of the 'defect self-attestation' hole-fix (ADR Addendum 12, translated):
     an untrusted advisor (a real, possibly-hallucinating LLM) must not be
     able to declare a tool defect-free (or defect-present) from thin air --
     every such verdict must be backed by cited readings whose metrics cover
     the class's required sensor surface.

  Grounding is REQUIRED for any inspection verdict that asserts a condition
  (:clear -- 'this tool is safe' / :safety-defect -- 'this tool has a
  specific defect'). A :needs-data verdict ('I could not determine') is the
  honest 'no basis yet' outcome -- it escalates for a human / re-inspection
  and is explicitly exempt, because requiring a basis for 'I don't know yet'
  would punish honesty.")

;; A sensor reading is a plain map (NOT a defrecord) so it round-trips
;; through pr-str / edn/read-string unchanged on BOTH Store backends -- a
;; defrecord would emit a tagged literal (#formation.telemetry.SensorReading
;; {...}) that edn/read-string cannot read back without a registered reader,
;; breaking the DatomicStore path.

(defn reading
  "Construct + validate a sensor reading. `metric` is a keyword matching a
  tool-class's :sensor-metrics (formation.facts). Throws on the shape errors
  that would silently corrupt the grounding check (missing ids / nil value /
  non-keyword metric). `opts` may carry :unit and :timestamp strings."
  [{:keys [reading-id tool-id metric value unit sensor-id timestamp]
    :or {unit "" timestamp nil}}]
  (when-not (and reading-id (not= reading-id ""))
    (throw (ex-info "sensor reading: reading-id required" {})))
  (when-not (and tool-id (not= tool-id ""))
    (throw (ex-info "sensor reading: tool-id required" {})))
  (when-not (keyword? metric)
    (throw (ex-info (str "sensor reading: metric must be a keyword, got " (pr-str metric)) {})))
  (when (nil? value)
    (throw (ex-info "sensor reading: value required (nil is not a measurement)" {})))
  (when-not (and sensor-id (not= sensor-id ""))
    (throw (ex-info "sensor reading: sensor-id required" {})))
  {:type :sensor-reading :reading-id reading-id :tool-id tool-id :metric metric
   :value value :unit (or unit "") :sensor-id sensor-id :timestamp timestamp})

(defn readings-by-id
  "Index a seq of readings by their reading-id (last wins on collision)."
  [readings]
  (into {} (map (juxt :reading-id identity)) readings))

(defn grounds-verdict?
  "Does `cited-ids` (the reading-ids an inspection proposal claims as its
  sensor-basis) actually ground a condition verdict for `class-code` on
  `tool-id`?

   - Every cited id must resolve to a REAL reading for `tool-id` (a cited id
     that points at another tool's reading, or at nothing, grounds nothing --
     it is the advisor naming evidence it does not have).
   - Every cited id must be DISTINCT (citing the same reading twice does not
     widen coverage).
   - The union of the resolved readings' metrics must cover EVERY
     sensor-metric `metrics-fn` (formation.facts/sensor-metrics) requires for
     this class. Partial coverage is not grounding: 'I measured the battery
     but not the chassis' does not substantiate 'this drill is safe'.

   `metrics-fn` is injected (class-code -> seq of metric keywords, or nil) so
   this namespace's tests do not couple to formation.facts. Returns true only
   on full coverage; nil/empty required metrics -> false (a class with no
   defined sensor surface can never be grounded through this gate)."
  [class-code tool-id cited-ids readings metrics-fn]
  (let [required (metrics-fn class-code)
        by-id (readings-by-id readings)]
    (and (seq required)
         (let [ids (seq cited-ids)
               resolved (keep (fn [rid]
                                (let [r (get by-id rid)]
                                  (when (and r (= (:tool-id r) tool-id)) r)))
                              ids)]
           (and ids
                (= (count resolved) (count (distinct ids)))
                (every? #(contains? (set (map :metric resolved)) %) required))))))
