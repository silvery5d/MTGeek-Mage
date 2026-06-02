# Game State API 研究笔记（B1' ValueFunction + MTGeekSimplePlayer 用）

> 调研日期：2026-06-02，XMage commit：d083271c2f63df333ce170ee4a6d448baeadeedb

---

## 1. Game 接口

**接口**：`mage.game.Game`（`Mage/src/main/java/mage/game/Game.java`）

实际需要的方法签名（均来自 `Game.java` interface 声明或 default 实现）：

| 方法签名 | 返回类型 | 说明 |
|----------|----------|------|
| `getPlayer(UUID playerId)` | `Player` | 按 UUID 取玩家对象（实现在 `GameImpl.java:404`） |
| `getOpponents(UUID playerId)` | `Set<UUID>` | default；调用 `getOpponents(playerId, false)` |
| `getOpponents(UUID playerId, boolean excludeLeavedPlayers)` | `Set<UUID>` | default；`excludeLeavedPlayers=true` 可排除已离开玩家 |
| `getBattlefield()` | `Battlefield` | `Game.java:267`；delegate 到 `GameState.getBattlefield()` |
| `getStack()` | `SpellStack` | `Game.java:269`；迭代当前堆叠中的对象 |
| `hasEnded()` | `boolean` | `Game.java:265`；游戏是否已有胜负 |
| `getActivePlayerId()` | `UUID` | `Game.java:259`；当前行动回合玩家 UUID |
| `getTurnStepType()` | `PhaseStep` | `Game.java:232`；当前所在步骤，可能返回 `null`（步骤未初始化时） |
| `getTurn()` | `Turn` | `Game.java:227`；也可用于取 `Turn.getPhase().getStep().getType()` |
| `informPlayers(String message)` | `void` | `Game.java:386`；B1' DecisionLogger 用，广播日志给所有玩家/观察者 |
| `isActivePlayer(UUID playerId)` | `boolean` | default（`Game.java:209`）；判断某玩家是否为当前行动玩家 |

> **注意**：`getTurnStepType()` 的实现 (`GameState.java:582`) 做了 null 链保护，若 `turn/phase/step` 未初始化则返回 `null`——B1' 代码中应做 null 检查。

---

## 2. Player 接口

**接口**：`mage.players.Player`（`Mage/src/main/java/mage/players/Player.java`）

| 方法签名 | 返回类型 | 说明 |
|----------|----------|------|
| `getLife()` | `int` | `Player.java:120`；当前血量 |
| `getManaPool()` | `ManaPool` | `Player.java:307`；当前 mana pool 对象 |
| `getHand()` | `Cards` | `Player.java:227`；手牌（`Cards` 接口，有 `size()`） |
| `getLibrary()` | `Library` | `Player.java:104`；牌库对象；用 `library.size()` 取牌库数量（`Library.java:140`） |
| `getLandsPlayed()` | `int` | `Player.java:233`；本回合已下地数量 |
| `canPlayLand()` | `boolean` | `Player.java:495`；等价于 `landsPlayed < landsPerTurn`（`PlayerImpl.java:1925`）；是否还能下地 |
| `isPassed()` | `boolean` | `Player.java:247`；玩家是否已 pass priority |
| `isInGame()` | `boolean` | `Player.java:297`；玩家是否仍在游戏中（未认负/未被消灭） |
| `getLogName()` | `String` | `Player.java:100`；玩家显示名，DecisionLogger 用 |
| `getPlayable(Game game, boolean hidden)` | `List<ActivatedAbility>` | `Player.java:858`；当前可激活的所有 ability 列表；`hidden=true` 包含手牌中可施法的咒语 |

### ManaPool 取未消耗 mana 总量

`player.getManaPool().count()` → `int`

- `ManaPool.count()`（`ManaPool.java:459`）：迭代所有 `ManaPoolItem`，累加每项 `item.count()`，返回所有颜色 + colorless + generic 的总量。
- 也可用 `player.getManaPool().getMana()` → `Mana`，再调 `mana.count()`（`Mana.java:523`）：`countColored() + generic + colorless`。
- **推荐用法**：`int unspentMana = player.getManaPool().count();`

---

## 3. Battlefield

**类**：`mage.game.permanent.Battlefield`（`Mage/src/main/java/mage/game/permanent/Battlefield.java`）

