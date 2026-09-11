(ns formation.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean tool through intake
  -> class assessment -> defect inspection -> enrollment (always escalates)
  -> human approval -> commit -> a return-to-service after maintenance ->
  a retirement (both also always escalate), a rental checkout + the
  double-rental hold, then shows four HARD holds (an open safety defect on
  the grinder, an inspection-incomplete tool, a fabricated class, a
  sensor-less self-attested clear) that never reach a human at all, and
  prints the audit ledger + the draft fleet record history."
  (:require [langgraph.graph :as g]
            [formation.store :as store]
            [formation.operation :as op]
            [formation.facts :as facts]))

(def operator {:actor-id "op-1" :actor-role :fleet-tech :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== intake tool-1 (PWR-DRILL, clean) ==")
    (println (exec! actor "t1" {:op :application/intake :subject "tool-1"
                                :patch {:id "tool-1" :status :ready}} operator))

    (println "== tool/assess tool-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :tool/assess :subject "tool-1"} operator))
    (println (approve! actor "t2"))

    (println "== defect/inspect tool-1 (clean + sensor-grounded; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :defect/inspect :subject "tool-1"} operator))
    (println (approve! actor "t3"))

    (println "== fleet/enroll tool-1 (always escalates -- actuation) ==")
    (let [r (exec! actor "t4" {:op :fleet/enroll :subject "tool-1"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t4")))

    (println "== rental/checkout tool-1 (escalates -- handover) ==")
    (let [r (exec! actor "t4r" {:op :rental/checkout :subject "tool-1" :renter "contractor-a"} operator)]
      (println r)
      (println (approve! actor "t4r")))

    (println "== rental/checkout tool-1 AGAIN (already rented -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t4r2" {:op :rental/checkout :subject "tool-1" :renter "contractor-b"} operator))

    (println "== fleet/return-to-service tool-1: after-maintenance re-entry (always escalates) ==")
    ;; return the tool first via a direct store flip so RTS is exercisable on an available tool
    (store/commit-record! db {:effect :application/upsert :value {:id "tool-1" :rental-state :available}})
    (let [r (exec! actor "t4b" {:op :fleet/return-to-service :subject "tool-1"
                                :changed-fields {:condition-grade :a :maintenance-notes "new chuck"}
                                :maintenance-summary "chuck replaced" :serviced-by "tech-2"
                                :effective-date "2026-07-10"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t4b")))

    (println "== fleet/retire tool-1 (always escalates -- actuation) ==")
    (let [r (exec! actor "t4c" {:op :fleet/retire :subject "tool-1"
                                :reason "end of service life" :effective-date "2026-12-01"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t4c")))

    (println "== fleet/retire tool-1 AGAIN (already retired -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t4d" {:op :fleet/retire :subject "tool-1"
                                 :reason "second attempt" :effective-date "2027-01-01"} operator))

    (println "== defect/inspect tool-2 (ANG-GRINDER, safety hazard -> :safety-defect, escalates) ==")
    (println (exec! actor "t5" {:op :defect/inspect :subject "tool-2"} operator))

    (println "== fleet/enroll tool-2 (open safety-defect on file -> HARD hold, never reaches a human) ==")
    (store/commit-record! db {:effect :assessment/set :path ["tool-2"]
                              :payload {:class "ANG-GRINDER"
                                        :checklist (:required-inspections (facts/spec-basis "ANG-GRINDER"))
                                        :spec-basis "https://www.osha.gov/"}})
    (store/commit-record! db {:effect :inspection/set :path ["tool-2"]
                              :payload {:tool-id "tool-2" :verdict :safety-defect :severity :high
                                        :sensor-basis ["r-2a" "r-2b" "r-2c" "r-2d"]}})
    (println (exec! actor "t5b" {:op :fleet/enroll :subject "tool-2"} operator))

    (println "== tool/assess tool-3 (no spec-basis class -> HARD hold) ==")
    (store/commit-record! db {:effect :application/upsert :value {:id "tool-3" :tool-class "UNKN"}})
    (println (exec! actor "t6" {:op :tool/assess :subject "tool-3" :no-spec? true} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft fleet records ==")
    (doseq [r (store/fleet-history db)] (println r))))
