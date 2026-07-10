# ADR-0001: cloud-itonami-unspsc-27 — ToolFleet-LLM を封じ込めた知能ノードとする工具 fleet レンタル/整備アクター設計

- Status: Accepted (2026-07-10)
- 関連: `cloud-itonami-isic-6910` ADR-0001（本 actor の port 元。
  Registrar-LLM を封じ込める会社設立代行 actor -- 本 ADR の構造・governor
  契約・addenda discipline をそのまま翻訳して引き継ぐ）、`robotaxi-actor`
  ADR-0001、`cloud-itonami-6310` ADR-0001、`ai-gftd-itonami`（CertGovernor）、
  langgraph-clj ADR-0001（Pregel superstep + interrupt + Datomic checkpoint）。
- 文脈: UNSPSC segment 27（Tools and General Machinery）の「工具 fleet の共有
  レンタルと、統治された状態点検（governed condition inspection）」を、単一の
  中央集権的 vendor が全サイトの liability/custody/safety を抱え込む設計ではなく、
  governed・forkable な OSS actor を多数の operator が各サイトで自己運用できる
  形で提供する。

## 課題

工具・機械のレンタルと整備を安全に運用するには、次の異なる性質の判断が必要になる。

1. **工具クラス要件の正しさ** -- 必要点検項目・安全基準・根拠が、公式の安全
   規格（OSHA / ANSI / ASME 等）に基づいているか。
2. **欠陥/安全判定** -- 点検で見つかった欠陥が深刻か、貸し出し可能か。特に
   `:high`/`:safety-critical` の欠陥は、工具がレンタル POOL に戻る前に人間の
   承認を必須とする（business-model.md）。
3. **実アクチュエーション** -- 工具をレンタル POOL に新規登録し、整備後に
   return-to-service し、退役させるという、後戻りのできない実世界の行為。

LLM はこれらのいずれについても、安全根拠の真正性・判定責任・実行責任を持たない。
加えて本ドメインは robotics:true であり、**状態/安全の主張はすべて計測された
センサーデータに裏付けられなければならない**（business-model.md）。したがって
設計課題は「LLM で整備・レンタル実務を回す」ことではなく、**LLM を信頼境界の
内側に封じ込め、クラス要件の真正性・点検/欠陥判定（センサー裏付け付き）・監査・
人間承認の層をどう被せ、かつ実アクチュエーションを構造的に人間専用に固定するか**
である。

## 決定

本 actor は `cloud-itonami-isic-6910`（Global Incorporation Actor）の構造を
**ほぼそのまま port** しつつ、ドメインを会社設立から工具 fleet レンタル/整備へ
翻訳した。namespace 対応は README/CLAUDE.md の port spec に従う:

| 6910 ns | → unspsc-27 ns | 備考 |
|---|---|---|
| `formation.registry` | `formation.registry` | LEI→fleet-id（同一の MOD 97-10 数学）。登記→登録(enroll)/整備(RTS)/退役(retire) |
| `formation.governor` | `formation.governor` | RegistrarGovernor → **ToolFleetGovernor** |
| `formation.store` | `formation.store` | 同一の `:db-api` 形状（MemStore ‖ DatomicStore） |
| `formation.facts` | `formation.facts` | 法域spec-basis→工具クラス安全spec-basis目録 |
| `formation.operation` | `formation.operation` | 同一の StateGraph 構造 |
| `formation.phase` | `formation.phase` | ほぼ verbatim |
| `formation.sim` | `formation.sim` | ほぼ verbatim のデモドライバ |
| `formation.registrarllm` | `formation.toolfleetllm` | 点検/整備 proposal node |
| `formation.corporate_intel` | **DROP** | ドメインに analog 無し（isic-8291 依存も削除）|
| (新規) | `formation.telemetry` | **6910 に無い、本ドメイン専用のセンサー grounding 層** |

### 1. ToolFleet-LLM は最下層の1ノードに封じ込め、直接登録/貸出させない

`formation.toolfleetllm` は intake 正規化・クラス点検チェックリスト・欠陥点検
（センサー裏付け付き）・登録提案・return-to-service 提案・退役提案・貸出提案の
7種類の proposal だけを返す。どの proposal も SSoT への書き込みや実際の fleet
登録を直接行わない。

### 2. OperationActor = langgraph-clj StateGraph、1 run = 1 操作

`formation.operation/build` は 6910 と同型の StateGraph
（intake → advise → govern → decide → commit | hold | request-approval）。
1回の graph run が1つの工具操作に対応し、無限の内部ループを持たない。

