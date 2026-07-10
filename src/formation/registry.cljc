(ns formation.registry
  "Pure-function fleet-record construction + a 20-char fleet-id with a real
  ISO 7064 MOD 97-10 check-digit computation, then an append-only
  enrollment / return-to-service / retire record.

  The LEI/MOD-97-10 arithmetic is ported near-verbatim from
  cloud-itonami-isic-6910's formation.registry (itself ported from
  matsurigoto's corp-registry, ADR-2606062300) -- it is jurisdiction/
  domain-agnostic spec math. What changes is the principal: 6910's LEI
  identifies a legal entity at a government registry; here the 20-char
  fleet-id identifies a tool enrolled in a rental fleet. Same check-digit
  guarantee, different entity.

  The default entity-id derivation (`default-fleet-id-suffix`) incorporates
  BOTH the tool-id and the per-class sequence -- the direct translation of
  6910's Addendum 13 (LEI global-uniqueness) fix: deriving the suffix from a
  per-class sequence ALONE would make two unrelated tools, each the Nth
  enrollment in their own class, receive a textually identical fleet-id
  (most commonly both first-in-class = sequence 0). That violates the
  fleet-id's whole purpose (globally-unique tool identity inside the fleet)
  and is the structural enabler of duplicate fleet entries.

  This namespace is pure data + pure functions -- no I/O, no network call to
  any rental management system. It builds the RECORD an operator would file,
  not the act of filing itself (that is `formation.operation`'s :fleet/enroll
  / :fleet/return-to-service / :fleet/retire, each always human-gated)."
  (:require [clojure.string :as str]))

;; -- 20-char fleet-id + ISO 7064 MOD 97-10 (the conformance anchor) --

(defn- to-digits
  "Convert an alphanumeric string to its ISO 7064 numeric form (0-9 stay; A=10 .. Z=35)."
  [s]
  (apply str
         (map (fn [ch]
                (cond
                  (<= (int \0) (int ch) (int \9)) (str ch)
                  (<= (int \A) (int ch) (int \Z)) (str (- (int ch) 55))
                  :else (throw (ex-info (str "fleet-id char must be [0-9A-Z], got " (pr-str (str ch))) {}))))
              s)))

(defn- mod97
  "digits mod 97, over arbitrary-precision integers (a plain 18-20 digit
  number overflows a 53-bit JS/double or a 63-bit long)."
  [numeric-str]
  #?(:clj  (.intValue (.mod (java.math.BigInteger. ^String numeric-str) (java.math.BigInteger. "97")))
     :cljs (js/Number (js-mod (js/BigInt numeric-str) (js/BigInt 97)))))

