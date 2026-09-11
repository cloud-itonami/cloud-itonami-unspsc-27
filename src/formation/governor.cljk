(ns formation.governor
  "ToolFleetGovernor -- the independent compliance layer that earns the
  ToolFleet-LLM the right to commit. The LLM has no notion of safety
  standards, sensor-evidence integrity or when a tool actually re-enters a
  rental pool, so this MUST be a separate system able to *reject* a proposal
  and fall back to HOLD -- the tool-fleet analog of cloud-itonami-isic-6910's
  RegistrarGovernor (and robotaxi's Minimal Risk Condition).

  The 6910 governor's eleven checks are translated one-for-one to the
  tool-fleet trust model (business-model.md), PLUS one check that has no
  6910 counterpart -- sensor-basis grounding -- because the tool-fleet
  domain is robotics:true and mandates that every safety/condition claim
  reference measured sensor data.

  Checks in priority order. The HARD ones cannot be overridden by a human
  approver; the SOFT ones escalate to a human who may approve:

    1. Effect matches op -- (6910 #1 / Addendum 12). Does the proposal's
                             :effect match the ONE legitimate effect for
                             the REQUEST's :op (`op->effect`)? Without this
                             first, an advisor could answer a harmless
                             :tool/assess request with :effect
                             :fleet/enroll-submitted and a human approving
                             what looks like an assessment would silently
                             enroll a tool.
    2. Sensor basis     -- (NEW, no 6910 counterpart; the Addendum-12
                             'self-attested effect' hole re-derived as
                             'defect self-attestation'). A :defect/inspect
                             proposal that ASSERTS a condition (:clear or
                             :safety-defect) must cite readings whose
                             metrics cover the class's required sensor
                             surface (formation.telemetry). 'I rate this
                             defect :low' with no sensor basis is the same
                             shape of lie as 'I declare :effect :filing' --
                             an untrusted advisor naming a conclusion its
                             evidence does not support. :needs-data ('I
                             could not determine') is exempt -- requiring a
                             basis for 'I don't know yet' punishes honesty.
    3. Spec basis       -- (6910 #2 / Addendum 6). An assess/enroll/
                             return-to-service/retire proposal must cite the
                             class's OFFICIAL safety standard
                             (formation.facts), not invent one.
    4. Open safety defect -- (6910 #3 / Addendum 7, sanctions -> safety
                             defect). An enroll/return-to-service/checkout
                             on a tool whose LATEST inspection is a
                             :safety-defect with :high/:safety-critical
                             severity is HARD held -- a serious-defect tool
                             never re-enters the rental pool automatically.
    5. Inspection complete -- (6910 #4 / Addendum 7, KYC-complete ->
                             inspection-complete). An enroll/rts/checkout
                             requires the tool's LATEST inspection verdict
                             to be :clear. A never-inspected tool (nil) or a
                             :needs-data/:low-defect tool is NOT clear --
                             the analog of 'nil != :clear' that 6910's
                             KYC-completeness check exists to catch.
    6. Checklist satisfied -- (6910 #5, document-complete). An enrollment
                             requires the class's required inspections to be
                             satisfied (the assessment checklist).
    7. Post-enrollment intake -- (6910 #6 / Addendum 9). Once a tool is
                             :in-service/:under-maintenance/:retired,
                             :application/intake (the only auto-commit op)
                             is blocked outright -- every further change
                             must go through :fleet/return-to-service or
                             :fleet/retire, which cite a spec-basis, are
                             inspection-gated and always require a human.
    8. Intake fabrication -- (6910 #7 / Addendum 15, intake forgery ->
                             return-to-service forgery). Even a pre-enrollment
                             intake patch may never set :fleet-id/
                             :fleet-number (assigned ONLY by a real
                             enrollment), never set :status to
                             :in-service/:retired (terminal actuation states
                             reached ONLY via enroll/retire), and its own
                             patch :id must match the request's :subject.
                             This is the structural prevention of
                             'return-to-service forgery' -- a tool marked
                             inspected/passing with zero governor clearance.
    9. Return-to-service target -- (6910 #8 / Addenda 4+14, amendment
                             smuggling -> maintenance-record smuggling).
                             :fleet/return-to-service needs an existing
                             fleet-id, a non-empty changed-fields map, and
                             must stay within `amendable-fields` -- it can
                             never smuggle :status/:fleet-id/:tool-class/
                             :rental-state through alongside a maintenance
                             note.
   10. Retire target      -- (6910 #9 / Addendum 5, double-dissolution ->
                             double-retire). :fleet/retire needs an existing
                             fleet-id and the tool must not already be
                             retired.
   11. Duplicate fleet entry -- (6910 #13, LEI global-uniqueness ->
                             double-enrollment / double-rental). An
                             :fleet/enroll on a tool already :in-service
                             (:already-enrolled), and an :rental/checkout on
                             a tool already :rented (:already-rented), are
                             both HARD held. (The complementary fleet-id
                             derivation collision fix lives in
                             formation.registry's default-fleet-id-suffix.)
   12. Confidence floor   -- (6910 #10, SOFT). LLM confidence below
                             threshold -> escalate.
   13. Actuation gate     -- (6910 #11, SOFT). :stake :actuation -> always
                             escalate; never auto, at any phase."
  (:require [formation.facts :as facts]
            [formation.telemetry :as telemetry]
            [formation.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  :actuation = a real enrollment / return-to-service / retire / handover.
  There is exactly one member on purpose: actuation is not a spectrum."
  #{:actuation})

(def safety-critical-severity
  "Defect severities that ALWAYS hard-block re-rental and force human
  sign-off (business-model.md: any :high/:safety-critical defect finding
  escalates to human sign-off before a tool re-enters the rental pool)."
  #{:high :safety-critical})

;; ----------------------------- checks -----------------------------

(def op->effect
  "The ONE legitimate `:effect` a proposal may declare for each op --
  `formation.operation/commit-record` takes `:effect` straight from the
  (untrusted) advisor proposal with no cross-check of its own, so this table
  is the only thing standing between 'the request says :tool/assess' and
  'the SSoT mutation that actually runs is :fleet/enroll-submitted'. Every
  other check keys off the REQUEST's :op -- a mismatched :effect would let
  all of THEIR scrutiny run against the wrong (lower-stakes) op while a
  different, higher-stakes effect gets committed (6910 Addendum 12,
  translated)."
  {:application/intake           :application/upsert
   :tool/assess                  :assessment/set
   :defect/inspect               :inspection/set
   :fleet/enroll                 :fleet/enroll-submitted
   :fleet/return-to-service      :fleet/return-to-service-submitted
   :fleet/retire                 :fleet/retire-submitted
   :rental/checkout              :rental/checkout-applied})

(defn- effect-mismatch-violations
  "HARD, checked first -- see `op->effect`'s docstring for why this must
  come first."
  [{:keys [op]} proposal]
  (when-let [expected (op->effect op)]
    (when (not= expected (:effect proposal))
      [{:rule :effect-mismatch
        :detail (str "op " op " の提案は :effect " expected
                     " のはずが実際には " (:effect proposal) " になっている")}])))

(defn- sensor-basis-violations
  "HARD (NEW, no 6910 counterpart): a :defect/inspect proposal that ASSERTS
  a condition (:clear -- 'safe' / :safety-defect -- 'has this defect') must
  cite sensor readings whose metrics cover the class's required sensor
  surface (formation.telemetry). The defect self-attestation hole: an
  untrusted advisor rates a defect :low (or a tool :clear) with no measured
  basis -- the tool-fleet re-derivation of 6910's 'self-attested :effect'
  hole. :needs-data is the honest 'no basis yet' verdict and is exempt."
  [{:keys [op subject]} proposal st]
  (when (= op :defect/inspect)
    (let [value (:value proposal)
          verdict (:verdict value)]
      (when (contains? #{:clear :safety-defect} verdict)
        (let [t (store/tool st subject)
              cls (:tool-class t)
              cited (:sensor-basis value)
              reads (store/sensor-readings st subject)]
          (when-not (telemetry/grounds-verdict? cls subject cited reads facts/sensor-metrics)
            [{:rule :no-sensor-basis
              :detail (str "検査 verdict " verdict " は計測センサー読み値の根拠を必要とするが、"
                           "引用された sensor-basis がクラス " cls " の必要センサーメトリクスを覆盖していない")}]))))))

(defn- spec-basis-violations
  "A :tool/assess / :fleet/enroll / :fleet/return-to-service / :fleet/retire
  proposal with no spec-basis citation is a HARD violation -- never invent a
  class's safety standard. Enrollment/return-to-service/retire are not exempt
  just because a fleet-id already exists: 'there is a tool to enroll' is not
  the same as 'we know what this class's inspection regime requires'."
  [{:keys [op]} proposal]
  (when (contains? #{:tool/assess :fleet/enroll :fleet/return-to-service :fleet/retire} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式safety spec-basisの引用が無い提案は工具クラス要件として扱えない"}]))))

(def ^:private rental-actuation-ops
  "Ops that put a tool in front of a renter (and therefore require a clean,
  clear inspection first). enroll = first entry into the pool; rts =
  re-entry after maintenance; checkout = handover to a renter."
  #{:fleet/enroll :fleet/return-to-service :rental/checkout})

(defn- latest-inspection [st tool-id]
  (store/inspection-of st tool-id))

(defn- open-safety-defect-violations
  "HARD (6910 #3 / Addendum 7, sanctions -> safety defect): an enroll/rts/
  checkout on a tool whose LATEST inspection is a :safety-defect with
  :high/:safety-critical severity never re-enters the rental pool
  automatically. This is the direct translation of business-model.md's
  'any :high/:safety-critical defect finding always escalates to human
  sign-off before a tool re-enters the rental pool' -- here as an
  un-overridable block, because an automated re-rental of a known-serious-
  defect tool is exactly what must never happen."
  [{:keys [op subject]} _proposal st]
  (when (contains? rental-actuation-ops op)
    (let [insp (latest-inspection st subject)]
      (when (and (= :safety-defect (:verdict insp))
                 (contains? safety-critical-severity (:severity insp)))
        [{:rule :open-safety-defect
          :detail "最新検査が :high/:safety-critical の安全欠陥を記録している工具はレンタル_POOLに再投入できない"}]))))

(defn- inspection-completeness-violations
  "HARD (6910 #4 / Addendum 7, KYC-complete -> inspection-complete): for
  enroll/rts/checkout, the tool's LATEST inspection verdict must be :clear.
  A never-inspected tool (nil), a :needs-data verdict, or a non-critical
  :safety-defect are all 'not clear' -- the tool has not earned re-rental.
  (The :high/:safety-critical case is reported separately by
  open-safety-defect-violations; this check catches the rest so 'nil != :clear'
  is never silently exploited, exactly as 6910's KYC-completeness check
  catches a never-screened officer.)"
  [{:keys [op subject]} _proposal st]
  (when (contains? rental-actuation-ops op)
    (let [insp (latest-inspection st subject)
          verdict (:verdict insp)]
      (when (not= :clear verdict)
        (when-not (and (= :safety-defect verdict)
                       (contains? safety-critical-severity (:severity insp)))
          [{:rule :inspection-incomplete
            :detail (str "工具の最新検査 verdict が :clear でない"
                         (when (nil? verdict) "(一度も検査されていない)"))}])))))

(defn- checklist-violations
  "HARD (6910 #5, document-complete): for :fleet/enroll, the class's
  required inspections must actually be satisfied (the assessment checklist)
  -- do not trust the advisor's self-reported confidence alone."
  [{:keys [op subject]} _proposal st]
  (when (= op :fleet/enroll)
    (let [t (store/tool st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-inspections-satisfied?
                      (:tool-class t) (:checklist assessment)))
        [{:rule :incomplete-checklist
          :detail "工具クラスの必要検査が充足していない状態での登録提案"}]))))

(defn- post-enrollment-intake-violations
  "(6910 #6 / Addendum 9): :application/intake exists for PRE-ENROLLMENT
  tool data entry. It is also the ONLY op in ANY phase's :auto set
  (formation.phase), so it auto-commits with NO human approval. Once a tool
  is :in-service/:under-maintenance/:retired, allowing intake to keep
  touching it would let condition-grade, site, status or fleet-id be silently
  rewritten with ZERO governor scrutiny -- a structural bypass of the entire
  actuation gate. HARD, un-overridable: the fix is always 'use
  :fleet/return-to-service (or :fleet/retire)', never 'approve the intake'."
  [{:keys [op subject]} st]
  (when (= op :application/intake)
    (let [t (store/tool st subject)]
      (when (contains? #{:in-service :under-maintenance :retired} (:status t))
        [{:rule :post-enrollment-intake-blocked
          :detail "登録/退役済みの工具への intake 経由の変更は禁止。:fleet/return-to-service または :fleet/retire を使うこと"}]))))

(def amendable-fields
  "The ONLY tool fields a :fleet/return-to-service may touch -- an ALLOWLIST,
  not a denylist, so a newly-added tool field defaults to forbidden until
  someone deliberately decides it belongs here. Everything else is
  structurally off-limits to a mere maintenance event, most importantly
  `:status` (lifecycle-managed exclusively by :fleet/enroll / :fleet/retire),
  `:tool-class` (identity, like jurisdiction), `:fleet-id`/`:fleet-number`
  (registry-assigned), `:rental-state` (lifecycle) and `:safety-hazard?`
  (the inspection flag). `:condition-grade` IS amendable -- a maintenance
  event is exactly when a tool's grade improves -- but it is gated by the
  inspection-complete check (a :clear inspection on file) and always
  escalates, so claiming an improved grade is never automatic."
  #{:nickname :site :condition-grade :maintenance-notes})

(defn- intake-fabrication-violations
  "HARD (6910 #7 / Addendum 15, intake forgery -> return-to-service forgery):
  :application/intake is the ONE op any phase ever auto-commits -- zero
  human approval. Its patch content needs the same structural limits RTS
  gets from `amendable-fields`, but pre-enrollment intake has its OWN
  failure modes, all of which amount to 'fabricate a return-to-service /
  enrolled state without governor clearance':

    - `:fleet-id` / `:fleet-number` are assigned ONLY by a real
      :fleet/enroll. An intake patch setting either fabricates a complete
      fake enrollment -- fake fleet-id, fake fleet-number, ZERO fleet-history
      entry, zero spec-basis, zero inspection, zero human ever involved.
    - `:status :in-service` / `:retired` are terminal actuation states
      reached ONLY via :fleet/enroll-submitted / :fleet/retire-submitted.
      An intake patch claiming either fabricates the actuation event itself,
      and also flips on post-enrollment-intake-violations' own protection --
      making the fabrication permanent and indistinguishable from a real
      enrollment to every later check.
    - patch's own `:id`, if present, must equal the request's `:subject`
      (same subject-confusion exploit 6910 Addendum 15 found)."
  [{:keys [op subject]} proposal]
  (when (= op :application/intake)
    (let [patch (:value proposal)]
      (cond-> []
        (some #(contains? patch %) [:fleet-id :fleet-number])
        (conj {:rule :intake-forbidden-field
               :detail "intake で fleet-id/fleet-number を設定することはできない（実際の enroll でのみ発行される）"})
        (contains? #{:in-service :retired} (:status patch))
        (conj {:rule :intake-forbidden-status
               :detail "intake で :status を :in-service/:retired にすることはできない（実際の enroll/retire でのみ到達する終端状態）"})
        (and (contains? patch :id) (not= (:id patch) subject))
        (conj {:rule :intake-subject-mismatch
               :detail "patch の :id がリクエストの subject と一致しない"})))))

(defn- return-to-service-violations
  "HARD (6910 #8 / Addenda 4+14, amendment smuggling -> maintenance-record
  smuggling): for :fleet/return-to-service, the target must already carry a
  fleet-id (you cannot return-to-service a tool that was never enrolled),
  the changed-fields must actually change something, and it must not touch
  any field outside `amendable-fields` -- without the last check, an RTS
  proposal could smuggle `{:status :retired}` (or :fleet-id, :tool-class,
  :rental-state) into changed-fields alongside an innocuous-looking
  maintenance note, committing as a plain 'return-to-service-draft' record
  with NONE of :fleet/retire's own scrutiny (double-retire guard) ever run."
  [{:keys [op subject]} proposal st]
  (when (= op :fleet/return-to-service)
    (let [t (store/tool st subject)
          changed (get-in proposal [:value :changed-fields])
          forbidden (remove amendable-fields (keys changed))]
      (cond-> []
        (nil? (:fleet-id t))
        (conj {:rule :no-fleet-id
               :detail "未登録(fleet-id無)の工具には return-to-service できない"})
        (empty? changed)
        (conj {:rule :empty-return-to-service
               :detail "変更内容が空の return-to-service 提案"})
        (seq forbidden)
        (conj {:rule :return-to-service-forbidden-field
               :detail (str "return-to-service で変更できないフィールドが含まれている（"
                            "status/fleet-id/fleet-number/tool-class/rental-state/safety-hazard?/id は対象外）: "
                            (vec forbidden))})))))

(defn- retire-violations
  "HARD (6910 #9 / Addendum 5, double-dissolution -> double-retire): for
  :fleet/retire, the target must already carry a fleet-id, and it must not
  already be retired (no double-retire)."
  [{:keys [op subject]} st]
  (when (= op :fleet/retire)
    (let [t (store/tool st subject)]
      (cond-> []
        (nil? (:fleet-id t))
        (conj {:rule :no-fleet-id
               :detail "未登録(fleet-id無)の工具には retire できない"})
        (= :retired (:status t))
        (conj {:rule :already-retired
               :detail "既に退役済みの工具への二重 retire 提案"})))))

(defn- duplicate-entry-violations
  "HARD (6910 #13, LEI global-uniqueness -> double-enrollment / double-rental):
  enrolling a tool that is already :in-service, or checking out a tool that
  is already :rented, are both un-overridable holds. (The complementary
  fleet-id *derivation* collision fix -- two first-in-class tools never
  receive the same fleet-id -- lives in formation.registry.)"
  [{:keys [op subject]} st]
  (let [t (store/tool st subject)]
    (cond-> []
      (and (= op :fleet/enroll) (some? (:fleet-id t)))
      (conj {:rule :already-enrolled
             :detail "既にレンタル_POOLに登録済み(fleet-id保有)の工具への二重 enroll"})
      (and (= op :rental/checkout) (= :rented (:rental-state t)))
      (conj {:rule :already-rented
             :detail "既に貸出中(:rented)の工具への二重貸出"}))))

(defn- rental-target-violations
  "HARD: :rental/checkout requires the tool to be enrolled (:fleet-id
  present) and :in-service. (The double-rental case is handled by
  duplicate-entry-violations; this catches checking out a tool that was
  never enrolled or is off-pool.)"
  [{:keys [op subject]} st]
  (when (= op :rental/checkout)
    (let [t (store/tool st subject)]
      (cond-> []
        (nil? (:fleet-id t))
        (conj {:rule :not-enrolled
               :detail "未登録の工具は貸し出せない"})
        (not (contains? #{:in-service} (:status t)))
        (conj {:rule :not-in-service
               :detail (str "貸し出しには :in-service の工具が必要（現在: " (:status t) ")")})))))

(defn check
  "Censors a ToolFleet-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}.

   - :hard?       -- at least one HARD violation. Forces HOLD; a human
                    cannot override.
   - :escalate?   -- soft: low confidence OR actuation. A human decides.
   - :ok?         -- clean AND not escalating: safe to auto-commit."
  [request _context proposal st]
  (let [hard (into []
                   (concat (effect-mismatch-violations request proposal)
                           (sensor-basis-violations request proposal st)
                           (spec-basis-violations request proposal)
                           (open-safety-defect-violations request proposal st)
                           (inspection-completeness-violations request proposal st)
                           (checklist-violations request proposal st)
                           (post-enrollment-intake-violations request st)
                           (intake-fabrication-violations request proposal)
                           (return-to-service-violations request proposal st)
                           (retire-violations request st)
                           (rental-target-violations request st)
                           (duplicate-entry-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
