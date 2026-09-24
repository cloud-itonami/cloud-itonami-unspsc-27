# physai-unspsc-27 — 工具・一般機械（UNSPSC 27）／工具レンタル保守の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-unspsc-27`、UNSPSC segment 27 工具・一般機械。工具フリートのレンタル・保守事業者）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: 診断ロボット（振動・摩耗・校正ずれの計測）がレンタル工具・機械を点検し、
Tool Fleet Governor が保守／廃棄の判断を統制する（借り手を傷つけうる欠陥は人の承認なしにレンタルへ戻らない）。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:returned-tool-to-bench` | manipulator | 返却された電動工具（ドリル〜はつり機）を返却カートから点検台へ持ち上げる | 肩関節ピークトルク | 180 N·m（estimate） |
| `:jack-stand-pin-proof-pull` | material | レンタル用ジャッキスタンドの S355 鋼ロックピン（摩耗で φ20 → φ16 mm）の保証荷重引張 | 0.2 % 耐力荷重 | ≥ 80 kN（estimate） |
| `:motor-run-in-heating` | thermal | 電動工具の 30 分慣らし運転: 銅損が固定子巻線を温め、ファン冷却の筐体が空気へ放熱（半厚、中心面がホットスポット） | 巻線中心面のピーク温度 | 130 °C（estimate） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test-physai/formation/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。
repo 自身の `test/` も同じ runner で走る。着地時点で 66 tests / 295 assertions / 0 fail）。

setup で直した既存の不具合（kbb の cljs 経路で test が走らなかった。test は弱めていない）:
- `formation.registry/mod97` の `:cljs` 分岐が存在しない `js-mod` を呼んでいた → 桁ごとの剰余（任意長で厳密、BigInt 不要）に置換。
- `formation.registry/to-digits` が `(int ch)` で文字を判定していた → cljs では文字が 1 文字の文字列で `int` が 0 を返し、英字が数字に変換されずに素通りしていた。文字コードを取る `code` に置換。
- DatomicStore の `:inspection/tool-id` / `:assessment/tool-id` が identity でなく、再 commit のたびに実体が増えて `:find ?p .` が任意の 1 件を返していた（"latest verdict wins" が失敗）→ `:db.unique/identity` を宣言して upsert にした。

## 測って分かったこと・限界（成長の第一候補）

1. **工具の持ち上げ**: 肩トルクは 2 kg で 76.3 N·m、10 kg で 131.4 N·m、15 kg で 166.8 N·m、30 kg で 273.9 N·m。
   限界 180 N·m に達する工具重量は **16.9 kg** —— 大型のはつり機・コア抜き機は別の搬送手段が要る。
2. **ロックピン**: 0.2 % 耐力荷重は φ20 mm（3.14 cm²）で 112.2 kN、φ19 で 101.3 kN、φ18 で 91.0 kN、φ17 で 81.2 kN、φ16（2.01 cm²）で 71.9 kN。
   限界 80 kN を割るのは φ17 と φ16 の間 —— 摩耗で直径が約 15 % 減るとピンは不合格になる。点検で測るべきは摩耗後の直径。
3. **慣らし運転**: 30 分後の巻線中心面温度は発熱 100 kW/m³ で 59.4 °C、300 kW/m³ で 118.1 °C、500 kW/m³ で 176.9 °C（130 °C 到達 858 s）、700 kW/m³ で 235.6 °C（535 s）。
   限界 130 °C に達する発熱密度は **340 kW/m³**。どの run も 30 分の時点でまだ上昇中（ピーク時刻 = 終了時刻）。
4. **estimate のままの値（成長候補）**:
   - 肩トルク 180 N·m → 採用するアームのデータシート。
   - ピンの必要耐力 80 kN → ジャッキスタンドの定格荷重と規格（例: ASME PASE の安全率）を原典で確かめて導く。S355 の 355 MPa は EN 10025-2 の薄肉材の公称値として原典で確認する。
   - 巻線 130 °C → 工具ごとの絶縁種別（IEC 60085 の耐熱クラス）を銘板・仕様書から記録してから置き換える。
   - 巻線・筐体の等価熱物性（k 2.0、ρ 5000、c 600）、ファン冷却の熱伝達係数 30 W/m²K、アームの寸法・質量。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-unspsc-27 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-unspsc-27 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
