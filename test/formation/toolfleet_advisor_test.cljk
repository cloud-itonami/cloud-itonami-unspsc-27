(ns formation.toolfleet-advisor-test
  "The real-inference advisor (langchain.model ChatModel), driven offline by
  langchain's mock-model. Proves: a real LLM proposal is parsed, still fully
  censored by the ToolFleetGovernor, and that an unparseable/garbage
  response, or one that fabricates a class spec, or one that answers a
  harmless-looking request with a mismatched higher-stakes :effect, or one
  that self-attests a :clear verdict with NO sensor basis, can never
  auto-enroll, auto-service or auto-rent."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [langchain.model :as model]
            [formation.toolfleetllm :as toolfleetllm]
            [formation.governor :as governor]
            [formation.store :as store]
            [formation.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :fleet-tech :phase 3})
(def assess-req {:op :tool/assess :subject "tool-1"})

(defn- advise-with [req content]
  (toolfleetllm/-advise (toolfleetllm/llm-advisor (model/mock-model [{:role :assistant :content content}]))
                        (store/seed-db) req))

(deftest clean-llm-assessment-is-parsed-and-accepted
  (let [p (advise-with assess-req
                       (str "{:summary \"PWR-DRILL 向け検査チェックリスト\" :rationale \"OSHA/ANSI 公式基準\" "
                            ":cites [\"ANSI/UL 60745-1\" \"https://www.osha.gov/laws-regs/regulations/standardnumber/1910/1910.243\"] "
                            ":effect :assessment/set "
                            ":value {:tool-id \"tool-1\" :class \"PWR-DRILL\" :checklist [] :spec-basis \"https://www.osha.gov/\"} "
                            ":stake nil :confidence 0.9}"))]
    (is (= :assessment/set (:effect p)))
    (is (seq (:cites p)))
    (testing "the governor accepts a proposal that actually cites a spec-basis"
      (is (:ok? (governor/check assess-req operator p (store/seed-db)))))))

(deftest llm-fabricating-a-class-is-rejected
  (testing "even a confident LLM can't invent a class's safety standard -- spec-basis gate holds"
    (let [p (advise-with assess-req
                         (str "{:summary \"UNKN 向け検査項目\" :rationale \"一般的な慣行に基づく推測\" "
                              ":cites [] :effect :assessment/set "
                              ":value {:tool-id \"tool-1\" :class \"UNKN\" :checklist [\"some item\"]} "
                              ":confidence 0.95}"))
          v (governor/check assess-req operator p (store/seed-db))]
      (is (:hard? v))
      (is (some #{:no-spec-basis} (map :rule (:violations v)))))))

(deftest llm-self-attesting-a-clear-verdict-with-no-sensor-basis-is-rejected
  (testing "the NEW telemetry hole-fix (defect self-attestation, ADR Addendum 12 translated): a real LLM
            that declares a tool :clear with an EMPTY :sensor-basis -- high confidence, plausible cites --
            is a HARD violation, not a low-confidence escalation. 'I rate this defect-free' with no measured
            basis is the same lie-shape as 'I declare :effect :filing'."
    (let [p (advise-with {:op :defect/inspect :subject "tool-1"}
                         (str "{:summary \"工具はクリア\" :rationale \"目視で問題なし\" "
                              ":cites [\"ANSI/UL 60745-1\"] :effect :inspection/set "
                              ":value {:tool-id \"tool-1\" :verdict :clear :severity :none :sensor-basis [] :findings \"looks fine\"} "
                              ":stake nil :confidence 0.95}"))
          v (governor/check {:op :defect/inspect :subject "tool-1"} operator p (store/seed-db))]
      (is (:hard? v))
      (is (some #{:no-sensor-basis} (map :rule (:violations v)))))))

(deftest llm-answering-an-assess-request-with-an-enroll-effect-is-rejected
  (testing "a harmless-looking :tool/assess request answered with :effect :fleet/enroll-submitted -- even
            with plausible cites and high confidence -- is a HARD violation, not just a low-confidence escalation"
    (let [p (advise-with assess-req
                         (str "{:summary \"PWR-DRILL の登録を提案\" :rationale \"OSHA 公式基準\" "
                              ":cites [\"ANSI/UL 60745-1\" \"https://www.osha.gov/\"] "
                              ":effect :fleet/enroll-submitted "
                              ":value {:application-id \"tool-1\"} "
                              ":stake nil :confidence 0.95}"))
          v (governor/check assess-req operator p (store/seed-db))]
      (is (:hard? v))
      (is (some #{:effect-mismatch} (map :rule (:violations v)))))))

(deftest effect-mismatch-cannot-actually-enroll-through-the-full-actor-graph
  (testing "end-to-end: a :tool/assess request whose LLM proposal declares :effect :fleet/enroll-submitted
            must HOLD outright (no interrupt, no approval step) and leave the tool completely untouched.
            Before this fix, an approver who thought they were approving a routine assessment would
            silently trigger a real enrollment with none of :fleet/enroll's own scrutiny ever run."
    (let [db (store/seed-db)
          before (store/tool db "tool-1")
          advisor (toolfleetllm/llm-advisor
                   (model/mock-model
                    [{:role :assistant
                      :content (str "{:summary \"PWR-DRILL の登録\" :rationale \"OSHA 公式基準\" "
                                    ":cites [\"ANSI/UL 60745-1\" \"https://www.osha.gov/\"] "
                                    ":effect :fleet/enroll-submitted "
                                    ":value {:application-id \"tool-1\"} "
                                    ":stake nil :confidence 0.95}")}]))
          actor (op/build db {:advisor advisor})
          res (g/run* actor {:request assess-req :context operator} {:thread-id "exploit"})]
      (is (= :done (:status res)) "settles immediately -- never reaches request-approval")
      (is (= :hold (get-in res [:state :disposition])))
      (is (= before (store/tool db "tool-1")) "tool completely unchanged")
      (is (empty? (store/fleet-history db)) "nothing enrolled")
      (is (nil? (store/assessment-of db "tool-1")) "no assessment written either"))))

(deftest unparseable-llm-output-never-auto-commits
  (testing "garbage / refusal -> safe noop at confidence 0 -> governor won't pass it"
    (let [p (advise-with assess-req "申し訳ございませんが、その工具クラスについてはお答えできません。")]
      (is (= :noop (:effect p)))
      (is (= 0.0 (:confidence p)))
      (is (not (:ok? (governor/check assess-req operator p (store/seed-db))))))))
