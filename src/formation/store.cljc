(ns formation.store
  "SSoT for the tool-fleet actor, behind a `Store` protocol so the backend
  is a swap, not a rewrite -- the same seam cloud-itonami-isic-6910 /
  gftd-talent-actor / ai-gftd-itonami use:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/formation/store_contract_test.clj), which is the whole point: the
  actor, the ToolFleetGovernor and the audit ledger never know which SSoT
  they run on.

  The ledger stays append-only on every backend: 'who serviced what, for
  which tool, on what safety basis, approved by whom, with which sensor
  readings' is always a query over an immutable log -- the audit trail a
  customer trusting an operator with a tool's condition needs, and the
  evidence an operator needs if a rental is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [formation.registry :as registry]
            [formation.telemetry :as telemetry]
            [langchain.db :as d]))

(defprotocol Store
  (tool [s id])
  (all-tools [s])
  (inspection-of [s tool-id] "committed defect inspection verdict, or nil")
  (assessment-of [s tool-id] "committed class inspection checklist, or nil")
  (sensor-readings [s tool-id] "all sensor readings for a tool")
  (ledger [s])
  (fleet-history [s] "the append-only fleet-record history (formation.registry drafts)")
  (next-sequence [s class-code] "next fleet-number sequence for a class")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-tools [s tools] "replace/seed the tool directory (map id->tool)")
  (with-readings [s readings] "replace/seed the sensor-reading set (seq of readings)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained tool set so the actor + tests run offline.
  tool-1 is a clean standard drill with full sensor coverage; tool-2 is a
  high-criticality grinder flagged with a chassis safety hazard (still has
  sensor coverage, so its :safety-defect verdict is GROUNDED); tool-3 is a
  spare drill with NO sensor readings (screens to :needs-data, never :clear,
  without also tripping :safety-hazard?)."
  []
  {:tools
   {"tool-1" {:id "tool-1" :tool-class "PWR-DRILL" :nickname "Big Red"
              :condition-grade :b :site "site-a" :status :intake
              :safety-hazard? false :rental-state :available}
    "tool-2" {:id "tool-2" :tool-class "ANG-GRINDER" :nickname "Sparky"
              :condition-grade :c :site "site-a" :status :intake
              :safety-hazard? true :rental-state :available}
    "tool-3" {:id "tool-3" :tool-class "PWR-DRILL" :nickname "Reserve Drill"
              :condition-grade :c :site "site-b" :status :intake
              :safety-hazard? false :rental-state :available}}
   :officers nil
   :readings
   [(telemetry/reading {:reading-id "r-1a" :tool-id "tool-1" :metric :battery-voltage
                         :value 18.2 :unit "V" :sensor-id "s-1" :timestamp "2026-07-09T10:00:00Z"})
    (telemetry/reading {:reading-id "r-1b" :tool-id "tool-1" :metric :chassis-vibration
                         :value 0.3 :unit "g" :sensor-id "s-2" :timestamp "2026-07-09T10:00:00Z"})
    (telemetry/reading {:reading-id "r-1c" :tool-id "tool-1" :metric :guard-presence
                         :value 1 :unit "bool" :sensor-id "s-3" :timestamp "2026-07-09T10:00:00Z"})
    (telemetry/reading {:reading-id "r-2a" :tool-id "tool-2" :metric :wheel-rpm
                         :value 11000 :unit "rpm" :sensor-id "s-4" :timestamp "2026-07-09T10:00:00Z"})
    (telemetry/reading {:reading-id "r-2b" :tool-id "tool-2" :metric :guard-presence
                         :value 1 :unit "bool" :sensor-id "s-5" :timestamp "2026-07-09T10:00:00Z"})
    (telemetry/reading {:reading-id "r-2c" :tool-id "tool-2" :metric :vibration
                         :value 4.1 :unit "g" :sensor-id "s-6" :timestamp "2026-07-09T10:00:00Z"})
    (telemetry/reading {:reading-id "r-2d" :tool-id "tool-2" :metric :spindle-runout
                         :value 0.02 :unit "mm" :sensor-id "s-7" :timestamp "2026-07-09T10:00:00Z"})]})

;; ----------------------------- shared fleet logic -----------------------------

(defn- enroll!
  "Backend-agnostic `:fleet/enroll-submitted` -- looks up the tool via the
  protocol, drafts the fleet record, returns {:result .. :tool-patch ..}."
  [s tool-id]
  (let [t (tool s tool-id)
        seq-n (next-sequence s (:tool-class t))
        result (registry/register-enrollment
                (:id t) (:tool-class t) (:nickname t)
                (:condition-grade t) (:site t) seq-n)]
    {:result result
     :tool-patch {:status :in-service
                  :fleet-id (get result "fleet_id")
                  :fleet-number (get result "fleet_number")
                  :rental-state :available}}))

