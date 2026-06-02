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
- B2'：LLM-Agent（Java AI 经 HTTP 调外部 Python/JS 服务用 MiniMax 决策）
- B3'：Web 观战页（MTGeek Next.js 接到 fork 的 HTTP 服务）

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
| Simple vs Trivial 完赛率 | 96.7% (29/30) | ≥ 95% | ✅ |
| Simple vs ComputerPlayer 完赛率 | 100% (30/30) | ≥ 95% | ✅ |
| Simple vs Trivial 胜率 | 33.3% (10/30) | ≥ 60% | ❌ |
| match-log 含 [Simple|*] 决策日志 | 是 | 是 | ✅ |
| B0' 测试无回归 | 21/21 pass | 不破基线 | ✅ |

**胜率未达原因**：当前 Weights 常数对两副真牌（Show-and-Tell combo + Dimir Tempo）的决策偏差大；尤其 SimpleAI 对 combo 牌（Sneak Attack/Show and Tell + Emrakul/Atraxa）的"非线性"价值没法用线性求和体现，spec §10 已预警此结构性限制。**B1.1 后续可专项调参**——本期 B1' 范围内的所有架构、决策钩子、日志体系工作均已完成。

MTGeek 主仓：`~/Documents/claude/MTGeek/`（Next.js 智能问答前端，与本仓解耦）