(defn compute-fleet-id-check-digits
  "ISO 7064 MOD 97-10 check digits for an 18-char fleet-id base.
  digits = numeric(base18 + \"00\"); check = 98 - (digits mod 97); zero-padded to 2."
  [base18]
  (when (not= (count base18) 18)
    (throw (ex-info (str "fleet-id base must be 18 chars, got " (count base18)) {})))
  (let [m (mod97 (to-digits (str base18 "00")))
        c (- 98 m)]
    (if (< c 10) (str "0" c) (str c))))

(defn validate-fleet-id
  "A 20-char fleet-id is valid iff numeric(fleet-id) mod 97 == 1 (ISO 7064 MOD 97-10)."
  [fleet-id]
  (if (or (not (string? fleet-id)) (not= (count fleet-id) 20))
    false
    (try
      (= (mod97 (to-digits fleet-id)) 1)
      (catch #?(:clj Exception :cljs :default) _ false))))

(defn assign-fleet-id
  "Build a valid 20-char fleet-id: 4-char fleet prefix + reserved '00' +
  12-char entity id + 2 check digits."
  ([prefix entity-id12]
   (when (not= (count prefix) 4)
     (throw (ex-info "fleet-id prefix must be 4 chars" {})))
   (when (not= (count entity-id12) 12)
     (throw (ex-info "entity id must be 12 chars" {})))
   (let [base (str/upper-case (str prefix "00" entity-id12))]
     (str base (compute-fleet-id-check-digits base)))))

(defn- default-fleet-id-suffix
  "Deterministic 12-char alphanumeric entity id when the caller supplies no
  explicit `entity-id12` -- derived from BOTH `tool-id` and the per-class
  `sequence` via base-36 arithmetic over their combined digit form, NEVER
  from `sequence` alone. `formation.store/next-sequence` is PER-CLASS, so
  two different tools -- each the Nth enrollment in their OWN class -- share
  the same `sequence` value; deriving the suffix from `sequence` alone would
  make two entirely unrelated tools receive the textually IDENTICAL fleet-id
  the moment both happen to be, say, first-in-class (sequence 0). That is a
  duplicate fleet entry by construction and the exact bug 6910's Addendum 13
  fixed for LEI, translated here to tool identity."
  [tool-id sequence]
  (let [alnum (str/upper-case (str/replace (str tool-id sequence) #"[^0-9A-Za-z]" ""))
        n #?(:clj  (java.math.BigInteger. ^String (to-digits alnum))
             :cljs (js/BigInt (to-digits alnum)))
        b36 (str/upper-case #?(:clj  (.toString ^java.math.BigInteger n 36)
                               :cljs (.toString n 36)))
        ;; the last 12 base-36 digits of n == n mod 36^12 (place-value fact) --
        ;; a deterministic reduction into the 12-char alphanumeric space, not
        ;; a truncation that discards the tool-id's contribution.
        last12 (subs b36 (max 0 (- (count b36) 12)))]
    (str (apply str (repeat (max 0 (- 12 (count last12))) "0")) last12)))

;; -- fleet records --

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- the act of stamping
  a tool 'enrolled / returned-to-service / retired' in the REAL rental
  management system is the operator's, not this actor's. See README Actuation."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_fleet" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-enrollment
  "Validate + construct a tool enrollment DRAFT (first entry into the rental
  fleet -- assigns the fleet-id). Pure function; does not touch any real
  rental management system."
  ([tool-id class-code nickname condition-grade site sequence]
   (register-enrollment tool-id class-code nickname condition-grade site sequence "FLTV" nil))
  ([tool-id class-code nickname condition-grade site sequence prefix entity-id12]
   (when-not (and tool-id (not= tool-id ""))
     (throw (ex-info "enrollment: tool-id required" {})))
   (when-not (and class-code (not= class-code ""))
     (throw (ex-info "enrollment: class-code required" {})))
   (when-not (and nickname (not= nickname ""))
     (throw (ex-info "enrollment: nickname required" {})))
   (when-not (contains? #{:a :b :c} condition-grade)
     (throw (ex-info (str "enrollment: condition-grade must be :a/:b/:c, got " (pr-str condition-grade)) {})))
   (when-not (and site (not= site ""))
     (throw (ex-info "enrollment: site required" {})))
   (when (< sequence 0)
     (throw (ex-info "enrollment: sequence must be >= 0" {})))
   (let [fleet-number (str (str/upper-case class-code) "-" (zero-pad sequence 8))
         base-eid (or entity-id12 (default-fleet-id-suffix tool-id sequence))
         eid (-> base-eid
                 (subs 0 (min 12 (count base-eid)))
                 (#(str (apply str (repeat (max 0 (- 12 (count %))) "0")) %))
                 str/upper-case)
         fleet-id (assign-fleet-id prefix eid)
         record {"record_id" fleet-number
                 "kind" "enrollment-draft"
                 "tool_id" tool-id
                 "class_code" class-code
                 "nickname" nickname
                 "condition_grade" (str condition-grade)
                 "site" site
                 "fleet_id" fleet-id
                 "immutable" true}]
     {"record" record "fleet_id" fleet-id "fleet_number" fleet-number
      "certificate" (unsigned-certificate "EnrollmentCertificate" tool-id fleet-number)})))

(defn register-return-to-service
  "Append-only return-to-service DRAFT (a maintenance event that brings a
  tool back into the rental pool). Never overwrites the enrollment record --
  maintenance is one more appended record, the tool-fleet analog of an
  amendment (6910 Addendum 4/14: append-only, and the governor's
  return-to-service target check guards what fields it may touch)."
  [fleet-number maintenance-summary serviced-by effective-date]
  (when-not (and fleet-number (not= fleet-number ""))
    (throw (ex-info "return-to-service: fleet_number required" {})))
  (when-not (and maintenance-summary (not= maintenance-summary ""))
    (throw (ex-info "return-to-service: maintenance-summary required" {})))
  {"record" {"record_id" (str fleet-number "#rts@" effective-date)
             "kind" "return-to-service-draft"
             "fleet_number" fleet-number
             "maintenance_summary" maintenance-summary
             "serviced_by" serviced-by
             "effective_date" effective-date
             "immutable" true}})

(defn register-retirement
  "Append-only retirement DRAFT (terminal event -- tool leaves the rental
  fleet permanently). The fleet-number and its history are never deleted
  (append-only) -- retirement is one more appended record, the tool-fleet
  analog of a dissolution (6910 Addendum 5: double-retirement prevention
  lives in the governor)."
  [fleet-number reason effective-date]
  (when-not (and fleet-number (not= fleet-number ""))
    (throw (ex-info "retire: fleet_number required" {})))
  (when-not (and reason (not= reason ""))
    (throw (ex-info "retire: reason required" {})))
  {"record" {"record_id" (str fleet-number "#retired@" effective-date)
             "kind" "retirement-draft"
             "fleet_number" fleet-number
             "reason" reason
             "effective_date" effective-date
             "immutable" true}})

(defn append
  "Append a fleet record, returning a NEW list (never mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
