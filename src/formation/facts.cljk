(ns formation.facts
  "Per-tool-class condition & safety requirement catalog -- the spec-basis
  table the ToolFleetGovernor checks every :tool/assess proposal against
  ('did the advisor cite an OFFICIAL safety standard for this class's
  required inspections, or did it invent one?'). The direct analog of
  cloud-itonami-isic-6910's per-jurisdiction spec-basis table, translated
  to the tool-fleet domain: a jurisdiction's company-formation law becomes
  a tool class's published safety/inspection standard.

  Coverage is reported HONESTLY (see `coverage`), the same discipline the
  reference actor uses: a tool class not in this table has NO spec-basis,
  full stop -- the advisor must not fabricate one, and the governor holds
  if it tries. Seed values are drawn from each class's recognized safety
  standard / OSHA-style authority (see `:provenance`); they are a STARTING
  catalog, not a survey of every tool category. Extending coverage is
  additive: add one map to `catalog`, cite a real source, done -- never
  invent a class's requirements to make coverage look bigger.

  Two fields are specific to the tool-fleet domain (no counterpart in the
  company-formation reference) and exist to support the telemetry-grounding
  discipline (business-model.md: 'public safety/condition claims must
  reference measured sensor data'):
    :sensor-metrics  -- the measurable quantities a sensor reading must
                        cover before a 'this tool is safe / defect-free'
                        claim about this class can be grounded.
    :criticality      -- :standard | :high. A :high class (cutting /
                        pressure / lifting equipment) escalates every
                        rental handover to a human even when clean, the
                        tool-fleet analog of always-gated actuation.")

(def catalog
  "tool-class code -> requirement map. `:required-inspections` mirrors the
  generic condition checklist every rental fleet asks for in some form;
  `:legal-basis` / `:owner-authority` / `:provenance` are the citation the
  governor requires before any :tool/assess proposal can commit.
  `:sensor-metrics` are the measurable signals that ground a safety/condition
  claim for this class (formation.telemetry). `:criticality` drives the
  always-escalate rental gate for high-risk classes."
  {"PWR-DRILL" {:name "Power Drill (corded / cordless)"
                :owner-authority "OSHA / ANSI"
                :legal-basis "ANSI/UL 60745-1 (hand-held motor-operated electric tools)"
                :national-spec "OSHA 29 CFR 1910.243 / manufacturer service manual"
                :provenance "https://www.osha.gov/laws-regs/regulations/standardnumber/1910/1910.243"
                :criticality :standard
                :sensor-metrics [:battery-voltage :chassis-vibration :guard-presence]
                :required-inspections ["Chuck / bit retention integrity"
                                       "Power cord or battery pack integrity"
                                       "Housing / chassis cracks"
                                       "Guard present and functional"]}
   "ANG-GRINDER" {:name "Angle Grinder (abrasive wheel)"
                 :owner-authority "OSHA / ANSI B7.1"
                 :legal-basis "ANSI B7.1 (abrasive wheels) + OSHA 29 CFR 1910.215"
                 :national-spec "OSHA 1910.215 / manufacturer service manual"
                 :provenance "https://www.osha.gov/laws-regs/regulations/standardnumber/1910/1910.215"
                 :criticality :high
                 :sensor-metrics [:wheel-rpm :guard-presence :vibration :spindle-runout]
                 :notes "Abrasive-wheel equipment: guard and wheel-speed match are the recurrent serious-injury vectors; high-criticality."
                 :required-inspections ["Guard present, adjusted and undamaged"
                                        "Wheel ring-test (no cracks)"
                                        "Wheel speed rating >= tool spindle rpm"
                                        "Trigger / dead-man switch functional"]}
   "CIRC-SAW" {:name "Circular Saw"
               :owner-authority "OSHA / ANSI"
               :legal-basis "ANSI/UL 745-2-3 + OSHA 29 CFR 1910.243"
               :national-spec "OSHA 1910.243 / manufacturer service manual"
               :provenance "https://www.osha.gov/laws-regs/regulations/standardnumber/1910/1910.243"
               :criticality :high
               :sensor-metrics [:blade-rpm :guard-presence :blade-brake-time :chassis-vibration]
               :required-inspections ["Lower blade guard returns freely"
                                      "Blade brake stops arbor in <= 2s"
                                      "Cut depth / bevel lock secure"
                                      "Power cord and trigger functional"]}
   "AIR-COMP" {:name "Air Compressor (pressure vessel)"
               :owner-authority "ASME / OSHA"
               :legal-basis "ASME Boiler and Pressure Vessel Code Section VIII (PVG-1) + OSHA 1910.169"
               :national-spec "ASME PVG-1 / OSHA 29 CFR 1910.169"
               :provenance "https://www.osha.gov/laws-regs/regulations/standardnumber/1910/1910.169"
               :criticality :high
               :sensor-metrics [:tank-pressure :relief-valve-test :temperature :drain-cycle]
               :notes "Pressure-vessel equipment: relief-valve and hydrostatic integrity are the catastrophic-failure vectors; high-criticality."
               :required-inspections ["Pressure relief valve lifts at rated pressure"
                                      "Tank hydrostatic / visual inspection current"
                                      "Drain valve operates"
                                      "Hose and fittings rated >= working pressure"]}
   "GEN-SET" {:name "Portable Generator (combustion-electric)"
              :owner-authority "OSHA / NFPA"
              :legal-basis "OSHA 29 CFR 1926.404(b) + NFPA 70 (NEC) / CO safety"
              :national-spec "OSHA 1926.404 / manufacturer service manual"
              :provenance "https://www.osha.gov/laws-regs/regulations/standardnumber/1926/1926.404"
              :criticality :high
              :sensor-metrics [:output-voltage :co-ppm :rpm :ground-fault-test]
              :required-inspections ["GFCI / ground-fault protection functional"
                                     "CO shutoff sensor present and tested"
                                     "Output voltage in rated band"
                                     "Fuel system leak-free"]}
   "HEDGE-TRMR" {:name "Hedge Trimmer"
                 :owner-authority "OSHA / ANSI"
                 :legal-basis "ANSI/UL 60745-2-15 + OSHA 29 CFR 1910.242"
                 :national-spec "OSHA 1910.242 / manufacturer service manual"
                 :provenance "https://www.osha.gov/laws-regs/regulations/standardnumber/1910/1910.242"
                 :criticality :standard
                 :sensor-metrics [:blade-stroke :guard-presence :chassis-vibration]
                 :required-inspections ["Blade tooth integrity"
                                        "Two-hand interlock / guard functional"
                                        "Debris cleared, housing intact"]}})

(defn spec-basis
  "The tool-class's requirement map, or nil -- nil means NO spec-basis, and
  the governor must hold any proposal that tries to assess/enroll/service
  a tool of that class."
  [class-code]
  (get catalog class-code))

(defn coverage
  "Honest coverage report: how many of the requested tool classes actually
  have a spec-basis entry. Never report a missing class as covered."
  ([] (coverage (keys catalog)))
  ([class-codes]
   (let [have (filter catalog class-codes)
         missing (remove catalog class-codes)]
     {:requested (count class-codes)
      :covered (count have)
      :covered-classes (vec (sort have))
      :missing-classes (vec (sort missing))
      :note (str "cloud-itonami-unspsc-27 R0: " (count catalog)
                 " tool classes seeded with an official safety spec-basis. "
                 "This is a starting catalog, not a survey of every UNSPSC "
                 "segment-27 class -- extend `formation.facts/catalog`, never "
                 "fabricate a class's inspection requirements.")})))

(defn required-inspections-satisfied?
  "Does `performed` (a set/coll of inspection items or keywords) satisfy
  every required inspection listed for `class-code`? Missing spec-basis ->
  never satisfied (you cannot confirm an inspection regime that does not
  exist)."
  [class-code performed]
  (when-let [{:keys [required-inspections]} (spec-basis class-code)]
    (let [need (count required-inspections)
          have (count (filter (set performed) required-inspections))]
      (= need have))))

(defn inspection-checklist [class-code]
  (:required-inspections (spec-basis class-code) []))

(defn sensor-metrics
  "The measurable metrics a sensor reading must cover before a safety/condition
  claim for this class can be grounded (formation.telemetry). nil if the class
  has no spec-basis."
  [class-code]
  (:sensor-metrics (spec-basis class-code)))
