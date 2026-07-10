(ns formation.facts-test
  (:require [clojure.test :refer [deftest is]]
            [formation.facts :as facts]))

(deftest drill-class-has-a-spec-basis
  (is (some? (facts/spec-basis "PWR-DRILL")))
  (is (string? (:provenance (facts/spec-basis "PWR-DRILL"))))
  (is (seq (:sensor-metrics (facts/spec-basis "PWR-DRILL")))))

(deftest unknown-class-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "UNKN")))
  (is (nil? (facts/sensor-metrics "UNKN"))))

(deftest coverage-never-reports-a-missing-class-as-covered
  (let [report (facts/coverage ["PWR-DRILL" "UNKN" "ANG-GRINDER"])]
    (is (= 2 (:covered report)))
    (is (= ["UNKN"] (:missing-classes report)))
    (is (= ["ANG-GRINDER" "PWR-DRILL"] (:covered-classes report)))))

(deftest required-inspections-satisfied-needs-every-item
  (let [all (facts/inspection-checklist "PWR-DRILL")]
    (is (facts/required-inspections-satisfied? "PWR-DRILL" all))
    (is (not (facts/required-inspections-satisfied? "PWR-DRILL" (rest all))))
    (is (not (facts/required-inspections-satisfied? "UNKN" all)) "no spec-basis -> never satisfied")))
