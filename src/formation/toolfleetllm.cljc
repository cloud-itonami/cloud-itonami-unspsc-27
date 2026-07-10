(ns formation.toolfleetllm
  "ToolFleet-LLM client -- the *contained intelligence node* (the direct
  analog of cloud-itonami-isic-6910's Registrar-LLM).

  It normalizes tool intake, drafts a per-class inspection checklist +
  safety spec-basis citation, inspects a tool for defects (grounding every
  verdict in measured sensor readings), drafts the enrollment / return-to-
  service / retirement actions, and drafts a rental checkout. CRITICAL: it
  is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited + the sensor readings it grounded on),
  never a committed record or a real fleet actuation. Every output is
  censored downstream by `formation.governor` before anything touches the
  SSoT, and :fleet/enroll / :fleet/return-to-service / :fleet/retire /
  :rental/checkout proposals NEVER auto-commit at any phase -- see README
  Actuation.

  Like the reference advisor, this is a deterministic mock so the actor
  graph runs offline and the governor contract is exercised end-to-end. In
  production this calls a real LLM (kotoba-llm or equivalent) with the same
  proposal shape; the governor's sensor-basis check is what catches a real
  LLM that tries to self-attest a defect verdict with no measured basis.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis / sensor-basis gates
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation if it touches a real fleet actuation
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [formation.facts :as facts]
            [formation.telemetry :as telemetry]
            [formation.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it does
  not invent condition-grade, fleet-id or class. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "工具レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :application/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-tool-class
  "Per-class inspection checklist + spec-basis draft. `:no-spec?` injects the
  failure mode: proposing a checklist for a class with NO official spec-basis
  in formation.facts -- the ToolFleetGovernor must reject this (never invent
  a class's safety standard)."
  [db {:keys [subject no-spec?]}]
  (let [t (store/tool db subject)
        cls (if no-spec? "UNKN" (:tool-class t))
        sb (facts/spec-basis cls)]
    (if (nil? sb)
      {:summary    (str cls " の公式 safety spec-basis が見つかりません")
       :rationale  "formation.facts に未登録の工具クラス。検査要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:tool-id subject :class cls :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str cls " (" (:owner-authority sb) ") 向け必要検査 "
                        (count (:required-inspections sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 安全根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:tool-id subject :class cls
                    :checklist (:required-inspections sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- inspect-tool
  "Defect inspection draft. The mock advisor is HONEST about its evidence:
  it cites the tool's actual sensor readings as its :sensor-basis and lets
  telemetry/grounds-verdict? decide whether they cover the class's required
  sensor surface. A tool flagged :safety-hazard? with grounded readings ->
  :safety-defect (:high severity). A clean tool with grounded readings ->
  :clear. Anything without grounded readings -> :needs-data (the honest 'I
  cannot determine yet' verdict, exempt from the governor's sensor-basis
  gate). A real (llm-advisor) LLM that lies -- claiming :clear with no
  basis, or rating a hazard :low -- is exactly what the governor's
  sensor-basis-violations check exists to catch."
  [db {:keys [subject]}]
  (let [t (store/tool db subject)
        cls (:tool-class t)
        reads (store/sensor-readings db subject)
        ids (mapv :reading-id reads)
        sb (facts/spec-basis cls)
        grounded? (telemetry/grounds-verdict? cls subject ids reads facts/sensor-metrics)]
    (cond
      (nil? t)
      {:summary "対象工具が見つかりません" :rationale "no tool record"
       :cites [] :effect :inspection/set
       :value {:tool-id subject :verdict :needs-data :severity :none
               :sensor-basis [] :findings "no tool record"}
       :stake nil :confidence 0.0}

      (:safety-hazard? t)
      (if grounded?
        {:summary    (str (:nickname t) ": 安全欠陥フラグ検出、センサー裏付けあり")
         :rationale  (str "安全フラグ + センサー読み値がクラス " cls " の必要メトリクスを覆盖。"
                          "法的根拠: " (:legal-basis sb))
         :cites      [(:legal-basis sb) (:provenance sb)]
         :effect     :inspection/set
         :value      {:tool-id subject :verdict :safety-defect :severity :high
                      :sensor-basis ids :findings "flagged hazard confirmed by readings"}
         :stake      nil
         :confidence 0.92}
        {:summary    (str (:nickname t) ": 安全欠陥フラグあるもセンサー裏付け不足")
         :rationale  "安全フラグがあるが確認に必要なセンサーデータが揃っていない。"
         :cites      [:safety-flag]
         :effect     :inspection/set
         :value      {:tool-id subject :verdict :needs-data :severity :none
                      :sensor-basis [] :findings "flagged hazard, sensor data incomplete"}
         :stake      nil
         :confidence 0.4})

      grounded?
      {:summary    (str (:nickname t) ": 検査クリア、センサー裏付けあり")
       :rationale  (str "センサー読み値がクラス " cls " の必要メトリクスを覆盖。安全基準: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :inspection/set
       :value      {:tool-id subject :verdict :clear :severity :none
                    :sensor-basis ids :findings "all required sensor metrics within safe band"}
       :stake      nil
       :confidence 0.9}

      :else
      {:summary    (str (:nickname t) ": センサーデータ不足で確定できない")
       :rationale  "クラスの必要センサーメトリクスを覆盖する読み値が無い。確定するまでクリアにできない。"
       :cites      [:sensor-coverage]
       :effect     :inspection/set
       :value      {:tool-id subject :verdict :needs-data :severity :none
                    :sensor-basis ids :findings "insufficient sensor coverage"}
       :stake      nil
       :confidence 0.4})))

(defn- propose-enrollment
  "Draft the enrollment action (first entry into the rental pool -- assigns
  fleet-id). ALWAYS :stake :actuation -- a real fleet act. See README
  Actuation: no phase ever adds this op to :auto (formation.phase); the
  governor also always escalates on :actuation. Two independent layers."
  [db {:keys [subject]}]
  (let [t (store/tool db subject)
        assessment (store/assessment-of db subject)
        checklist-ok? (and assessment (facts/required-inspections-satisfied?
                                        (:tool-class t) (:checklist assessment)))
        sb (facts/spec-basis (:tool-class t))]
    {:summary    (str (:nickname t) " を " (:tool-class t) " としてレンタル_POOLに登録する準備"
                      (when-not checklist-ok? "(検査チェックリスト未充足)"))
     :rationale  (if assessment (str "spec-basis: " (:spec-basis assessment)) "assessment未実施")
     :cites      (if (and assessment sb) [(:legal-basis sb) (:provenance sb)] [])
     :effect     :fleet/enroll-submitted
     :value      {:application-id subject}
     :stake      :actuation
     :confidence (if checklist-ok? 0.9 0.3)}))

(defn- propose-return-to-service
  "Draft a return-to-service after maintenance. ALWAYS :stake :actuation --
  submitted to the same real rental system as the original enrollment.
  Cites the class's official safety standard (G2 discipline), so an RTS
  cannot rely only on 'there is a fleet-id' -- it must point at the standard
  that governs returning this class to service."
  [db {:keys [subject changed-fields maintenance-summary serviced-by effective-date]}]
  (let [t (store/tool db subject)
        sb (facts/spec-basis (:tool-class t))]
    (if (and (:fleet-id t) sb)
      {:summary    (str (:nickname t) " (" (:fleet-number t) ") の return-to-service 案: " (pr-str (keys changed-fields)))
       :rationale  (str "既存登録 " (:fleet-number t) " への追記型整備記録。安全根拠: " (:legal-basis sb))
       :cites      [(:fleet-number t) (:legal-basis sb) (:provenance sb)]
       :effect     :fleet/return-to-service-submitted
       :value      {:application-id subject :changed-fields changed-fields
                    :maintenance-summary (or maintenance-summary "routine maintenance")
                    :serviced-by (or serviced-by "tech-1")
                    :effective-date effective-date}
       :stake      :actuation
       :confidence 0.9}
      {:summary    (str (:nickname t) " は return-to-service できません")
       :rationale  (if (:fleet-id t)
                     (str (:tool-class t) " の公式 safety spec-basis が見つかりません")
                     "fleet-id が無い = 未登録。")
       :cites      []
       :effect     :fleet/return-to-service-submitted
       :value      {:application-id subject :changed-fields changed-fields
                    :maintenance-summary (or maintenance-summary "routine maintenance")
                    :serviced-by (or serviced-by "tech-1")
                    :effective-date effective-date}
       :stake      :actuation
       :confidence 0.2})))

(defn- propose-retirement
  "Draft a tool retirement (terminal). ALWAYS :stake :actuation. A target
  that was never enrolled has nothing to retire, and an already-retired
  target cannot be retired twice -- both are the governor's hard
  :fleet/retire-specific checks."
  [db {:keys [subject reason effective-date]}]
  (let [t (store/tool db subject)
        sb (facts/spec-basis (:tool-class t))]
    (cond
      (nil? (:fleet-id t))
      {:summary "未登録のため retire できません" :rationale "fleet-id が無い"
       :cites [] :effect :fleet/retire-submitted
       :value {:application-id subject :reason reason :effective-date effective-date}
       :stake :actuation :confidence 0.2}

      (= :retired (:status t))
      {:summary (str (:nickname t) " は既に退役済みです") :rationale "二重 retire の防止"
       :cites [] :effect :fleet/retire-submitted
       :value {:application-id subject :reason reason :effective-date effective-date}
       :stake :actuation :confidence 0.2}

      (nil? sb)
      {:summary (str (:tool-class t) " の公式 safety spec-basis が見つからず retire できません")
       :rationale "formation.facts に未登録のクラス。退役手続きの根拠を推測で作らない。"
       :cites [] :effect :fleet/retire-submitted
       :value {:application-id subject :reason reason :effective-date effective-date}
       :stake :actuation :confidence 0.2}

      :else
      {:summary    (str (:nickname t) " (" (:fleet-number t) ") の退役案: " reason)
       :rationale  (str "既存登録 " (:fleet-number t) " への追記型退役記録。安全根拠: " (:legal-basis sb))
       :cites      [(:fleet-number t) (:legal-basis sb) (:provenance sb)]
       :effect     :fleet/retire-submitted
       :value      {:application-id subject :reason reason :effective-date effective-date}
       :stake      :actuation
       :confidence 0.9})))

(defn- propose-checkout
  "Draft a rental checkout (hand a tool to a renter). Reversible (the tool
  comes back) so it is NOT :stake :actuation, but it still never
  auto-commits at any phase (formation.phase puts no op but intake in :auto),
  so it always escalates to a human handover confirmation. The governor
  hard-blocks it on an open safety defect, an incomplete inspection, or an
  already-rented tool."
  [db {:keys [subject renter]}]
  (let [t (store/tool db subject)]
    {:summary    (str (:nickname t) " を " renter " へ貸し出す準備")
     :rationale  (if (:fleet-id t) "登録済み工具の貸出" "未登録工具")
     :cites      (when (:fleet-id t) [(:fleet-number t)])
     :effect     :rental/checkout-applied
     :value      {:application-id subject :renter renter}
     :stake      nil
     :confidence (if (:fleet-id t) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :application/intake      (normalize-intake db request)
    :tool/assess             (assess-tool-class db request)
    :defect/inspect          (inspect-tool db request)
    :fleet/enroll            (propose-enrollment db request)
    :fleet/return-to-service (propose-return-to-service db request)
    :fleet/retire            (propose-retirement db request)
    :rental/checkout         (propose-checkout db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  ([] (mock-advisor {}))
  ([_opts]
   (reify Advisor (-advise [_ st req] (infer st req)))))

(def ^:private system-prompt
  (str "あなたは工具 fleet レンタル/整備エージェントの助言者です。与えられた事実のみに"
       "基づき、提案を1つだけEDNマップで返します。説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:application/upsert|:assessment/set|:inspection/set|:fleet/enroll-submitted|:fleet/return-to-service-submitted|:fleet/retire-submitted|:rental/checkout-applied) "
       ":stake(:actuation か nil) :confidence(0..1)。\n"
       "重要: 検査 verdict が :clear または :safety-defect の場合は、必ず実際のセンサー読み値の "
       "reading-id を :value の :sensor-basis として引用すること。根拠の無い安全/欠陥主張は禁止。"
       "spec-basis が無いクラスは :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :tool/assess             {:tool (store/tool st subject)}
    :defect/inspect          {:tool (store/tool st subject)
                              :readings (store/sensor-readings st subject)}
    :fleet/enroll            {:tool (store/tool st subject)
                              :assessment (store/assessment-of st subject)}
    :fleet/return-to-service {:tool (store/tool st subject)}
    :fleet/retire            {:tool (store/tool st subject)}
    :rental/checkout         {:tool (store/tool st subject)}
    {:tool (store/tool st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence noop so the ToolFleetGovernor escalates/holds -- an
  LLM hiccup can never auto-enroll or auto-rent."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :toolfleetllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
