(ns formation.governor-contract-test
  "The ToolFleetGovernor contract as executable tests -- the tool-fleet
  analog of cloud-itonami-isic-6910's governor contract. The single
  invariant under test:

    ToolFleet-LLM never enrolls/services/rents a tool the ToolFleetGovernor
    would reject, every fleet actuation NEVER auto-commits at any phase,
    every safety/condition claim is sensor-grounded, and every decision
    (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [formation.store :as store]
            [formation.facts :as facts]
            [formation.operation :as op]))

(defn- fresh
  "A fresh [db actor] pair. Defaults to MemStore; pass `store/datomic-
  seed-db` to run the SAME actor flow against DatomicStore too."
  ([] (fresh store/seed-db))
  ([db-ctor]
   (let [db (db-ctor)]
     [db (op/build db)])))

(def operator {:actor-id "op-1" :actor-role :fleet-tech :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- enroll-tool-1!
  "Drive tool-1 all the way to :in-service (assess -> approve, inspect ->
  approve, enroll -> approve). Shared setup for the tests below."
  [actor]
  (exec-op actor "setup-a" {:op :tool/assess :subject "tool-1"} operator)
  (approve! actor "setup-a")
  (exec-op actor "setup-b" {:op :defect/inspect :subject "tool-1"} operator)
  (approve! actor "setup-b")
  (exec-op actor "setup-c" {:op :fleet/enroll :subject "tool-1"} operator)
  (approve! actor "setup-c"))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :application/intake :subject "tool-1"
                   :patch {:id "tool-1" :status :ready}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :ready (:status (store/tool db "tool-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest tool-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :tool/assess :subject "tool-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "tool-1")))))))

(deftest fabricated-class-is-held
  (testing "a tool/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :tool/assess :subject "tool-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "tool-1")) "no assessment written"))))

(deftest defect-inspection-grounds-a-clear-verdict-and-escalates
  (testing "tool-1 has covering sensor readings -> :clear verdict cites them; still escalates (human sees every inspection)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :defect/inspect :subject "tool-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t4")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :clear (:verdict (store/inspection-of db "tool-1"))))
        (is (seq (:sensor-basis (store/inspection-of db "tool-1"))) "clear verdict carries a cited sensor basis")))))

(deftest defect-inspection-without-sensor-coverage-is-needs-data
  (testing "tool-3 has NO sensor readings -> honest :needs-data verdict (not a fabricated :clear)"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :defect/inspect :subject "tool-3"} operator)]
      (is (= :interrupted (:status res)))
      (approve! actor "t5")
      (is (= :needs-data (:verdict (store/inspection-of db "tool-3")))))))

(deftest enrollment-without-anything-is-held
  (testing "enroll with no assessment, no inspection -> HOLD (no spec-basis citation among others)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :fleet/enroll :subject "tool-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (:fleet-id (store/tool db "tool-1"))) "nothing enrolled"))))

(deftest enrollment-after-assessment-but-before-inspection-is-held
  (testing "assessment clean, but tool never inspected -> HOLD (inspection-incomplete), not a silent pass"
    (let [[db actor] (fresh)]
      (exec-op actor "t7a" {:op :tool/assess :subject "tool-1"} operator)
      (approve! actor "t7a")
      (let [res (exec-op actor "t7" {:op :fleet/enroll :subject "tool-1"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:inspection-incomplete} (-> (store/ledger db) last :basis)))
        (is (nil? (:fleet-id (store/tool db "tool-1"))) "nothing enrolled")))))

(deftest enrollment-with-a-partial-checklist-is-held
  (testing "the full checklist must be satisfied -- a partial assessment does not pass"
    (let [[db actor] (fresh)]
      ;; seed a partial assessment (only one of the required inspections) directly,
      ;; plus a clean inspection, so ONLY the checklist gate is in play.
      (store/commit-record! db {:effect :assessment/set :path ["tool-1"]
                                :payload {:class "PWR-DRILL" :checklist [(first (facts/inspection-checklist "PWR-DRILL"))]
                                          :spec-basis "https://www.osha.gov/"}})
      (store/commit-record! db {:effect :inspection/set :path ["tool-1"]
                                :payload {:tool-id "tool-1" :verdict :clear :severity :none :sensor-basis ["r-1a"]}})
      (let [res (exec-op actor "t7c" {:op :fleet/enroll :subject "tool-1"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:incomplete-checklist} (-> (store/ledger db) last :basis)))))))

(deftest enrollment-always-escalates-then-human-decides
  (testing "a clean, fully-assessed + inspected enrollment still ALWAYS interrupts -- actuation is never auto"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t8" {:op :fleet/enroll :subject "tool-1"} operator)]
      (is (= :hold (get-in r1 [:state :disposition])) "no assess/inspect yet -> hold, not interrupt")
      ;; now do the real flow
      (enroll-tool-1! actor)
      (is (= :in-service (:status (store/tool db "tool-1"))))
      (is (= 1 (count (store/fleet-history db))) "one draft fleet record"))))

(deftest open-safety-defect-blocks-enrollment
  (testing "a tool whose latest inspection is a :high/:safety-critical safety defect is HARD-blocked from enrollment"
    (let [[db actor] (fresh)]
      ;; tool-2 (ANG-GRINDER) screens to :safety-defect :high (hazard flag + grounded readings)
      (exec-op actor "t9a" {:op :tool/assess :subject "tool-2"} operator)
      (approve! actor "t9a")
      (exec-op actor "t9b" {:op :defect/inspect :subject "tool-2"} operator)
      (approve! actor "t9b")
      (is (= :safety-defect (:verdict (store/inspection-of db "tool-2"))) "sanity: inspection recorded the defect")
      (let [res (exec-op actor "t9" {:op :fleet/enroll :subject "tool-2"} operator)]
        (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
        (is (not= :interrupted (:status res)))
        (is (some #{:open-safety-defect} (-> (store/ledger db) last :basis)))
        (is (nil? (:fleet-id (store/tool db "tool-2"))) "never enrolled")))))

;; ----------------------------- rental + double-rental -----------------------------

(deftest rental-checkout-works-then-double-rental-is-held
  (testing "a clean enrolled tool can be checked out (escalate -> approve), and never rented twice"
    (let [[db actor] (fresh)]
      (enroll-tool-1! actor)
      (let [r1 (exec-op actor "t10" {:op :rental/checkout :subject "tool-1" :renter "contractor-a"} operator)]
        (is (= :interrupted (:status r1)))
        (let [r2 (approve! actor "t10")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :rented (:rental-state (store/tool db "tool-1"))))))
      (let [res (exec-op actor "t10b" {:op :rental/checkout :subject "tool-1" :renter "contractor-b"} operator)]
        (is (= :hold (get-in res [:state :disposition])) "double-rental -> HARD hold")
        (is (some #{:already-rented} (-> (store/ledger db) last :basis)))))))

;; ----------------------------- return-to-service -----------------------------

(defn- drift-class-to-unknown!
  "Simulates a data-consistency edge case: an enrolled tool's :tool-class
  drifts to one with no spec-basis. fleet-id/status stay set from the
  original valid enrollment, so only the spec-basis check protects rts/retire
  here. Deliberately bypasses the actor (direct store/commit-record!) --
  post-enrollment-intake-violations blocks intake from touching an
  in-service tool, so this cannot happen through the actor's own ops."
  [db subject]
  (store/commit-record! db {:effect :application/upsert :value {:id subject :tool-class "UNKN"}}))

(deftest return-to-service-requires-an-enrolled-tool
  (testing "a tool with no fleet-id has nothing to return-to-service -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :fleet/return-to-service :subject "tool-1"
                                    :changed-fields {:maintenance-notes "x"}
                                    :maintenance-summary "svc" :effective-date "2026-07-10"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-fleet-id} (-> (store/ledger db) first :basis))))))

(deftest return-to-service-cannot-smuggle-a-status-change
  (testing "smuggling :status :retired alongside a maintenance note -> HARD :return-to-service-forbidden-field
            (ADR Addendum 14 translated: maintenance-record smuggling)"
    (let [[db actor] (fresh)]
      (enroll-tool-1! actor)
      (let [res (exec-op actor "t12" {:op :fleet/return-to-service :subject "tool-1"
                                      :changed-fields {:maintenance-notes "svc" :status :retired}
                                      :maintenance-summary "svc" :effective-date "2026-07-10"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:return-to-service-forbidden-field} (-> (store/ledger db) last :basis)))
        (is (= :in-service (:status (store/tool db "tool-1"))) "status untouched")))))

(deftest return-to-service-cannot-smuggle-fleet-id-or-class
  (testing "fleet-id/fleet-number/tool-class/rental-state are registry-assigned/lifecycle -- never RTS-editable"
    (let [[db actor] (fresh)]
      (enroll-tool-1! actor)
      (doseq [forbidden-patch [{:fleet-id "FAKE"} {:fleet-number "X"} {:tool-class "UNKN"} {:rental-state :rented}]]
        (let [res (exec-op actor (str "t12h-" (name (first (keys forbidden-patch))))
                           {:op :fleet/return-to-service :subject "tool-1"
                            :changed-fields forbidden-patch
                            :maintenance-summary "svc" :effective-date "2026-07-10"} operator)]
          (is (= :hold (get-in res [:state :disposition])) (str forbidden-patch " must be rejected"))
          (is (some #{:return-to-service-forbidden-field} (-> (store/ledger db) last :basis))))))))

(deftest return-to-service-requires-a-spec-basis-even-with-a-fleet-id
  (testing "a fleet-id alone is not enough -- class must still have a citable spec-basis"
    (let [[db actor] (fresh)]
      (enroll-tool-1! actor)
      (drift-class-to-unknown! db "tool-1")
      (let [res (exec-op actor "t12c" {:op :fleet/return-to-service :subject "tool-1"
                                       :changed-fields {:maintenance-notes "x"}
                                       :maintenance-summary "svc" :effective-date "2026-07-10"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:no-spec-basis} (-> (store/ledger db) last :basis)))))))

(deftest return-to-service-escalates-then-human-decides
  (testing "a clean RTS on an enrolled tool ALWAYS interrupts -- actuation is never auto"
    (let [[db actor] (fresh)]
      (enroll-tool-1! actor)
      (let [r1 (exec-op actor "t13" {:op :fleet/return-to-service :subject "tool-1"
                                     :changed-fields {:condition-grade :a :maintenance-notes "new chuck"}
                                     :maintenance-summary "chuck replaced" :serviced-by "tech-2"
                                     :effective-date "2026-07-10"} operator)]
        (is (= :interrupted (:status r1)))
        (let [r2 (approve! actor "t13")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :a (:condition-grade (store/tool db "tool-1"))))
          (is (= 2 (count (store/fleet-history db))) "enrollment + one RTS")
          (is (= "return-to-service-draft" (get (last (store/fleet-history db)) "kind"))))))))

;; ----------------------------- retire -----------------------------

(deftest retire-requires-an-enrolled-tool
  (let [[db actor] (fresh)
        res (exec-op actor "t14" {:op :fleet/retire :subject "tool-1"
                                  :reason "eol" :effective-date "2026-08-01"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:no-fleet-id} (-> (store/ledger db) first :basis)))))

(deftest retire-and-double-retire
  (testing "retire escalates (actuation); a second retire is HARD-held (double-retire prevention)"
    (let [[db actor] (fresh)]
      (enroll-tool-1! actor)
      (let [r1 (exec-op actor "t15" {:op :fleet/retire :subject "tool-1"
                                     :reason "eol" :effective-date "2026-08-01"} operator)]
        (is (= :interrupted (:status r1)))
        (approve! actor "t15")
        (is (= :retired (:status (store/tool db "tool-1"))))
        (let [res (exec-op actor "t15b" {:op :fleet/retire :subject "tool-1"
                                         :reason "again" :effective-date "2026-09-01"} operator)]
          (is (= :hold (get-in res [:state :disposition])))
          (is (some #{:already-retired} (-> (store/ledger db) last :basis))))))))

;; ----------------------------- intake smuggling / forgery -----------------------------

(deftest post-enrollment-intake-cannot-smuggle-changes
  (testing "once :in-service, the auto-committing intake op must not rewrite the tool behind the actuation gate"
    (let [[db actor] (fresh)]
      (enroll-tool-1! actor)
      (let [before (store/tool db "tool-1")
            res (exec-op actor "t16" {:op :application/intake :subject "tool-1"
                                      :patch {:id "tool-1" :site "FAKE" :condition-grade :a}} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:post-enrollment-intake-blocked} (-> (store/ledger db) last :basis)))
        (is (= before (store/tool db "tool-1")) "tool completely unchanged")))))

(deftest intake-cannot-fabricate-an-enrolled-state
  (testing "the ONE auto-commit op must not mint a fake enrolled tool -- no assess, no inspect, no human
            (ADR Addendum 15 translated: return-to-service forgery)"
    (let [[db actor] (fresh)
          before (store/tool db "tool-1")
          res (exec-op actor "t17" {:op :application/intake :subject "tool-1"
                                    :patch {:id "tool-1" :status :in-service
                                            :fleet-id "FLTV00FAKE0000000099"}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:intake-forbidden-field} (-> (store/ledger db) last :basis)))
      (is (some #{:intake-forbidden-status} (-> (store/ledger db) last :basis)))
      (is (= before (store/tool db "tool-1")) "tool completely unchanged")
      (is (empty? (store/fleet-history db)) "no fleet record fabricated"))))

(deftest intake-cannot-target-a-different-tool-than-its-declared-subject
  (testing "a request declaring subject tool-3 whose patch's OWN :id names a DIFFERENT, already-enrolled tool
            must not silently rewrite that other tool (subject-confusion, ADR Addendum 15)"
    (let [[db actor] (fresh)]
      (enroll-tool-1! actor)
      (let [before (store/tool db "tool-1")
            res (exec-op actor "t18" {:op :application/intake :subject "tool-3"
                                      :patch {:id "tool-1" :site "REWRITTEN"}} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:intake-subject-mismatch} (-> (store/ledger db) last :basis)))
        (is (= before (store/tool db "tool-1")) "tool-1 unchanged")))))

(deftest intake-setting-status-to-ready-is-still-unaffected
  (testing "the intake-fabrication check only rejects :in-service/:retired -- ordinary pre-enrollment transitions still auto-commit"
    (let [[db actor] (fresh)
          res (exec-op actor "t19" {:op :application/intake :subject "tool-1"
                                    :patch {:id "tool-1" :status :ready}} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= :ready (:status (store/tool db "tool-1")))))))

;; ----------------------------- ledger answerability -----------------------------

(deftest every-decision-leaves-one-ledger-fact
  (let [[db actor] (fresh)]
    (exec-op actor "a" {:op :application/intake :subject "tool-1" :patch {:id "tool-1" :status :ready}} operator)
    (exec-op actor "b" {:op :tool/assess :subject "tool-1" :no-spec? true} operator)
    (is (= 2 (count (store/ledger db))) "one commit + one hold, both recorded")))

(deftest committed-ledger-fact-records-the-actual-approver
  (testing "an enrollment's ledger fact records WHO approved it -- the approver, not the requester"
    (let [[db actor] (fresh)]
      (enroll-tool-1! actor)
      ;; the last approve! in enroll-tool-1! used "op-1"; redo with a distinct approver:
      (exec-op actor "t20a" {:op :rental/checkout :subject "tool-1" :renter "c"} operator)
      (g/run* actor {:approval {:status :approved :by "supervisor-9"}} {:thread-id "t20a" :resume? true})
      (is (= "supervisor-9" (:approved-by (last (store/ledger db))))))))

;; ----------------------------- cross-backend parity -----------------------------

(deftest post-enrollment-intake-blocked-on-datomic-store-too
  (testing "the actuation-gate-bypass fix holds on DatomicStore, not just MemStore"
    (let [[db actor] (fresh store/datomic-seed-db)]
      (enroll-tool-1! actor)
      (let [before (store/tool db "tool-1")
            res (exec-op actor "d1" {:op :application/intake :subject "tool-1"
                                     :patch {:id "tool-1" :site "FAKE"}} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (= before (store/tool db "tool-1")))))))

(deftest full-lifecycle-on-datomic-store-too
  (testing "enroll -> rts -> retire, and double-retire hold, all behave identically on DatomicStore"
    (let [[db actor] (fresh store/datomic-seed-db)]
      (enroll-tool-1! actor)
      (is (= :in-service (:status (store/tool db "tool-1"))))
      (is (= 1 (count (store/fleet-history db))))
      (exec-op actor "d2" {:op :fleet/return-to-service :subject "tool-1"
                           :changed-fields {:maintenance-notes "svc"}
                           :maintenance-summary "svc" :effective-date "2026-07-10"} operator)
      (approve! actor "d2")
      (is (= 2 (count (store/fleet-history db))))
      (exec-op actor "d3" {:op :fleet/retire :subject "tool-1"
                           :reason "eol" :effective-date "2026-08-01"} operator)
      (approve! actor "d3")
      (is (= :retired (:status (store/tool db "tool-1"))))
      (is (= 3 (count (store/fleet-history db))))
      (let [res (exec-op actor "d4" {:op :fleet/retire :subject "tool-1"
                                     :reason "again" :effective-date "2026-09-01"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:already-retired} (-> (store/ledger db) last :basis)))
        (is (= 3 (count (store/fleet-history db))))))))
