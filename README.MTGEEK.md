# MTGeek-Mage —— XMage Fork for MTGeek AI 项目

这是 [magefree/mage](https://github.com/magefree/mage) 的 fork，用作 MTGeek 项目的 MTG AI 实验平台。MTGeek 主仓位于 `~/Documents/claude/MTGeek/`，是 Next.js + SQLite 的 MTG 智能问答前端；本 fork 专门承载 AI 打牌相关的 Java 代码。

## B0' 现状（地基已完成）

✅ 自定义 trivial AI 跑通真实 Legacy/Vintage 两副牌的完整 AI-vs-AI 对局：
- 套牌 1：**谆谆教诲**（Show and Tell combo，60 张）
- 套牌 2：**蓝黑节奏**（Dimir Tempo，60 张）
- 输出：`target/match-log.txt`（含 turn-by-turn 行动）
- 跑得通 headless 模式（`-Djava.awt.headless=true`，无 GUI）

B0' 不追求 AI 智能——trivial player 只做"能下地就下、能施法选首个可施法、全攻击"。智能逻辑留到 B1'（SimpleAI）。

## 我们添加的代码

**新增 package（不动 XMage 既有类）：**
- `mage.player.ai.mtgeek.DeckVerifier` —— `.dck` 牌组合法性核对
- `mage.player.ai.mtgeek.VerifyReport` —— DeckVerifier 返回类型
- `org.mage.test.mtgeek.MTGeekTrivialPlayer` —— 最简自定义 AI（extends `ComputerPlayer`）
- `org.mage.test.mtgeek.MTGeekDeckMatchTest` —— 端到端 AI vs AI 对战测试
- `org.mage.test.mtgeek.DeckVerifierTest` —— DeckVerifier 单元测试
- `org.mage.test.mtgeek.scripts.BuildDck` —— 一次性生成 .dck 工具

**两处对 XMage 既有类的小幅追加（与既有模式一致，纯追加无破坏）：**
- `Mage.Tests/src/test/java/org/mage/test/player/TestPlayer.java` —— 新加 1 个构造器 `public TestPlayer(MTGeekTrivialPlayer)`（XMage 本就为每个内部 player 类型提供一个构造器，这是同一模式）
- `Mage/src/main/java/mage/collectors/DataCollectorServices.java` —— 新加 1 个静态方法 `public static void register(DataCollector)` 用于注册自定义 log collector

每次 sync upstream 后这两处需要重新 apply（很小，可手工 cherry-pick）。

## 构建

需要 Java 17（不是 Java 21+，XMage 用 Java 8 字节码）：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export MAVEN_OPTS="-Xmx4g"
mvn -DskipTests clean install
```

首跑约 8 分钟（依赖已缓存时；首次全新会下 15-30 分钟）。

## 跑 AI vs AI 对战测试

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export MAVEN_OPTS="-Xmx4g"
mvn test -pl Mage.Tests -Dtest=MTGeekDeckMatchTest -DfailIfNoTests=false \
  -Djava.awt.headless=true
```

结果输出到 `Mage.Tests/target/match-log.txt`（或运行目录的 `target/match-log.txt`，取决于工作目录）。

## 跑 DeckVerifier 单元测试

```bash
mvn test -pl Mage.Tests -Dtest=DeckVerifierTest -DfailIfNoTests=false
```

## 套牌

- 源（纯英文）：`decks/source/show-and-tell.txt`、`decks/source/dimir-tempo.txt`
- XMage `.dck` 格式：`Mage.Tests/src/test/resources/mtgeek/*.dck`
- 如需更新套牌：编辑 `decks/source/*.txt`，重跑 `mvn test -Dtest=BuildDck` 重新生成 `.dck`，再跑 `DeckVerifierTest` 验证。

## 已知缺失/歧义卡

**无**。47 张 unique 卡（去重后）全部由 XMage `CardRepository` 解析成功，包括较新的：
- "Flow State" → [SOS:49]
- "Raph & Mikey, Troublemakers" → [TMT:167]（TMNT crossover）
- "Mistrise Village" → [TDM:261]、"Thundering Falls" → [MKM:269]、"Stock Up" → [DFT:67]
- "Atraxa, Grand Unifier" → [ONE:196]、"Tamiyo, Inquisitive Student" 等

## 研究笔记

- `docs/research/computer-player-api.md` —— ComputerPlayer / Player 接口的关键 hooks
- `docs/research/test-harness.md` —— CardTestPlayerBaseAI 用法 + TestPlayer 接入
- `docs/research/dck-format.md` —— .dck 文件格式（含解析器正则）
- `docs/research/build-notes.md` —— Maven 构建注意事项（JAVA_HOME 必须显式指 Java 17）

## 上游同步

```bash
git fetch upstream
git checkout master
git merge upstream/master
git checkout mtgeek-b0
git rebase master   # 或 merge，看冲突情况
```

我们的两处既有类追加可能需要重新 apply（用 `git log -- <path>` 找原 commit，再 cherry-pick）。

## 路线

详细路线在 MTGeek 主仓的 spec 和 plan 中：
- 设计：`~/Documents/claude/MTGeek/docs/superpowers/specs/2026-06-01-mtg-ai-b0-xmage-design.md`
- 计划：`~/Documents/claude/MTGeek/docs/superpowers/plans/2026-06-01-mtg-ai-b0-xmage.md`

**当前 → 未来**：
- ✅ **B0'（本仓本期）**：地基 —— 构建 + 验牌 + trivial AI + 端到端日志
- ✅ **B1'（已完成）**：SimpleAI —— 值函数驱动可解释 AI（完赛率 ✅；胜率调参留 B1.1）
- ✅ **B1.1（已完成）**：调参与逻辑补全 —— Show-and-Tell 37.5%→50%，综合 53.3%→63.3%
- B2'：LLM-Agent（Java AI 经 HTTP 调外部 Python/JS 服务用 MiniMax 决策）
- ✅ **B3'（已完成）**：Web 观战基础 —— MatchRecorder + ReplayWriter 写 JSON 到 MTGeek 前端
- ✅ **B3.1（已完成）**：录制完整度 —— 7 类事件 + actor 追踪 + turn 追踪

### B1' （已完成）：SimpleAI 值函数驱动可解释 AI

`MTGeekSimplePlayer` 在 `MTGeekBasePlayer` 基础上加：
- 7 个 score 方法的 `ValueFunction`（cast/playland/attacker/block/target/yesno/pass）+ 21 种 Effect 子类 `instanceof` 分派
- MTG-tuned `Weights` 常量表（参 Tians SimpleAI v2，17 个常量分 7 类）
- 6 个决策钩子 override：`priority` + `selectAttackers` + `selectBlockers` + `chooseTarget` + `chooseMode` + `chooseUse`
- Lethal 短路 + legacy fallback + 失败-cast 追踪（防止 priority 循环）
- 结构化 `[Simple|*]` 决策日志（写入 GameLog 与原生事件交织）

跑端到端测试：
```bash
mvn test -pl Mage.Tests -Dtest=MTGeekSimpleMatchTest -DfailIfNoTests=false -Djava.awt.headless=true
```
match log 输出到 `Mage.Tests/target/simple-vs-trivial-match.log`，含可读 `[Simple|priority]` / `[Simple|selectAttackers]` 等行。

跑统计对战（60 场 ~1.5 分钟）：
```bash
mvn test -pl Mage.Tests -Dtest=Tournament -DfailIfNoTests=false -Djava.awt.headless=true
ls Mage.Tests/target/tournament-results-*.csv
```

#### 实测验收（30 场样本，2026-06-03）

| 验收项 | 实测 | spec 阈值 | 状态 |
|---|---|---|---|
| Simple vs Trivial 完赛率 | 100% (30/30) | ≥ 95% | ✅ |
| Simple vs ComputerPlayer 完赛率 | 100% (30/30) | ≥ 95% | ✅ |
| Simple vs Trivial 胜率 | 53.3% (16/30) | ≥ 60% | ⚠️ 近线 |
| match-log 含 [Simple|*] 决策日志 | 是 | 是 | ✅ |
| B0' 测试无回归 | 21/21 pass | 不破基线 | ✅ |

**胜率分解（deck-balanced）**：
- 用 Show-and-Tell combo 牌组：6/16 = 37.5%（combo 多步序列对线性求和值函数不友好）
- 用 Dimir Tempo 线性牌组：10/14 = **71.4%**（线性牌组明显强过 Trivial）

53.3% 综合数字未达 60%，根因是 Show-and-Tell 的非线性 combo 性质——spec §10 已预警此结构性限制。**B1.1 可针对 combo 牌组专项调参**（如：手牌 combo-piece 互相加分、关键 enabler 卡 hard-coded 高分），本期 B1' 范围内所有架构与基础设施工作均已完成。

### B1.1 （已完成）：调参与逻辑补全 — 修 Show-and-Tell 偏弱

针对 B1' Tournament 暴露的 Show-and-Tell 37.5% 偏弱问题，做的 4 层修复：
- **Layer A**：`ValueFunction` 加 `PutCardFromHandOntoBattlefieldEffect`（+ Show and Tell 走 Outcome 兜底）/ `CounterUnlessPaysEffect` handler；放行 sacrifice-mana abilities（Lotus Petal）
- **Layer B**：新增 `choose(Outcome, Cards, TargetCard, ...)` override
- **Layer C**：`ValueFunction.scoreHandCardAsThreat` 助手（P+T×2+T + CMC + keyword 子串识别）
- **Layer D**：4 个 Weights 微调（`PUT_FROM_HAND_PAYOFF=8.0`；TUTOR 3→5；LIFE_LOSS -1→-0.5；FACE 10→12）
- **Bonus fix**（首轮 Tournament 暴露的回归）：`chooseUse` 对"from hand onto battlefield"类问句返 YES；`chooseTarget` 识别 `TargetCardInHand` 用 `scoreHandCardAsThreat` 排序

#### 实测验收（30 场样本，2026-06-03）

| 项 | B1' 基线 | B1.1 实测 | spec 阈值 | 状态 |
|---|---|---|---|---|
| Show-and-Tell 胜率 | 37.5% | **50.0%** (8/16) | ≥ 50% | ✅ |
| Dimir 胜率 | 71.4% | **78.6%** (11/14) | ≥ 60% | ✅ |
| 综合 Simple vs Trivial | 53.3% | **63.3%** (19/30) | ≥ 55% | ✅ |
| Simple vs Trivial 完赛率 | 100% | **100%** (30/30) | 维持 100% | ✅ |
| Simple vs ComputerPlayer 完赛率 | 100% | **100%** (30/30) | ≥ 95% | ✅ |
| Simple vs ComputerPlayer 胜率 | 23.3% | **36.7%** (11/30) | — | ↑ |
| 既有 21+ 项测试 | GREEN | GREEN | 不破基线 | ✅ |

**关键调试发现**：首轮 Tournament 出现明显回归（Show-and-Tell 跌到 12.5%），诊断发现根因不是数值调参错而是 `chooseUse`/`chooseTarget` 对 Show-and-Tell 类卡的路径未覆盖——SimpleAI 在被问到"要把手牌放上场吗？"时返 NO，等于主动拒绝整个 combo。修复后回归消除并显著超越 B1' 基线。这次发现验证了 spec §7.4"迭代预案"的价值——单跑 Tournament 是发现这类 sequencing bug 的关键手段。

MTGeek 主仓：`~/Documents/claude/MTGeek/`（Next.js 智能问答前端，与本仓解耦）

### B3'（已完成）：Web 观战基础 — MatchRecorder + ReplayWriter

`MTGeekReplayMatchTest` 跑一场 SimpleAI vs SimpleAI 对局，录制结构化 replay JSON 写到 MTGeek 前端：

- `MatchRecorder`：XMage `DataCollector` 实现，监听 `onGameLog` 把原始 log 行解析成 `ReplayEvent`
- `ReplayWriter`：把 `MatchRecorder` 采集到的事件序列化成 JSON 文件，写到 `~/Documents/claude/MTGeek/public/replays/`
- `ReplayEvent`：POJO（`type`, `turn`, `actor`, `detail`, `seq`）+ 手写 `toJson()`（零额外依赖）

B3' 初版只解析 2 类事件（decision + cast_spell）且存在 turn 追踪、actor 填充等问题，由 B3.1 修复。

### B3.1（已完成）：录制完整度

修了 B3' MatchRecorder 只解析 2 类事件的限制：

- `DecisionLogger` 加 `playerName` → 新 log 行 `[Simple|PlayerA:priority] picked: ...`
- `MatchRecorder` 加 7 类事件正则：`play_land` / `life_change` / `draw` / `decision` / `attack` / `game_end` + decision actor 提取
- HTML strip 处理 XMage `<font>` 装饰 + `[hex]` suffix
- Turn 追踪用 `game.getTurnNum()`（XMage 不 emit `Turn N` 行）
- MTGeek 侧 `decision.actor` 类型收紧为必填，`EventLog`/`DecisionDetail` 显示决策归属

实测对比：事件类型从 B3' 的 2 类（decision+cast_spell）扩到 **7 类**；distinct turns 从 1 增至 17-40+；decision actor 填充率 0% → **100%**；Battlefield UI 真正反映对局进展（life 下降、battlefield 长卡）。

跑录制测试：
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export MAVEN_OPTS="-Xmx4g"
mvn test -pl Mage.Tests -Dtest=MTGeekReplayMatchTest -DfailIfNoTests=false -Djava.awt.headless=true
```

JSON 写到 `~/Documents/claude/MTGeek/public/replays/match-<timestamp>.json`，前端 `/replay` 页面可直接读取。

研究笔记：`docs/research/xmage-log-formats.md`
