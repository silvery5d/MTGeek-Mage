# ComputerPlayer API（XMage）研究笔记

> 调研日期：2026-06-01，XMage 提交：657051c8b2b662ac24bda84d1d7f6a55dbca7ca3

## 类继承链

```
ComputerPlayer extends PlayerImpl implements Player, Serializable
PlayerImpl implements Player, Serializable
Player extends MageItem, Copyable<Player>
```

源文件：
- `Mage.Server.Plugins/Mage.Player.AI/src/main/java/mage/player/ai/ComputerPlayer.java`（1355 行）
- `Mage/src/main/java/mage/players/PlayerImpl.java`（abstract class）
- `Mage/src/main/java/mage/players/Player.java`（interface）

Javadoc（ComputerPlayer 类注释，原文）：
> "AI: basic server side bot with simple actions support (game, draft, construction/sideboarding).
> Full and minimum implementation of all choose dialogs to allow AI to start and finish a real game.
> Used as parent class for any AI implementations."

## 公开构造函数

```java
// 1. 正式实例化入口（游戏创建 AI 玩家时使用）
public ComputerPlayer(String name, RangeOfInfluence range)
// - name：玩家名称字符串
// - range：影响范围枚举（通常 RangeOfInfluence.ALL）
// 内部行为：调用 super(name, range)，设 human=false，userData 设为 computer 组、avatar 64、flag "computer.png"

// 2. 复制构造（用于 AI 模拟/game copy）
public ComputerPlayer(final ComputerPlayer player)
// - player：被复制的原始实例
// 内部行为：调用 super(player)

// 3. protected UUID 构造（仅供内部子类反序列化等场景）
protected ComputerPlayer(UUID id)
// - id：玩家 UUID
// 内部行为：调用 super(id)，同样设为 computer 身份
```

父类 `PlayerImpl` 对应构造（`ComputerPlayer` 通过 `super(...)` 调用）：
```java
protected PlayerImpl(String name, RangeOfInfluence range)
protected PlayerImpl(UUID id)
protected PlayerImpl(final PlayerImpl player)
```

**MTGeekTrivialPlayer 应使用：**
```java
public MTGeekTrivialPlayer(String name, RangeOfInfluence range) {
    super(name, range);  // 调用 ComputerPlayer(String, RangeOfInfluence)
}
public MTGeekTrivialPlayer(final MTGeekTrivialPlayer player) {
    super(player);       // 调用 ComputerPlayer(ComputerPlayer)
}
```

## 关键决策钩子（按 priority 流程排序）

### 1. `priority(Game game)` → `boolean`

**接口定义：**
```java
// Player.java line 667
boolean priority(Game game);
```

**ComputerPlayer 中的默认实现（line 387-391）：**
```java
@Override
public boolean priority(Game game) {
    // minimum implementation for do nothing
    pass(game);
    return false;
}
```

- 返回类型：`boolean`（true = 本轮有行动；false = 让权/pass）
- 调用时机：游戏引擎在每个 priority 窗口（`GameImpl.java line 1742`）对当前 priority 持有者调用此方法，适用于所有阶段（主阶段、攻击前、战斗等）。调用前已检查 triggers/effects。
- 默认实现行为：直接调用 `pass(game)`，将 `this.passed = true`；返回 false，表示未采取任何行动。
- **我们的 override 策略**：这是 MTGeekTrivialPlayer 的核心入口。在此方法内通过 `game.getTurnStepType()` 检查当前步骤，执行下地 / 施法，其余情况调用 `pass(game)`。参考 `ComputerPlayer7.priorityPlay()` 的结构（见下文"参考案例"一节）。

### 2. `selectAttackers(Game game, UUID attackingPlayerId)` → `void`

**接口定义：**
```java
// Player.java line 770
void selectAttackers(Game game, UUID attackingPlayerId);
```

**ComputerPlayer 中的默认实现（line 909-911）：**
```java
@Override
public void selectAttackers(Game game, UUID attackingPlayerId) {
    // do nothing, parent class must implement it
}
```