(defn- return-to-service!
  "Backend-agnostic `:fleet/return-to-service-submitted` -- drafts the
  append-only return-to-service record via formation.registry, merges the
  maintenance changed-fields + :status :in-service into the tool. The RTS
  record is APPENDED to fleet-history; the enrollment record is never
  overwritten (append-only, matching 6910's amendment discipline)."
  [s tool-id changed-fields maintenance-summary serviced-by effective-date]
  (let [t (tool s tool-id)
        result (registry/register-return-to-service
                (:fleet-number t) maintenance-summary serviced-by effective-date)]
    {:result result
     :tool-patch (merge changed-fields {:status :in-service :rental-state :available})}))

(defn- retire!
  "Backend-agnostic `:fleet/retire-submitted` -- drafts the append-only
  retirement record, sets the tool's :status :retired. fleet-history is
  appended, never overwritten (terminal event, but the record persists)."
  [s tool-id reason effective-date]
  (let [t (tool s tool-id)
        result (registry/register-retirement (:fleet-number t) reason effective-date)]
    {:result result
     :tool-patch {:status :retired :rental-state :available}}))

(defn- checkout!
  "Backend-agnostic `:rental/checkout-applied` -- flips the tool's
  :rental-state to :rented. The double-rental guard itself lives in the
  governor (:already-rented HARD hold); this is the pure mutation that runs
  only AFTER the governor has cleared it."
  [_s tool-id renter]
  {:result {:tool-id tool-id :renter renter}
   :tool-patch {:rental-state :rented}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (tool [_ id] (get-in @a [:tools id]))
  (all-tools [_] (sort-by :id (vals (:tools @a))))
  (inspection-of [_ id] (get-in @a [:inspections id]))
  (assessment-of [_ id] (get-in @a [:assessments id]))
  (sensor-readings [_ tool-id] (filterv #(= (:tool-id %) tool-id) (:readings @a)))
  (ledger [_] (:ledger @a))
  (fleet-history [_] (:fleet @a))
  (next-sequence [_ class-code]
    (get-in @a [:sequences class-code] 0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :application/upsert
      (swap! a update-in [:tools (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :inspection/set
      (swap! a assoc-in [:inspections (first path)] payload)

      :telemetry/ingest
      (swap! a update :readings conj (telemetry/reading value))

      :fleet/enroll-submitted
      (let [tool-id (first path)
            {:keys [result tool-patch]} (enroll! s tool-id)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences (:tool-class (get-in state [:tools tool-id]))] (fnil inc 0))
                       (update-in [:tools tool-id] merge tool-patch)
                       (update :fleet registry/append result))))
        result)

      :fleet/return-to-service-submitted
      (let [tool-id (first path)
            {:keys [changed-fields maintenance-summary serviced-by effective-date]} value
            {:keys [result tool-patch]} (return-to-service! s tool-id changed-fields maintenance-summary serviced-by effective-date)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:tools tool-id] merge tool-patch)
                       (update :fleet registry/append result))))
        result)

      :fleet/retire-submitted
      (let [tool-id (first path)
            {:keys [reason effective-date]} value
            {:keys [result tool-patch]} (retire! s tool-id reason effective-date)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:tools tool-id] merge tool-patch)
                       (update :fleet registry/append result))))
        result)

      :rental/checkout-applied
      (let [tool-id (first path)
            {:keys [tool-patch]} (checkout! s tool-id (:renter value))]
        (swap! a update-in [:tools tool-id] merge tool-patch)
        {:tool-id tool-id :renter (:renter value)})
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-tools [s tools] (when (seq tools) (swap! a assoc :tools tools)) s)
  (with-readings [s readings] (when (seq readings) (swap! a assoc :readings (vec readings))) s))

