(ns formation.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic / kotoba-server' a configuration change,
  not a rewrite. Sensor readings are carried by the same protocol, so
  MemStore ≡ DatomicStore parity covers the telemetry layer too."
  (:require [clojure.test :refer [deftest is testing]]
            [formation.store :as store]
            [formation.telemetry :as telemetry]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Big Red" (:nickname (store/tool s "tool-1"))))
      (is (= "PWR-DRILL" (:tool-class (store/tool s "tool-1"))))
      (is (= :intake (:status (store/tool s "tool-1"))))
      (is (false? (:safety-hazard? (store/tool s "tool-1"))))
      (is (true? (:safety-hazard? (store/tool s "tool-2"))))
      (is (= ["tool-1" "tool-2" "tool-3"] (mapv :id (store/all-tools s))))
      (is (nil? (store/inspection-of s "tool-1")))
      (is (nil? (store/assessment-of s "tool-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/fleet-history s)))
      (is (zero? (store/next-sequence s "PWR-DRILL")))
      (is (= 3 (count (store/sensor-readings s "tool-1"))) "drill has 3 covering readings")
      (is (= 4 (count (store/sensor-readings s "tool-2"))) "grinder has 4 covering readings")
      (is (zero? (count (store/sensor-readings s "tool-3"))) "reserve drill has no readings"))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :application/upsert
                                 :value {:id "tool-1" :status :ready}})
        (is (= :ready (:status (store/tool s "tool-1"))))
        (is (= "Big Red" (:nickname (store/tool s "tool-1"))) "nickname preserved"))
      (testing "assessment / inspection payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["tool-1"]
                                 :payload {:class "PWR-DRILL" :checklist ["a" "b"]}})
        (is (= {:class "PWR-DRILL" :checklist ["a" "b"]} (store/assessment-of s "tool-1")))
        (store/commit-record! s {:effect :inspection/set :path ["tool-1"]
                                 :payload {:tool-id "tool-1" :verdict :clear}})
        (is (= :clear (:verdict (store/inspection-of s "tool-1"))))
        (store/commit-record! s {:effect :inspection/set :path ["tool-1"]
                                 :payload {:tool-id "tool-1" :verdict :safety-defect :severity :high}})
        (is (= :high (:severity (store/inspection-of s "tool-1"))) "latest verdict wins"))
      (testing "telemetry ingest + read back"
        (store/commit-record! s {:effect :telemetry/ingest
                                 :value {:reading-id "r-new" :tool-id "tool-1"
                                         :metric :battery-voltage :value 17.9 :unit "V"
                                         :sensor-id "s-99" :timestamp "2026-07-10T01:00:00Z"}})
        (is (some #(= "r-new" (:reading-id %)) (store/sensor-readings s "tool-1"))))
      (testing "enrollment drafts a fleet record and advances the sequence"
        (store/commit-record! s {:effect :fleet/enroll-submitted :path ["tool-1"]})
        (is (= "PWR-DRILL-00000000" (get (first (store/fleet-history s)) "record_id")))
        (is (= "enrollment-draft" (get (first (store/fleet-history s)) "kind")))
        (is (= :in-service (:status (store/tool s "tool-1"))))
        (is (= 1 (count (store/fleet-history s))))
        (is (= 1 (store/next-sequence s "PWR-DRILL"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest rental-checkout-flips-rental-state-on-both-backends
  (doseq [[label s] (backends)]
    (testing label
      ;; tool-1 must be enrolled + inspected-clear before checkout is governor-clean;
      ;; at the STORE level checkout is a plain mutation regardless.
      (store/commit-record! s {:effect :application/upsert :value {:id "tool-1" :fleet-id "F" :fleet-number "PWR-DRILL-00000000"
                                                                   :status :in-service :rental-state :available}})
      (store/commit-record! s {:effect :rental/checkout-applied :path ["tool-1"]
                               :value {:application-id "tool-1" :renter "c-1"}})
      (is (= :rented (:rental-state (store/tool s "tool-1")))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/tool s "nope")))
    (is (= [] (store/all-tools s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/fleet-history s)))
    (is (zero? (store/next-sequence s "PWR-DRILL")))
    (is (zero? (count (store/sensor-readings s "tool-1"))))
    (store/with-tools s {"x" {:id "x" :tool-class "PWR-DRILL" :nickname "X" :condition-grade :a
                              :site "b" :status :intake :safety-hazard? false :rental-state :available}})
    (is (= "X" (:nickname (store/tool s "x"))))
    (store/with-readings s [(telemetry/reading {:reading-id "rx" :tool-id "x" :metric :battery-voltage
                                                :value 18 :unit "V" :sensor-id "s"})])
    (is (= 1 (count (store/sensor-readings s "x"))))))
