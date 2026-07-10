(ns formation.telemetry-test
  "The sensor-grounding layer (NEW -- no counterpart in the company-formation
  reference). These tests are the technical kernel of the 'defect
  self-attestation' hole-fix: an inspection verdict that asserts a condition
  (:clear/:safety-defect) is only as good as the cited readings' coverage of
  the class's required sensor surface."
  (:require [clojure.test :refer [deftest is testing]]
            [formation.telemetry :as telemetry]))

(def drill-metrics (constantly [:battery-voltage :chassis-vibration :guard-presence]))

(def drill-readings
  [(telemetry/reading {:reading-id "r1" :tool-id "t1" :metric :battery-voltage
                       :value 18.2 :unit "V" :sensor-id "s1"})
   (telemetry/reading {:reading-id "r2" :tool-id "t1" :metric :chassis-vibration
                       :value 0.3 :unit "g" :sensor-id "s2"})
   (telemetry/reading {:reading-id "r3" :tool-id "t1" :metric :guard-presence
                       :value 1 :sensor-id "s3"})])

(deftest reading-constructor-validates-shape
  (is (thrown? Exception (telemetry/reading {:reading-id "" :tool-id "t1" :metric :x :value 1 :sensor-id "s"})))
  (is (thrown? Exception (telemetry/reading {:reading-id "r" :tool-id "t1" :metric nil :value 1 :sensor-id "s"})))
  (is (thrown? Exception (telemetry/reading {:reading-id "r" :tool-id "t1" :metric :x :value nil :sensor-id "s"})))
  (is (= :battery-voltage (:metric (telemetry/reading {:reading-id "r" :tool-id "t1" :metric :battery-voltage :value 1 :sensor-id "s"})))))

(deftest full-coverage-grounds-a-verdict
  (is (true? (telemetry/grounds-verdict? "PWR-DRILL" "t1" ["r1" "r2" "r3"]
                                         drill-readings drill-metrics))))

(deftest partial-coverage-does-not-ground
  (testing "measured the battery but not the chassis -> not grounded"
    (is (not (telemetry/grounds-verdict? "PWR-DRILL" "t1" ["r1" "r2"]
                                         drill-readings drill-metrics)))))

(deftest empty-or-nil-cited-basis-never-grounds
  (is (not (telemetry/grounds-verdict? "PWR-DRILL" "t1" [] drill-readings drill-metrics)))
  (is (not (telemetry/grounds-verdict? "PWR-DRILL" "t1" nil drill-readings drill-metrics))))

(deftest cited-id-pointing-at-another-tool-grounds-nothing
  (testing "a cited reading that belongs to a different tool is not evidence for this tool"
    (let [reads (conj drill-readings
                      (telemetry/reading {:reading-id "r9" :tool-id "OTHER"
                                          :metric :guard-presence :value 1 :sensor-id "s9"}))]
      (is (not (telemetry/grounds-verdict? "PWR-DRILL" "t1" ["r1" "r2" "r9"]
                                           reads drill-metrics))))))

(deftest citing-the-same-reading-twice-does-not-widen-coverage
  (is (not (telemetry/grounds-verdict? "PWR-DRILL" "t1" ["r1" "r1" "r1"]
                                       drill-readings drill-metrics))))

(deftest class-with-no-defined-metrics-never-grounds
  (is (not (telemetry/grounds-verdict? "UNKNOWN" "t1" ["r1"] drill-readings (constantly nil)))))