(defn seed-db
  "A MemStore seeded with the demo tool set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :inspections {} :ledger [] :sequences {} :fleet []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (inspection/assessment payloads, ledger facts, fleet
  records, sensor readings) are stored as EDN strings so `langchain.db`
  doesn't expand them into sub-entities."
  {:tool/id        {:db/unique :db.unique/identity}
   :ledger/seq     {:db/unique :db.unique/identity}
   :fleet/seq      {:db/unique :db.unique/identity}
   :sequence/class {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- tool->tx [{:keys [id tool-class nickname condition-grade site status
                         safety-hazard? rental-state fleet-id fleet-number]}]
  (cond-> {:tool/id id}
    tool-class       (assoc :tool/class tool-class)
    nickname         (assoc :tool/nickname nickname)
    (some? condition-grade) (assoc :tool/condition-grade condition-grade)
    site             (assoc :tool/site site)
    status           (assoc :tool/status status)
    (some? safety-hazard?) (assoc :tool/safety-hazard? safety-hazard?)
    rental-state     (assoc :tool/rental-state rental-state)
    fleet-id         (assoc :tool/fleet-id fleet-id)
    fleet-number     (assoc :tool/fleet-number fleet-number)))

(def ^:private tool-pull
  [:tool/id :tool/class :tool/nickname :tool/condition-grade :tool/site
   :tool/status :tool/safety-hazard? :tool/rental-state :tool/fleet-id :tool/fleet-number])

(defn- pull->tool [m]
  (when (:tool/id m)
    (cond-> {:id (:tool/id m) :tool-class (:tool/class m) :nickname (:tool/nickname m)
             :site (:tool/site m) :status (:tool/status m)
             :rental-state (:tool/rental-state m)}
      (:tool/condition-grade m) (assoc :condition-grade (:tool/condition-grade m))
      (contains? m :tool/safety-hazard?) (assoc :safety-hazard? (boolean (:tool/safety-hazard? m)))
      (:tool/fleet-id m) (assoc :fleet-id (:tool/fleet-id m))
      (:tool/fleet-number m) (assoc :fleet-number (:tool/fleet-number m)))))

(defrecord DatomicStore [conn]
  Store
  (tool [_ id]
    (pull->tool (d/pull (d/db conn) tool-pull [:tool/id id])))
  (all-tools [_]
    (->> (d/q '[:find [?id ...] :where [?e :tool/id ?id]] (d/db conn))
         (map #(pull->tool (d/pull (d/db conn) tool-pull [:tool/id %])))
         (sort-by :id)))
  (inspection-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?tid
                :where [?i :inspection/tool-id ?tid] [?i :inspection/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?tid
                :where [?a :assessment/tool-id ?tid] [?a :assessment/payload ?p]]
              (d/db conn) id)))
  (sensor-readings [_ tool-id]
    (vec (filter #(= (:tool-id %) tool-id)
                 (mapv dec* (d/q '[:find [?r ...] :where [?e :reading/payload ?r]] (d/db conn))))))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (fleet-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :fleet/seq ?s] [?e :fleet/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ class-code]
    (or (d/q '[:find ?n . :in $ ?c
              :where [?e :sequence/class ?c] [?e :sequence/next ?n]]
            (d/db conn) class-code)
        0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :application/upsert
      (d/transact! conn [(tool->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/tool-id (first path) :assessment/payload (enc payload)}])

      :inspection/set
      (d/transact! conn [{:inspection/tool-id (first path) :inspection/payload (enc payload)}])

      :telemetry/ingest
      (d/transact! conn [{:reading/reading-id (:reading-id value)
                          :reading/payload (enc (telemetry/reading value))}])

      :fleet/enroll-submitted
      (let [tool-id (first path)
            {:keys [result tool-patch]} (enroll! s tool-id)
            class-code (:tool-class (tool s tool-id))
            next-n (inc (next-sequence s class-code))]
        (d/transact! conn
                     [(tool->tx (assoc tool-patch :id tool-id))
                      {:sequence/class class-code :sequence/next next-n}
                      {:fleet/seq (count (fleet-history s)) :fleet/record (enc (get result "record"))}])
        result)

      :fleet/return-to-service-submitted
      (let [tool-id (first path)
            {:keys [changed-fields maintenance-summary serviced-by effective-date]} value
            {:keys [result tool-patch]} (return-to-service! s tool-id changed-fields maintenance-summary serviced-by effective-date)]
        (d/transact! conn
                     [(tool->tx (assoc tool-patch :id tool-id))
                      {:fleet/seq (count (fleet-history s)) :fleet/record (enc (get result "record"))}])
        result)

      :fleet/retire-submitted
      (let [tool-id (first path)
            {:keys [reason effective-date]} value
            {:keys [result tool-patch]} (retire! s tool-id reason effective-date)]
        (d/transact! conn
                     [(tool->tx (assoc tool-patch :id tool-id))
                      {:fleet/seq (count (fleet-history s)) :fleet/record (enc (get result "record"))}])
        result)

      :rental/checkout-applied
      (let [tool-id (first path)
            {:keys [tool-patch]} (checkout! s tool-id (:renter value))]
        (d/transact! conn [(tool->tx (assoc tool-patch :id tool-id))])
        {:tool-id tool-id :renter (:renter value)})
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-tools [s tools]
    (when (seq tools) (d/transact! conn (mapv tool->tx (vals tools)))) s)
  (with-readings [s readings]
    (when (seq readings)
      (d/transact! conn (mapv (fn [r] {:reading/reading-id (:reading-id r)
                                       :reading/payload (enc r)})
                              readings)))
    s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:tools .. :readings ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [tools readings]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-tools tools) (with-readings readings)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo tool set -- the Datomic-backed analog
  of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