- 返回类型：`void`
- 调用时机：宣告攻击步骤（DECLARE_ATTACKERS）由游戏引擎调用，让玩家宣告攻击者。
- 默认实现行为：**空方法**，什么都不做（等效于不宣告任何攻击者）。
- **我们的 override 策略**：遍历战场上所有我控制的生物，对每个可以攻击的生物调用 `declareAttacker(UUID attackerId, UUID defenderId, Game game, boolean allowUndo)` 宣告攻击（`PlayerImpl` 中已实现）。目标为第一个对手 `game.getOpponents(playerId).iterator().next()`。

### 3. `selectBlockers(Ability source, Game game, UUID defendingPlayerId)` → `void`

**接口定义：**
```java
// Player.java line 772
void selectBlockers(Ability source, Game game, UUID defendingPlayerId);
```

**ComputerPlayer 中的默认实现（line 913-916）：**
```java
@Override
public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
    // do nothing, parent class must implement it
}
```

- 返回类型：`void`
- 调用时机：宣告阻挡步骤（DECLARE_BLOCKERS）由游戏引擎调用。
- 默认实现行为：**空方法**，等效于不阻挡任何攻击者。
- **我们的 override 策略**：B0' trivial player **保留默认空实现**（不阻挡）。

### 4. `playMana(Ability ability, ManaCost unpaid, String promptText, Game game)` → `boolean`

**接口定义：**
```java
// Player.java line 695
boolean playMana(Ability ability, ManaCost unpaid, String promptText, Game game);
```

**ComputerPlayer 中的实现（line 394-404）：**
```java
@Override
public boolean playMana(Ability ability, ManaCost unpaid, String promptText, Game game) {
    payManaMode = true;
    lastUnpaidMana.put(ability.getId(), unpaid.copy());
    try {
        return playManaHandling(ability, unpaid, game);
    } finally {
        lastUnpaidMana.remove(ability.getId());
        payManaMode = false;
    }
}
```

- 返回类型：`boolean`（true = 成功支付了部分或全部 mana）
- 调用时机：在结算一个法术/能力的 mana 费用时，对每一部分未支付的费用被调用（可多次调用）。
- 默认实现行为：委托给 `playManaHandling()`，按照 colored → snow → colorless → hybrid → monohybrid → generic 顺序从 mana 产生源中选取合适 mana 支付。相当完整的自动付费逻辑。
- **我们的 override 策略**：**保留默认实现**。ComputerPlayer 的 mana 支付逻辑已经足够好，不需要覆盖。

### 5. `choose(Outcome outcome, Choice choice, Game game)` → `boolean`

**ComputerPlayer 中的实现（line 785-834）：**
```java
@Override
public boolean choose(Outcome outcome, Choice choice, Game game)
```

- 调用时机：需要从预定义选项列表中做选择时（如选颜色）。
- 默认实现行为：优先选择与未支付 mana 颜色匹配的选项；否则随机选择。
- **我们的 override 策略**：**保留默认实现**。

### 6. `chooseTarget(Outcome outcome, Target target, Ability source, Game game)` → `boolean`

**接口定义（Player.java line 679）：**
```java
boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game);
```

**ComputerPlayer 中的实现（line 224-227）：**
```java
@Override
public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
    return makeChoice(outcome, target, source, game, null);
}
```

**重载（line 893-895）：**
```java
@Override
public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
    return makeChoice(outcome, target, source, game, cards);
}
```

- 调用时机：法术或能力需要选择目标时。
- 默认实现行为：通过 `PossibleTargetsSelector` 分析目标，优先选有利目标（good targets = 对手/对手生物），最后才选不利目标（bad targets = 自己的）。
- **我们的 override 策略**：**保留默认实现**。

### 7. `choose(Outcome outcome, Target target, Ability source, Game game)` → `boolean`

**接口定义（Player.java line 673-675）：两个重载：**
```java
boolean choose(Outcome outcome, Target target, Ability source, Game game);
boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options);
```

**ComputerPlayer 实现（line 119-126）：**
```java
@Override
public boolean choose(Outcome outcome, Target target, Ability source, Game game) {
    return choose(outcome, target, source, game, null);
}
@Override
public boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options) {
    return makeChoice(outcome, target, source, game, null);
}
```