| 方法签名 | 返回类型 | 说明 |
|----------|----------|------|
| `getAllActivePermanents()` | `List<Permanent>` | `Battlefield.java:188`；全部在场 permanent（所有玩家） |
| `getAllActivePermanents(UUID controllerId)` | `List<Permanent>` | `Battlefield.java:199`；指定控制者的所有在场 permanent |
| `getAllActivePermanents(CardType type, Game game)` | `List<Permanent>` | `Battlefield.java:213`；按牌张类型过滤 |
| `getAllActivePermanents(FilterPermanent filter, Game game)` | `List<Permanent>` | `Battlefield.java:227`；通用过滤器版本 |
| `getAllActivePermanents(FilterPermanent filter, UUID controllerId, Game game)` | `List<Permanent>` | `Battlefield.java:239`；控制者 + 过滤器双重筛选 |
| `getActivePermanents(FilterPermanent filter, UUID sourcePlayerId, Game game)` | `List<Permanent>` | `Battlefield.java:250`；含 source 玩家视角的能见度检查 |
| `getActivePermanents(UUID sourcePlayerId, Game game)` | `List<Permanent>` | `Battlefield.java:277`；无过滤器版本 |
| `getPermanent(UUID key)` | `Permanent` | `Battlefield.java:149`；按 UUID 取单个 permanent |
| `containsPermanent(UUID key)` | `boolean` | `Battlefield.java:163`；检查某 UUID 是否在场 |
| `getAllPermanents()` | `Collection<Permanent>` | `Battlefield.java:184`；包含正在进入（entering）的 permanent |

---

## 4. Permanent 接口

**接口**：`mage.game.permanent.Permanent extends Card, Controllable`（`Mage/src/main/java/mage/game/permanent/Permanent.java`）

### 类型判断（来自 `MageObject` default 方法，`MageObject.java`）

| 方法签名 | 返回类型 | 说明 |
|----------|----------|------|
| `isCreature()` | `boolean` | `MageObject.java:186`；无需 game context |
| `isCreature(Game game)` | `boolean` | `MageObject.java:190`；带 game context（考虑类型修改效果） |
| `isLand()` | `boolean` | `MageObject.java:202` |
| `isLand(Game game)` | `boolean` | `MageObject.java:206` |
| `isPlaneswalker()` | `boolean` | `MageObject.java:242` |
| `isPlaneswalker(Game game)` | `boolean` | `MageObject.java:246` |

> **B1' 用法建议**：优先用带 `Game game` 参数的重载，以正确处理类型修改效果（如 Humility）。

### 战斗相关（来自 `Permanent.java`）

| 方法签名 | 返回类型 | 说明 |
|----------|----------|------|
| `isTapped()` | `boolean` | `Permanent.java:26`；是否已横置 |
| `hasSummoningSickness()` | `boolean` | `Permanent.java:163`；召唤师晕眩（无 Game 参数） |
| `canAttack(UUID defenderId, Game game)` | `boolean` | `Permanent.java:353`；该生物能否攻击指定防御方 |
| `canAttackInPrinciple(UUID defenderId, Game game)` | `boolean` | `Permanent.java:362`；原则上能否攻击（不考虑当前 tapped 状态） |
| `getBlocking()` | `int` | `Permanent.java:312`；该生物正在阻挡的攻击者数量 |
| `getBlockingRefs()` | `Set<MageObjectReference>` | `Permanent.java:326`；正在阻挡的攻击者引用集合 |

### P/T 与计数器（来自 `MageObject` + `Card`）

| 方法签名 | 返回类型 | 说明 |
|----------|----------|------|
| `getPower()` | `MageInt` | `MageObject.java:143`；战力；取数值用 `.getValue()` → `int` |
| `getToughness()` | `MageInt` | `MageObject.java:145`；防御力；`.getValue()` → `int` |
| `getCounters(Game game)` | `Counters` | `Card.java:155` → `PermanentImpl.java:520`；取所有计数器 |
| `getCounters(GameState state)` | `Counters` | `Card.java:157`；带 GameState 版本 |

**Planeswalker loyalty**：`permanent.getCounters(game).getCount(CounterType.LOYALTY)` → `int`（来自 `PermanentImpl.java:1184`）

**+1/+1 计数器**：`permanent.getCounters(game).getCount(CounterType.P1P1)` → `int`

### ID / 控制者

| 方法签名 | 返回类型 | 说明 |
|----------|----------|------|
| `getId()` | `UUID` | `MageItem.java:12`；该 permanent 的 UUID |
| `getControllerId()` | `UUID` | `Controllable.java:10`（由 `PermanentImpl.java:933` 实现）；控制者玩家 UUID |

---

## 5. PhaseStep 枚举

**完整枚举**（`mage.constants.PhaseStep`，`Mage/src/main/java/mage/constants/PhaseStep.java`）：

共 **13** 个值（index 0–12）：