### 3. ToolFleetGovernor は ToolFleet-LLM と別系統

`formation.governor` は 6910 の 11 チェックを翻訳したもの + センサー grounding
1チェック（後述）を持つ。HARD（人間上書き不可）と SOFT（人間が承認判断）の
分離も同一。

### 4. 実アクチュエーションは構造的に常に人間専用（2層で独立に強制）

`formation.governor` の actuation gate（`:stake :actuation` は常に escalate）と
`formation.phase` のフェーズ表（`:fleet/enroll`/`:fleet/return-to-service`/
`:fleet/retire`/`:rental/checkout` はどのフェーズの `:auto` にも含まれない）の
**両方**が、実際の fleet 登録/整備/退役/貸出を自動化しない（`phase_test.clj`
の `actuation-ops-never-auto-at-any-phase` で機械的に検出）。

### 5. fleet-id の spec 数学は 6910 から移植、principal は分離

`formation.registry` は 6910 の formation.registry（さらに matsurigoto 由来）
が実装した ISO 7064 MOD 97-10 チェックdigitのロジックをそのまま移植した。
6910 は「実在の政府 registry を相手にする licensed operator を支援する」のに対し、
本 actor は「レンタル fleet を相手にする operator を支援する」-- 同じ spec 数学、
異なる principal。

### 6. テレメトリ/センサー grounding 層は本ドメイン専用の追加（6910 に対応物なし）

`formation.telemetry` は business-model.md の「public safety/condition claims
must reference measured sensor data」を実装する。点検 proposal の verdict が
`:clear`（安全）または `:safety-defect`（欠陥あり）を主張する場合、引用された
`:sensor-basis`（reading-id 群）が、その工具クラスの必要センサーメトリクスを
過不足なく覆盖することを governor が検証する（`grounds-verdict?`）。`:needs-data`
（「確定できない」）は正直な「根拠不足」判定として grounding 要求を免除する
（「分からない」に根拠を要求すると正直さを罰してしまうため）。この層は
MemStore≡DatomicStore parity の対象でもある（sensor readings は Store protocol
を通じて両バックエンドが保持する）。

## 帰結

- (+) 6910 で実証済みの governed・auditable な実行基盤を、工具 fleet ドメインに
  再利用できる。実アクチュエーション不変条件（governor + phase の2層）は
  `phase_test.clj` でリグレッション検出可能。
- (+) センサー grounding により、安全/状態の主張が計測データに traceable になる。
  これが無いと、LLM が「この工具は問題ない」と偽って（根拠なく）主張するのを
  止められない。
- (-) 本 R0 は 6 工具クラス（PWR-DRILL/ANG-GRINDER/CIRC-SAW/AIR-COMP/GEN-SET/
  HEDGE-TRMR）のみ spec-basis を持つ。UNSPSC segment 27 の全クラスは未カバー
  であり、正直に `formation.facts/coverage` で報告する。
- (-) `MemStore` ‖ `DatomicStore` 両対応だが、実際の Datomic Local /
  kotoba-server pod への接続確認は未実施（`store_contract_test.clj` が in-process
  parity を証明済み）。
- (-) 実際のレンタル管理システム統合・実際のセンサー収集パイプライン統合は
  この OSS actor の対象外（各 operator の責任）。`:rental/return`（貸出工具の返却）
  も未実装の follow-up（`checkout` の逆操作）。

## Addendum 群 -- 6910 のセキュリティ/正確性 hole-fix の翻訳

6910 は R0 から Addendum 15 までの反復で 4件の実ガバナンス迂回バグ（Addendum
9/12/14/15）を発見・修正した。本 actor は 6910 の**完成した** governor を起点に
しているため、これらの穴は最初から塞がれた形で翻訳されている。以下に各 addendum
の **discipline** を本ドメインへどう翻訳したかを正直に記録する（会社設立固有の
詳細をコピーしたわけではなく、各 hole-fix の「保護すべき不変条件」を再定式化した）。

### Addendum 2 (DatomicStore parity) → そのまま適用
`formation.store` に MemStore ‖ DatomicStore を実装。`store_contract_test.clj`
が CRUD parity（センサー読み込み含む）を、`governor_contract_test.clj` の
`*-on-datomic-store-too` テストが全 actor フローの parity を保証する。