- 调用时机：非 target 类型的 choose 对话（如选择手牌中的牌等），内部调用与 chooseTarget 相同的 `makeChoice()`。
- **我们的 override 策略**：**保留默认实现**。

### 8. `chooseUse(Outcome outcome, String message, Ability source, Game game)` → `boolean`

**接口定义（Player.java line 687-689）：两个重载：**
```java
boolean chooseUse(Outcome outcome, String message, Ability source, Game game);
boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText, Ability source, Game game);
```

**ComputerPlayer 实现（line 772-781）：**
```java
@Override
public boolean chooseUse(Outcome outcome, String message, Ability source, Game game) {
    return chooseUse(outcome, message, null, null, null, source, game);
}
@Override
public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText, Ability source, Game game) {
    return outcome != Outcome.AIDontUseIt;
}
```

- 默认实现行为：除 `Outcome.AIDontUseIt` 外，永远返回 true（"主动使用"）。
- **我们的 override 策略**：**保留默认实现**。

### 9. `chooseAbilityForCast(Card card, Game game, boolean noMana)` → `SpellAbility`

**接口定义（Player.java line 452）：**
```java
SpellAbility chooseAbilityForCast(Card card, Game game, boolean noMana);
```

**ComputerPlayer 实现（line 1313-1319）：**
```java
@Override
public SpellAbility chooseAbilityForCast(Card card, Game game, boolean noMana) {
    Map<UUID, SpellAbility> usable = PlayerImpl.getCastableSpellAbilities(game, this.getId(), card, game.getState().getZone(card.getId()), noMana);
    return usable.values().stream()
            .filter(a -> a.getTargets().canChoose(getId(), a, game))
            .findFirst()
            .orElse(null);
}
```

- 调用时机：当一张牌有多个施法方式（如 split card）时，选择使用哪个 SpellAbility。
- 默认实现行为：选第一个有合法目标的施法能力。
- **我们的 override 策略**：**保留默认实现**。

### 10. `chooseMulligan(Game game)` → `boolean`

**ComputerPlayer 实现（line 106-116）：**
```java
@Override
public boolean chooseMulligan(Game game) {
    if (hand.size() < 6 || isTestMode() || game.getClass().getName().contains("Momir")) {
        return false;
    }
    Set<Card> lands = hand.getCards(new FilterLandCard(), game);
    return lands.size() < 2 || lands.size() > hand.size() - 2;
}
```

- 默认实现行为：手牌少于 6 张不再换牌；手牌地牌少于 2 或多于（手牌数 - 2）时换牌（即地牌太多或太少）。
- **我们的 override 策略**：**保留默认实现**，简单合理。

## 攻防

### 选择攻击者

```java
// ComputerPlayer 中（line 909-911）
@Override
public void selectAttackers(Game game, UUID attackingPlayerId) {
    // do nothing, parent class must implement it
}
```

用于宣告攻击者的 PlayerImpl 底层方法（可在 selectAttackers override 中直接调用）：
```java
// PlayerImpl.java
public boolean declareAttacker(UUID attackerId, UUID defenderId, Game game, boolean allowUndo)
```

参考实现（ComputerPlayer6/ComputerPlayer7，line 1188-1191）：
```java
@Override
public void selectAttackers(Game game, UUID attackingPlayerId) {
    declareAttackers(game, playerId);  // ComputerPlayer6 的内部方法，做全模拟
}
```

**我们的 override：** 遍历 `game.getBattlefield().getAllActivePermanents(playerId)` 找能攻击的生物，对每个调用 `declareAttacker(creatureId, opponentId, game, false)`。

### 选择阻挡者

```java
// ComputerPlayer 中（line 913-916）
@Override
public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
    // do nothing, parent class must implement it
}
```

- **我们的 override：保留空默认**（B0' 不阻挡）。

## 目标选择

所有重载都已在"关键决策钩子"一节列出。汇总如下：