| index | 枚举值 | 简写 | 说明 |
|-------|--------|------|------|
| 0 | `UNTAP` | UN | 解横置步骤 |
| 1 | `UPKEEP` | UP | 维持步骤 |
| 2 | `DRAW` | DR | 抽牌步骤 |
| 3 | `PRECOMBAT_MAIN` | M1 | 主要阶段一（B1' 施法判断点） |
| 4 | `BEGIN_COMBAT` | BC | 战斗开始步骤 |
| 5 | `DECLARE_ATTACKERS` | DA | 宣告攻击者步骤 |
| 6 | `DECLARE_BLOCKERS` | DB | 宣告阻挡者步骤 |
| 7 | `FIRST_COMBAT_DAMAGE` | FCD | 先攻伤害步骤 |
| 8 | `COMBAT_DAMAGE` | CD | 战斗伤害步骤 |
| 9 | `END_COMBAT` | EC | 战斗结束步骤 |
| 10 | `POSTCOMBAT_MAIN` | M2 | 主要阶段二（B1' 施法判断点） |
| 11 | `END_TURN` | ET | 回合结束步骤 |
| 12 | `CLEANUP` | CL | 清理步骤 |

**B1' 主阶段判断用法**：

```java
PhaseStep step = game.getTurnStepType();
boolean isMainPhase = step == PhaseStep.PRECOMBAT_MAIN
                   || step == PhaseStep.POSTCOMBAT_MAIN;
```

**辅助方法**：
- `PhaseStep.isBefore(PhaseStep other)` → `boolean`（`index < other.index`）
- `PhaseStep.isAfter(PhaseStep other)` → `boolean`
- `PhaseStep.fromString(String text)` → `PhaseStep`（找不到返回 `null`）
- `PhaseStep.getIndex()` → `int`

---

## 6. PlayerImpl / ComputerPlayer 行动方法

**来源**：`mage.players.PlayerImpl`（`Mage/src/main/java/mage/players/PlayerImpl.java`）

| 方法签名 | 返回类型 | 说明 |
|----------|----------|------|
| `getPlayable(Game game, boolean hidden)` | `List<ActivatedAbility>` | `PlayerImpl.java:4288`；`hidden=true` 包含手牌可施放的咒语 |
| `getPlayable(Game originalGame, boolean hidden, Zone fromZone, boolean hideDuplicatedAbilities)` | `List<ActivatedAbility>` | `PlayerImpl.java:4304`；更细粒度控制 |
| `playLand(Card card, Game game, boolean ignoreTiming)` | `boolean` | `PlayerImpl.java:1428`；下地；成功返回 `true` |
| `activateAbility(ActivatedAbility ability, Game game)` | `boolean` | `PlayerImpl.java:1666`；激活 ability（包含施法）；成功返回 `true` |
| `declareAttacker(UUID attackerId, UUID defenderId, Game game, boolean allowUndo)` | `void` | `PlayerImpl.java:2901`；宣告攻击 |
| `declareBlocker(UUID defenderId, UUID blockerId, UUID attackerId, Game game)` | `void` | `PlayerImpl.java:2916`；宣告阻挡（defender = 被攻击方玩家 UUID） |
| `declareBlocker(UUID defenderId, UUID blockerId, UUID attackerId, Game game, boolean allowUndo)` | `void` | `PlayerImpl.java:2921`；带 allowUndo 版本 |

**ComputerPlayer** 覆盖的方法（`Mage.Server.Plugins/Mage.Player.AI/src/main/java/mage/player/ai/ComputerPlayer.java`）：

| 方法签名 | 返回类型 | 说明 |
|----------|----------|------|
| `chooseTarget(Outcome outcome, Target target, Ability source, Game game)` | `boolean` | `ComputerPlayer.java:225`；选择目标 |
| `chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game)` | `boolean` | `ComputerPlayer.java:893`；从牌集合选目标 |

---

## 7. ActivatedAbility 相关

从 `getPlayable(...)` 返回的 `List<ActivatedAbility>` 中，B1' 需要的信息：

- `ability.getSourceId()` → `UUID`：关联 Card/Permanent 的 UUID
- `ability.getControllerId()` → `UUID`：控制者 UUID
- `ability instanceof SpellAbility`：判断是施法还是激活异能
- `game.getCard(ability.getSourceId())` → `Card`：取对应牌张对象

---

## 8. 风险与不确定项

| 项目 | 状态 | 说明 |
|------|------|------|
| `getTurnStepType()` 返回 null | **已确认** | 游戏未开始时步骤链可能为 null；B1' 代码需 null 检查 |
| `ManaPool.count()` 计数含 conditional mana | **已确认** | `count()` 包含所有 `ManaPoolItem`，含有限制条件的 mana；若需"可无条件使用"的 mana，需自行过滤 `ConditionalMana` |
| `isCreature(Game game)` vs `isCreature()` | **已确认** | 带 game 参数的版本才能反映类型修改效果，B1' 应统一使用带 game 参数的重载 |
| `declareAttacker` 参数名 `allowUndo` | **已确认** | 第4参数是 `allowUndo`（不是 `checkLegality`），设为 `false` 可避免 UI undo 开销 |
| `getCounters(Game)` vs `getCounters(GameState)` | **已确认** | 两个重载均存在；在有 `Game` 对象时优先用 `getCounters(game)` |
| 手牌可施法数量推断 | 无风险 | `player.getHand().size()` 直接可用（`Cards` 继承 `Collection`） |
