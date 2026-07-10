(ns formation.registry-test
  "Conformance tests for formation.registry -- the fleet-id / ISO 7064
  MOD 97-10 arithmetic ported near-verbatim from cloud-itonami-isic-6910's
  formation.registry (itself from matsurigoto's corp-registry). These tests
  mirror 6910's registry conformance suite so the port stays provably
  equivalent, plus the tool-fleet-specific enrollment/RTS/retire records."
  (:require [clojure.test :refer [deftest is testing]]
            [formation.registry :as r]))

(deftest certificate-is-a-draft-not-a-real-enrollment
  (let [result (r/register-enrollment "tool-1" "PWR-DRILL" "Big Red" :a "site-a" 1)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_fleet"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest fleet-id-roundtrip-validates
  (let [fid (r/assign-fleet-id "FLTV" "000000000001")]
    (is (= (count fid) 20))
    (is (r/validate-fleet-id fid))))

(deftest fleet-id-check-digits-make-mod97-one-for-many-entities
  (doseq [n (range 1 50)]
    (let [fid (r/assign-fleet-id "FLTV" (format "%012d" n))]
      (is (r/validate-fleet-id fid) fid))))

(deftest fleet-id-corruption-detected
  (let [fid (r/assign-fleet-id "FLTV" "000000000042")
        bad (vec fid)
        bad (assoc bad 8 (if (not= (nth bad 8) \Z) \Z \Y))]
    (is (r/validate-fleet-id fid))
    (is (not (r/validate-fleet-id (apply str bad))))))

(deftest fleet-id-rejects-bad-length-and-chars
  (is (not (r/validate-fleet-id "TOOSHORT")))
  (is (not (r/validate-fleet-id "FLTV00000000000001*9")))
  (is (not (r/validate-fleet-id 12345))))

(deftest default-fleet-id-does-not-collide-across-tools-at-the-same-sequence
  (testing "two entirely unrelated tools, each the Nth enrollment in THEIR OWN class
            (formation.store/next-sequence is per-class, so this is the common case,
            not an edge case -- every class's very first enrollment is sequence 0),
            must never be issued the same fleet-id just because they share a sequence
            number (ADR Addendum 13 -- LEI global-uniqueness translated to fleet-id
            uniqueness / duplicate-fleet-entry prevention)"
    (let [a (r/register-enrollment "tool-1" "PWR-DRILL" "Big Red" :a "site-a" 0)
          b (r/register-enrollment "tool-2" "ANG-GRINDER" "Sparky" :b "site-a" 0)]
      (is (not= (get a "fleet_id") (get b "fleet_id")))
      (is (r/validate-fleet-id (get a "fleet_id")))
      (is (r/validate-fleet-id (get b "fleet_id")))))
  (testing "holds across a broader sweep of tools and sequence numbers"
    (let [combos (for [t ["tool-1" "tool-2" "tool-3" "tool-4" "tool-5"]
                       s (range 5)]
                   (get (r/register-enrollment t "PWR-DRILL" "n" :a "site" s) "fleet_id"))]
      (is (= (count combos) (count (distinct combos))) "no two distinct (tool, sequence) pairs share a fleet-id")
      (is (every? r/validate-fleet-id combos)))))

(deftest enrollment-assigns-fleet-number-and-fleet-id
  (let [result (r/register-enrollment "tool-7" "PWR-DRILL" "Big Red" :a "site-a" 7)]
    (is (= (get result "fleet_number") "PWR-DRILL-00000007"))
    (is (r/validate-fleet-id (get result "fleet_id")))
    (is (= (get-in result ["record" "immutable"]) true))
    (is (= (get-in result ["record" "kind"]) "enrollment-draft"))))

(deftest enrollment-validation-rules
  (let [bad-args [["" "PWR-DRILL" "n" :a "s"]
                  ["t" "" "n" :a "s"]
                  ["t" "PWR-DRILL" "" :a "s"]
                  ["t" "PWR-DRILL" "n" :bad "s"]
                  ["t" "PWR-DRILL" "n" :a ""]]]
    (doseq [[tid cls nick grade site] bad-args]
      (is (thrown? Exception
                   (r/register-enrollment tid cls nick grade site 1))))))

(deftest return-to-service-is-append-only
  (let [enr (r/register-enrollment "t" "PWR-DRILL" "n" :a "s" 1)
        hist (r/append [] enr)
        rts (r/register-return-to-service (get enr "fleet_number") "chuck replaced" "tech-1" "2026-07-10")
        hist2 (r/append hist rts)]
    (is (and (= (count hist) 1) (= (count hist2) 2)))
    (is (= (get-in hist2 [0 "kind"]) "enrollment-draft"))
    (is (= (get-in hist2 [1 "kind"]) "return-to-service-draft"))))

(deftest retirement-is-append-only-and-preserves-history
  (let [enr (r/register-enrollment "t" "PWR-DRILL" "n" :a "s" 2)
        hist (r/append [] enr)
        rts (r/register-return-to-service (get enr "fleet_number") "svc" "tech-1" "2026-07-10")
        hist2 (r/append hist rts)
        ret (r/register-retirement (get enr "fleet_number") "end of life" "2026-08-01")
        hist3 (r/append hist2 ret)]
    (is (= 3 (count hist3)))
    (is (= (get-in hist3 [2 "kind"]) "retirement-draft"))
    (is (= (get-in hist3 [2 "reason"]) "end of life"))
    (is (= (get-in hist3 [0 "record_id"]) (get-in hist3 [2 "fleet_number"]))
        "retirement references the original enrollment's record_id (the fleet_number)")))

(deftest retirement-validation-rules
  (is (thrown? Exception (r/register-retirement "" "reason" "2026-08-01")))
  (is (thrown? Exception (r/register-retirement "PWR-DRILL-00000001" "" "2026-08-01"))))