```java
// ComputerPlayer.java 实际重载（4 个）：
boolean choose(Outcome outcome, Target target, Ability source, Game game)                           // line 119
boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String,Serializable>) // line 124
boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game)          // line 898
boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game)                     // line 225
boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game)    // line 893
boolean chooseTargetAmount(Outcome outcome, TargetAmount target, Ability source, Game game)         // line 230
```

默认行为：通过 `PossibleTargetsSelector` + `PossibleTargetsComparator` 自动选择好/坏目标。对我们的 trivial player 来说这是已有的合理实现。

**我们的 override 策略：保留全部默认**。

## 下地与施法的底层辅助 API（priority override 内使用）

我们在 `priority()` override 内需要直接使用如下方法（全部来自 PlayerImpl）：

```java
// 手牌
Cards getHand()                              // 返回手牌 CardsImpl（实现 Cards 接口）

// 判断能否下地
boolean canPlayLand()                        // PlayerImpl line 1925：landsPlayed < landsPerTurn

// 下地
boolean playLand(Card card, Game game, boolean ignoreTiming)  // PlayerImpl line 1428
// - ignoreTiming=false 时做时机检查；trivial player 在 priority 窗口调用时应传 false

// 施法
boolean cast(SpellAbility ability, Game game, boolean noMana, ApprovingObject approvingObject)
// PlayerImpl line 1341；noMana=false 正常付费；approvingObject=null 表示没有免费施法效果

// 获取可施放的能力列表（包含 mana 检查，不含目标检查）
List<ActivatedAbility> getPlayable(Game game, boolean hidden)  // PlayerImpl line 4288

// 获取可施放的法术能力（特定牌）
static Map<UUID, SpellAbility> getCastableSpellAbilities(Game game, UUID playerId, Card card, Zone zone, boolean noMana)
// PlayerImpl static method

// 让权
void pass(Game game)    // PlayerImpl line 2653：设 passed=true

// 查询当前步骤
game.getTurnStepType()  // 返回 PhaseStep 枚举
game.isActivePlayer(playerId)  // 判断是否是我的回合
```

## 参考案例：ComputerPlayer7.priorityPlay() 的结构

`ComputerPlayer7`（`Mage.Player.AI.MA` 模块）覆盖了 `priority()`，其内部 `priorityPlay()` 展示了正确的步骤分支写法：

```java
// ComputerPlayer7.java line 45-111
private boolean priorityPlay(Game game) {
    game.getState().setPriorityPlayerId(playerId);
    game.firePriorityEvent(playerId);
    switch (game.getTurnStepType()) {
        case UPKEEP:
        case DRAW:
            pass(game); return false;
        case PRECOMBAT_MAIN:
            // ... calculateActions(game); act(game); return true;
        case BEGIN_COMBAT:
            pass(game); return false;
        case DECLARE_ATTACKERS:
            // ...
        case DECLARE_BLOCKERS:
            // ...
        case POSTCOMBAT_MAIN:
            // ...
        case END_TURN:
        case CLEANUP:
            pass(game); return false;
    }
    return false;
}
```

**MTGeekTrivialPlayer 的 priority() 应仿照此结构**，在 PRECOMBAT_MAIN / POSTCOMBAT_MAIN 步骤执行下地 + 施法，其余全部 pass。

`PhaseStep` 枚举值（`mage.constants.PhaseStep`）：
- `UNTAP`, `UPKEEP`, `DRAW`
- `PRECOMBAT_MAIN`（M1）
- `BEGIN_COMBAT`, `DECLARE_ATTACKERS`, `DECLARE_BLOCKERS`
- `FIRST_COMBAT_DAMAGE`, `COMBAT_DAMAGE`, `END_COMBAT`
- `POSTCOMBAT_MAIN`（M2）
- `END_TURN`, `CLEANUP`

## 我们不会 override 的（保留默认）

下列方法 ComputerPlayer 已实现良好，MTGeekTrivialPlayer 无需覆盖：