### Addendum 4/5 (amend/dissolve 配線 + 二重解散防止) → return-to-service / retire
- `formation.registry/register-return-to-service` / `register-retirement` を追加
  （追記型 record、enrollment record は書き換えない）。
- governor の `return-to-service-violations`（fleet-id 必須・変更内容必須・
  `amendable-fields` 制限）と `retire-violations`（fleet-id 必須・二重 retire
  prevent）に翻訳。
- 二重 retire は `:already-retired` で un-overridable hold（`retire-and-double-retire`
  テスト）。

### Addendum 6 (amend/dissolve の spec-basis 引用要求) → そのまま適用
`spec-basis-violations` は `:tool/assess`/`:fleet/enroll`/
`:fleet/return-to-service`/`:fleet/retire` を対象とする。クラスの安全基準の引用
無しにはどの fleet 操作も通さない。

### Addendum 7 (KYC/filing gate) → inspection-complete + open-safety-defect
- 「officer の KYC verdict が一度も無いと filing が素通り」を、「工具が一度も
  点検されていない（inspection-of が nil）と登録/RTS/貸出 が素通り」へ翻訳 --
  `inspection-completeness-violations`（`:inspection-incomplete`）。nil != :clear。
- `:high`/`:safety-critical` の安全欠陥を記録した工具は `:open-safety-defect`
  で un-overridable hold（business-model.md の「`:high`/`:safety-critical` 欠陥は
  人間承認前にレンタル POOL に戻さない」の構造的強制）。

### Addendum 8 (amendment 経由の officer 追加が制裁検査を素通り) → return-to-service は最新点検に縛られる
6910 は amendment が新規導入する officer のみを制裁/KYC 検査の対象にした。
本ドメインには「officer に対応する個別エンティティ」が無く、return-to-service/
enroll/checkout はすべて「対象工具の最新点検 verdict」を検査するため、この
hole の形状は最初から存在しない。ただし等価の保護（RTS/enroll/checkout が
最新点検 :clear を要求）は `inspection-completeness-violations` が担う。

### Addendum 9 (post-filing intake bypass) → post-enrollment intake bypass
「登記済み申請への `:application/intake` が無検閲」を「登録済み（:in-service/
:under-maintenance/:retired）工具への intake が無検閲」へ翻訳 --
`post-enrollment-intake-violations`（`:post-enrollment-intake-blocked`）。
`post-enrollment-intake-cannot-smuggle-changes` テストが回帰を検出。

### Addendum 11 (台帳が「誰が承認したか」を答えられない) → そのまま適用
`commit-fact` に `:approved-by` を持たせる。auto-commit（intake）は nil のまま
（承認者を捏造しない）、人間承認経路は実際の承認者 id を記録する。
`committed-ledger-fact-records-the-actual-approver` テストが検証。

### Addendum 12 (advisor の自己申告 :effect) → effect-mismatch + defect self-attestation
**2つに分割して翻訳**（本 ADR で最も重要な翻訳）:
1. **effect-mismatch**（そのまま）: `op->effect` テーブルが、リクエストの :op ごとに
   唯一正当な :effect を固定し、`effect-mismatch-violations` が最初にチェックする。
   無害な `:tool/assess` リクエストに `:effect :fleet/enroll-submitted` で答える
   LLM を即座に hold（`effect-mismatch-cannot-actually-enroll-through-the-full-actor-graph`
   テスト）。
2. **defect self-attestation（本ドメインへの再定式化）→ センサー grounding**:
   「自己申告 :effect」の穴の**形状**（信頼できない advisor が、自分が持たない
   根拠で結論を宣言できる）を、「自己申告の点検 verdict（`:clear`/`:low` を根拠無く
   宣言できる）」へ読み替えた。`sensor-basis-violations` が、verdict が条件を
   主張する場合 `formation.telemetry/grounds-verdict?` でセンサー裏付けを強制する。
   `llm-self-attesting-a-clear-verdict-with-no-sensor-basis-is-rejected` テストが、
   高 confidence でも根拠が無ければ HARD hold することを検証。

### Addendum 13 (LEI global-uniqueness) → fleet-id 一意性 + 二重登録/二重貸出防止
- `formation.registry/default-fleet-id-suffix` は tool-id と per-class sequence の
  両方から fleet-id を導出する（sequence 単独ではない）-- 2つの無関係な工具が
  たまたま同じ sequence（例: 各クラスの初回 = 0）で同じ fleet-id を受け取るのを
  防ぐ。`default-fleet-id-does-not-collide-across-tools-at-the-same-sequence` テスト。