| 方法 | 位置 | 原因 |
|---|---|---|
| `playMana(...)` | CP line 394 | 自动付费逻辑完整，按颜色类型优先级正确选择 mana 源 |
| `playManaHandling(...)` | CP line 406 | protected helper，随 playMana 自动工作 |
| `chooseMulligan(Game)` | CP line 106 | 简单合理的地牌比例判断 |
| `choose(Outcome, Choice, Game)` | CP line 785 | 颜色选择 + 随机选择，足够用 |
| `chooseTarget(...)` 全部重载 | CP line 224+ | PossibleTargetsSelector 自动选目标 |
| `choose(...)` 全部重载 | CP line 119+ | 同上 |
| `chooseUse(...)` 全部重载 | CP line 772 | 默认"主动使用"合理 |
| `chooseTargetAmount(...)` | CP line 230 | 自动分配伤害/数量 |
| `chooseMode(...)` | CP line 925 | 选第一个有合法目标的 mode |
| `chooseTriggeredAbility(...)` | CP line 942 | 选第一个 trigger |
| `chooseAbilityForCast(...)` | CP line 1313 | 选第一个可施放 SpellAbility |
| `choosePile(...)` | CP line 903 | 永远选左堆 |
| `chooseReplacementEffect(...)` | CP line 919 | 选第一个替换效果 |
| `announceX(...)` | CP line 758 | 根据可用 mana 自动计算 X 值 |
| `getAmount(...)` | CP line 951 | 委托给 makeChoiceAmount |
| `abort()` | CP line 763 | 设置终止标志 |
| `skip()` | CP line 768 | 空实现（正确） |
| `selectBlockers(...)` | CP line 913 | 空实现（B0' 不阻挡） |
| `sideboard(...)` | CP line 983 | 直接提交原始牌组 |
| `pickCard(...)` | CP line 1169 | draft 用，游戏中不需要 |
| `cleanUpOnMatchEnd()` | CP line 1303 | 调用 super，足够 |
| `copy()` | CP line 1308 | 需要 override 以返回正确类型（但模式不同） |

**注意：`copy()` 必须 override 以返回 `MTGeekTrivialPlayer` 类型，这是 XMage 游戏复制机制的要求。**

## 风险 / 不确定

1. **`selectAttackers` 的 `declareAttacker()` 调用时机**：未确认在 `selectAttackers()` 内部调用 `declareAttacker()` 是否需要先调用 `game.getCombat().setAttackingPlayer(...)` 之类的初始化。需要 Task 13 实际运行测试验证。

2. **`getPlayable()` 的 nonland spell 过滤**：`getPlayable(game, true)` 返回 `List<ActivatedAbility>`，其中包含地牌的 PlayLandAbility 和非地牌的 SpellAbility，需要在 trivial player 内用 `card.isLand(game)` 或检查 `ability instanceof SpellAbility` 正确过滤。

3. **`playLand()` 的 `ignoreTiming` 参数**：传 `false` 时要求当前为合法时机（是我的回合、主阶段、stack 为空等）。如果 priority 在非主阶段被调用，`canPlayLand()` 返回 true 但 `playLand()` 可能因时机检查失败而返回 false。建议在调用前额外检查 `game.isActivePlayer(playerId)` 和 `game.getTurnStepType()` 是主阶段。

4. **`cast()` 的 `ApprovingObject` 参数**：正常施法传 `null`；若卡牌有免费施法效果则不为 null。Trivial player 暂时总传 `null`，可能导致部分有"as though without paying mana cost"效果的牌无法正确施放，但对基本功能无影响。

5. **DECLARE_ATTACKERS 阶段 priority 与 selectAttackers 的关系**：XMage 中宣告攻击者是通过 `selectAttackers()` 钩子完成的，不是在 `priority()` 内完成。ComputerPlayer7 在 DECLARE_ATTACKERS 阶段的 `priority()` 里调用 `act(game)` 执行模拟结果中的动作（实际攻击宣告已在 `selectAttackers` 完成）。Trivial player 可以在 DECLARE_ATTACKERS 阶段直接 pass，攻击在 `selectAttackers()` override 里处理。

6. **`copy()` override**：`ComputerPlayer.copy()` 返回 `new ComputerPlayer(this)`，子类必须 override 返回 `new MTGeekTrivialPlayer(this)`，否则游戏复制时会产生类型不一致错误。Task 11 需要确认。