- さらに `duplicate-entry-violations` が、`:in-service` の工具への二重 enroll
  （`:already-enrolled`）と、`:rented` の工具への二重貸出（`:already-rented`）を
  un-overridable hold（`rental-checkout-works-then-double-rental-is-held` テスト）。

### Addendum 14 (amendment の changed-fields が無制限) → maintenance-record smuggling
`amendable-fields`（`:nickname`/`:site`/`:condition-grade`/`:maintenance-notes` のみ）
の allowlist が、return-to-service が `:status`/`:fleet-id`/`:fleet-number`/
`:tool-class`/`:rental-state`/`:safety-hazard?`/`:id` を密輸するのを防ぐ。
`return-to-service-cannot-smuggle-a-status-change` /
`-cannot-smuggle-fleet-id-or-class` テスト。allowlist なのは新規 field が
デフォルトで禁止になるため（denylist の追記漏れを防ぐ）。

### Addendum 15 (intake が無制限で偽の登記状態を捏造) → return-to-service forgery
`intake-fabrication-violations` が、`:application/intake`（唯一の auto-commit op）
の patch が `:fleet-id`/`:fleet-number` を設定すること（`:intake-forbidden-field`）、
`:status` を `:in-service`/`:retired` にすること（`:intake-forbidden-status`）、
patch の `:id` が subject と不一致すること（`:intake-subject-mismatch`）を禁止する。
これが「return-to-service forgery」（工具を点検/合格扱いで無検閲で POOL に戻す）
の構造的防止。`intake-cannot-fabricate-an-enrolled-state` /
`-cannot-target-a-different-tool-than-its-declared-subject` テスト。

### Addendum 10 (governor 契約の cross-backend 証明) → そのまま適用
`fresh` を db-ctor を受け取るよう一般化し、`post-enrollment-intake-blocked-on-datomic-store-too`
と `full-lifecycle-on-datomic-store-too` が、最も高 stakes の不変条件が
DatomicStore でも同一に効くことを検証する。

## ドメイン固有の偏差（6910 との違いを正直に記録）

1. **officer 実体の削除**: 6910 は officer（KYC で審査される個別人）を持つが、
   本ドメインは「工具を点検する」ため、点検 verdict は工具単位（`inspection-of`
   tool-id）で直接保持する。これに伴い `officers-at-stake`（amendment が導入する
   officer のみを審査）は不要になり、enroll/RTS/checkout はすべて対象工具の
   **最新**点検 verdict を審査する。保護すべき不変条件（審査抜けの防止）は
   `inspection-completeness-violations` が担う。
2. **`rental/checkout` op の追加**: 6910 に無い op。「二重貸出防止」
   （Addendum 13 の翻訳）を実演するために追加した。actuation（不可逆）ではなく
   可逆（工具は返却される）なので `:stake :actuation` ではないが、どのフェーズの
   `:auto` にも入らないため常に人間の handover 承認に escalate する。逆操作の
   `:rental/return` は未実装（follow-up）。
3. **センサー grounding**: 6910 は robotics:false で対応物が無い。本ドメインの
   核となる追加（決定6 参照）。
4. **severity の導入**: 点検 verdict に `:severity`（`:none`/`:low`/`:medium`/
   `:high`/`:safety-critical`）を持たせ、`addendum 7` の「`:high`/
   `:safety-critical` は人間承認必須」を severity で駆動する。

## テスト結果

64 tests / 290 assertions, 0 failures, 0 errors. lint clean（clj-kondo,
errors: 0, warnings: 0）。内訳: facts(4) / telemetry(7) / registry(9) /
phase(6) / store-contract(4) / governor-contract(22) / toolfleet-advisor(7)
※ deftest 数は概算。全 hole-fix 翻訳と MemStore≡DatomicStore parity と
センサー grounding を cover する。

## 既知のギャップ（正直に記録）

1. **クラスカバレッジ 6 クラス**（`formation.facts/coverage`）。追加は
   1エントリ=1本物の公式安全基準引用が原則。
2. **DatomicStore は in-process 検証止まり**（実 Datomic Local / kotoba-server
   pod への接続未検証）。
3. **`:rental/return` 未実装**: checkout の逆操作。checkout で `:rented` に
   なった工具を `:available` に戻す op。本 R0 では直接 store upsert で代替
   （sim 参照）。
4. 実レンタル管理システム・実センサー収集パイプライン統合は最初から対象外
   （operator 責務、設計通り）。
