# 测试 Harness（XMage）研究笔记

> 调研日期：2026-06-01，XMage 提交：f004d6dfd52cd73a43d79811593af77fa442abb9

## 继承链

```
CardTestPlayerBaseAI
  extends CardTestPlayerAPIImpl
    extends MageTestPlayerBase
      implements CardTestAPI
```

`CardTestPlayerBase` 也 extends `CardTestPlayerAPIImpl`，但它在构造器里设置
`deckNameA = "RB Aggro.dck"` 并覆盖了 `createNewGameAndPlayers()` 传入 deckName。
`CardTestPlayerBaseAI` 不设 deckName，而是覆盖 `createPlayer(String name, RangeOfInfluence)` 来注入 AI player（见下文）。

源文件路径：

- `Mage.Tests/src/test/java/org/mage/test/serverside/base/CardTestPlayerBaseAI.java` (67 行)
- `Mage.Tests/src/test/java/org/mage/test/serverside/base/CardTestPlayerBase.java` (34 行)
- `Mage.Tests/src/test/java/org/mage/test/serverside/base/MageTestPlayerBase.java` (509 行)
- `Mage.Tests/src/test/java/org/mage/test/serverside/base/impl/CardTestPlayerAPIImpl.java` (2575 行)

---

## 关键方法

### 创建游戏

定义在 `CardTestPlayerBaseAI`：

```java
@Override
protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
    Game game = new TwoPlayerDuel(
        MultiplayerAttackOption.LEFT,
        RangeOfInfluence.ONE,
        MulliganType.GAME_DEFAULT.getMulligan(0),
        60, 20, 7   // startLife=20, maxHandSize=7
    );
    playerA = createPlayer(game, "PlayerA");
    playerB = createPlayer(game, "PlayerB");
    return game;
}
```

调用时机：`@Before reset()` 方法（定义在 `CardTestPlayerAPIImpl`）在每个 `@Test` 前自动调用，
内部调用 `currentGame = createNewGameAndPlayers()`。

### 加载套牌

**方法 A（推荐）：`addCard(Zone, TestPlayer, String, int, boolean)`**

```java
// 签名（CardTestPlayerAPIImpl）
public void addCard(Zone gameZone, TestPlayer player, String cardName)
public void addCard(Zone gameZone, TestPlayer player, String cardName, int count)
public void addCard(Zone gameZone, TestPlayer player, String cardName, int count, boolean tapped)
```

用途：在 `@Test` 体内直接给玩家的指定区域（HAND / BATTLEFIELD / LIBRARY / GRAVEYARD / EXILED）
添加具名卡牌，卡牌从 `CardRepository` 按名称查找。常见用法：

```java
addCard(Zone.HAND,        playerA, "Silvercoat Lion");
addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
addCard(Zone.LIBRARY,     playerA, "Mountain", 5);
```

**方法 B：`setDecknamePlayerA(String deckname)` / `setDecknamePlayerB(String)`**

```java
// 签名（CardTestPlayerAPIImpl）
public void setDecknamePlayerA(String deckname)
public void setDecknamePlayerB(String deckname)
```

在 `@Test` 开始前（`@Before` 之后，`execute()` 之前）调用，会改变 `deckNameA` / `deckNameB`，
下次 `createPlayer(game, name, deckName)` 时生效。

注意：`CardTestPlayerBaseAI.createNewGameAndPlayers()` 调用的是
`createPlayer(game, "PlayerA")`（无 deckName 参数），最终走到：

```java
// CardTestPlayerAPIImpl
protected TestPlayer createPlayer(Game game, String name) throws GameException {
    return createPlayer(game, name, "RB Aggro.dck");  // 默认 deck
}
```

因此 `CardTestPlayerBaseAI` 测试默认使用 `"RB Aggro.dck"`；使用 `setDecknamePlayerA()` 可覆盖。

**方法 C：`DeckImporter.importDeckFromFile(String deckName, boolean fixMissingCards)`**

内部实现在 `createPlayer(game, name, deckName)` 里：

```java
// CardTestPlayerAPIImpl（第 228 行）
DeckCardLists list = DeckImporter.importDeckFromFile(deckName, true);
Deck deck = Deck.load(list, false, false, loadedCardInfo);
game.loadCards(deck.getCards(), player.getId());
game.loadCards(deck.getSideboard(), player.getId());
game.addPlayer(player, deck);
```

`deckName` 是相对路径（相对于 Mage.Tests 工作目录）或绝对路径。

**推荐：用方法 B（`setDecknamePlayerA/B`）配合实际 `.dck` 文件**，这样套牌来自真实文件，
既能测试套牌加载，也让两个 AI 都有完整套牌对打。

### 注入自定义 Player 类

**Override 哪个方法：**`createPlayer(String name, RangeOfInfluence rangeOfInfluence)`
定义在 `MageTestPlayerBase`（第 232 行），被 `CardTestPlayerBaseAI` 已 override。

原始定义（`MageTestPlayerBase`）：

```java
protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
    return new TestPlayer(new TestComputerPlayer(name, rangeOfInfluence));
}
```

`CardTestPlayerBaseAI` 的覆盖：

```java
@Override
protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
    if (getFullSimulatedPlayers().contains(name)) {
        TestPlayer testPlayer = new TestPlayer(new TestComputerPlayer7(name, RangeOfInfluence.ONE, getSkillLevel()));
        testPlayer.setAIPlayer(true);
        return testPlayer;
    }
    return super.createPlayer(name, rangeOfInfluence);
}
```

因此要注入自定义 player，只需在子类再次 override 同一方法。

**默认 player 类：**

- `CardTestPlayerBase`（非 AI 路径）→ `TestComputerPlayer extends ComputerPlayer`
- `CardTestPlayerBaseAI`（AI 路径）→ `TestComputerPlayer7 extends ComputerPlayer7`

**`TestPlayer` 的构造器（接受三种 inner-player 类型）：**

```java
// TestPlayer.java（第 126-142 行）
public TestPlayer(TestComputerPlayer computerPlayer)
public TestPlayer(TestComputerPlayer7 computerPlayer)
public TestPlayer(TestComputerPlayerMonteCarlo computerPlayer)
```

`TestPlayer implements Player`（XMage 核心接口）。
`TestPlayer` 持有一个 `final ComputerPlayer computerPlayer` 字段，
所有决策请求先走 `TestPlayer` 的动作队列，队列用完再 fallback 到 inner `computerPlayer`。

**AI vs AI 示例（真实测试，来自 `SimulationPerformanceAITest.java`）：**

```java
public class SimulationPerformanceAITest extends CardTestPlayerBaseAI {

    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA", "PlayerB"); // 两个玩家都是 AI
    }
}
```

默认 `getFullSimulatedPlayers()` 只返回 `["PlayerA"]`。

**`getSkillLevel()`** 默认返回 `6`（对应 `ComputerPlayer7` 的 skill 参数），可 override 调整。

### 跑游戏到结束

**方法：`execute()`**

```java
// CardTestPlayerAPIImpl（第 244 行）
public void execute() throws IllegalStateException
```

内部流程：
1. 检查所有 player 动作不超过 `stopOnTurn` / `stopAtStep`
2. 初始化 `DataCollectorServices`（`enablePrintGameLogs=true`，`enableSaveGameHistory=DebugUtil.TESTS_DATA_COLLECTORS_ENABLE_SAVE_GAME_HISTORY`，后者默认 `false`）
3. `currentGame.cheat(...)` 把通过 `addCard()` 准备的卡牌放到正确区域
4. 设置 `gameOptions.stopOnTurn` / `stopAtStep`
5. 调用 **`currentGame.start(activePlayer.getId())`**（Game 接口方法）
6. 设置 `currentGame.setGameStopped(true)`
7. 调用 `assertAllCommandsUsed()`

**控制终止：**

```java
setStopAt(int turn, PhaseStep step)  // 在第 turn 回合的 step 后停止
setStopOnTurn(int turn)              // 等价于 setStopAt(turn, PhaseStep.UNTAP)
```

默认 `stopOnTurn = 2`，`stopAtStep = PhaseStep.UNTAP`。
要让两个 AI 打完整局游戏（直到自然结束），设一个足够大的上限，如：

```java
setStopAt(100, PhaseStep.END_TURN);
execute();
Assert.assertTrue(currentGame.hasEnded());
```

游戏自然结束（某玩家生命归零/抽牌失败等）会在到达 stopOnTurn 之前终止。

### 取 game log

XMage 测试框架中**没有** `currentGame.getGameLog()` 这样的返回 `List<String>` 的 API。
日志通过 `DataCollectorServices` 事件机制分发：

1. **印到 Log4j（最简单）：** `execute()` 里 `DataCollectorServices.init(true, false)` 会激活
   `PrintGameLogsDataCollector`，它把每条 `informPlayers` 消息格式化后调用 `logger.info(...)`,
   输出到 Log4j 配置的 appender（通常是控制台 / test output）。消息格式：

   ```
   [LOG][GAME] T1.M1: <stripped-html-message>
   ```

2. **注册自定义 DataCollector（推荐用于捕获游戏日志到 List）：**

   ```java
   // DataCollector 接口
   void onGameLog(Game game, String message);
   ```

   实现步骤：
   - 实现 `DataCollector`（或继承 `EmptyDataCollector`）
   - 在 `onGameLog` 中把 message 追加到 `List<String>`
   - 在 `DataCollectorServices.getInstance().activeServices.add(yourCollector)` 注入
     （注意：`DataCollectorServices.init()` 只初始化一次，之后重复调用是 no-op；
      需要在 `init()` 之前注入，或直接操作 `activeServices`）

3. **`currentGame.getWinner()`** — 游戏结束后调用，返回 `String`，格式：
   - `"Player PlayerA is the winner"` 或 `"Player PlayerB is the winner"`
   - 生命归零但没有赢家时返回 `null`（draw）

4. **`currentGame.hasEnded()`** — `boolean`，游戏是否自然结束。

5. **`logger.debug("Winner: " + currentGame.getWinner())`** — `execute()` 本身在游戏结束后
   就打印了 Winner，见 `CardTestPlayerAPIImpl` 第 327 行。

---

## 现有参考测试

### 单 AI（PlayerA 是 AI，PlayerB 是默认 TestComputerPlayer）

文件：`Mage.Tests/src/test/java/org/mage/test/AI/basic/CastCreaturesTest.java`

模式：`addCard(Zone.HAND, playerA, "Silvercoat Lion")` → `setStopAt(1, PhaseStep.BEGIN_COMBAT)` →
`execute()` → `assertPermanentCount(playerA, "Silvercoat Lion", 1)`。

PlayerA 的 AI 全自动打，PlayerB 没有任何卡牌或动作（默认 TestComputerPlayer，pass priority）。

### AI vs AI（双方都是 ComputerPlayer7）

文件：`Mage.Tests/src/test/java/org/mage/test/AI/basic/SimulationPerformanceAITest.java`

关键：override `getFullSimulatedPlayers()` 返回 `Arrays.asList("PlayerA", "PlayerB")`，
然后双方都从 library 抽牌、施放法术、攻击，测试在有限回合内游戏是否结束。

---

## 自定义 deck 文件（.dck）加载

内部加载路径：`createPlayer(game, name, deckName)` →
`DeckImporter.importDeckFromFile(deckName, true)` → `Deck.load(...)` → `game.loadCards(...)`。

`deckName` 参数是文件路径字符串，可以是：
- 相对路径（相对于测试工作目录，即 `Mage.Tests/` 模块根目录，用法见 `CardTestPlayerBase`
  默认的 `"RB Aggro.dck"`，此文件在 `Mage.Tests/` 下）
- 绝对路径（更安全，适合自定义 deck 放在 `decks/` 目录时）

在 `CardTestPlayerBaseAI` 子类里加载自定义 .dck 文件：

```java
@Before
public void setUpDecks() {
    // setDecknamePlayerA 必须在 reset() 之后调用；但 reset() 是 @Before，
    // JUnit 不保证多个 @Before 的调用顺序，因此更安全的做法是在
    // createNewGameAndPlayers() 里直接传路径（见下文"注入 player"方案）
    setDecknamePlayerA("/absolute/path/to/decks/deck-a.dck");
    setDecknamePlayerB("/absolute/path/to/decks/deck-b.dck");
}
```

但 `CardTestPlayerBaseAI.createNewGameAndPlayers()` 调用的是
`createPlayer(game, "PlayerA")`（无 deckName），不会读取 `deckNameA`。
要让 deck 生效，需要在子类覆盖 `createNewGameAndPlayers()`，显式传入 deckName：

```java
@Override
protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
    Game game = new TwoPlayerDuel(
        MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
        MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7
    );
    playerA = createPlayer(game, "PlayerA", "/path/to/deck-a.dck");
    playerB = createPlayer(game, "PlayerB", "/path/to/deck-b.dck");
    return game;
}
```

---

## 关键问题：如何在 test 中用我们的 MTGeekTrivialPlayer

### 背景（来自 Task 4）

- `ComputerPlayer.priority()` 是 no-op（只 pass）。
- `ComputerPlayer7.priorityPlay()` 包含真正的 AI 逻辑（树搜索）。
- `TestComputerPlayer extends ComputerPlayer`（非 AI，用于手动脚本测试）。
- `TestComputerPlayer7 extends ComputerPlayer7`（AI，用于 CardTestPlayerBaseAI）。

### harness 对 inner-player 的要求

`TestPlayer(ComputerPlayer computerPlayer)` 构造器**不接受**任意 `ComputerPlayer` 子类，
它只接受三种具体类型：`TestComputerPlayer`、`TestComputerPlayer7`、`TestComputerPlayerMonteCarlo`
（三个重载构造器）。原因：每种类型都需要调用 `.setTestPlayerLink(this)`，
这是 `TestComputerPlayer*` 各自定义的方法，不在 `ComputerPlayer` 基类中。

因此 `MTGeekTrivialPlayer` 不能直接传给 `TestPlayer` 构造器。

### 三个选项

**选项 A：MTGeekTrivialPlayer extends TestComputerPlayer**

- `TestComputerPlayer extends ComputerPlayer`（pass priority AI）
- 继承后 override `priorityPlay()` 或 `priority()` 实现我们的逻辑
- 构造器签名：`TestComputerPlayer(String name, RangeOfInfluence range)`
- `TestPlayer` 接受 `TestComputerPlayer` 实例 → 可直接 `new TestPlayer(new MTGeekTrivialPlayer(name, range))`
- 缺点：依赖 test 模块的类，无法在生产代码中复用

**选项 B：MTGeekTrivialPlayer extends TestComputerPlayer7**

- `TestComputerPlayer7 extends ComputerPlayer7`（真 AI）
- 继承后 override 只覆盖我们想改的决策，其余由 ComputerPlayer7 处理
- 构造器签名：`TestComputerPlayer7(String name, RangeOfInfluence range, int skill)`
- `TestPlayer` 接受 `TestComputerPlayer7` 实例 → 可直接 `new TestPlayer(new MTGeekTrivialPlayer(name, range, skill))`
- 缺点：同 A，依赖 test 模块

**选项 C：adapter 类 wrapping MTGeekTrivialPlayer**

- 在 test 代码里写 `MTGeekTestPlayerAdapter extends TestComputerPlayer`
- adapter 内部持有 `MTGeekTrivialPlayer` 引用，把决策委托给它
- `MTGeekTrivialPlayer` 本身可以是纯生产代码（extends `ComputerPlayer` 或独立实现）

### 推荐：选项 A

**理由：** `TestComputerPlayer extends ComputerPlayer`，而我们的"trivial player"就是在
`ComputerPlayer` 基础上添加最简决策逻辑（参考 Task 4）；直接继承 `TestComputerPlayer`
可以让 `TestPlayer` 正确接受，且不需要额外 adapter 代码。
`TestComputerPlayer` 已有 `setTestPlayerLink` 和内部 dispatch 机制，只需 override 决策方法。

注入点：override `createPlayer(String name, RangeOfInfluence rangeOfInfluence)`：

```java
// 在 MTGeekDeckMatchTest 里
public class MTGeekDeckMatchTest extends CardTestPlayerBaseAI {

    @Override
    public List<String> getFullSimulatedPlayers() {
        // 不要让任何玩家走 CardTestPlayerBaseAI 的 TestComputerPlayer7 分支
        return Collections.emptyList();
    }

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        // 两个玩家都用我们的 trivial player
        return new TestPlayer(new MTGeekTrivialPlayer(name, rangeOfInfluence));
    }

    @Override
    protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
        Game game = new TwoPlayerDuel(
            MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
            MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7
        );
        playerA = createPlayer(game, "PlayerA", "/path/to/deck-a.dck");
        playerB = createPlayer(game, "PlayerB", "/path/to/deck-b.dck");
        return game;
    }
}
```

其中 `MTGeekTrivialPlayer extends TestComputerPlayer`：

```java
public class MTGeekTrivialPlayer extends TestComputerPlayer {
    public MTGeekTrivialPlayer(String name, RangeOfInfluence range) {
        super(name, range);
    }
    // override priority() / priorityPlay() / etc.
}
```

---

## 风险 / 不确定

1. **`setDecknamePlayerA` 与 `CardTestPlayerBaseAI` 的 deck 加载路径不通：**
   `CardTestPlayerBaseAI.createNewGameAndPlayers()` 调用 `createPlayer(game, name)`（无 deckName），
   不读取 `deckNameA`。必须 override `createNewGameAndPlayers()` 才能加载自定义 deck。

2. **`DataCollectorServices.init()` 只执行一次：** 它检查 `instance != null` 后直接 return。
   如果想在单个测试中捕获游戏日志到 `List<String>`，必须在第一次 `execute()` 前注入
   自定义 collector，或者使用 Log4j appender 拦截（不太干净）。

3. **`TestPlayer` 构造器只接受三种具体 inner-player 类型，不接受泛型 `ComputerPlayer`：**
   必须让 `MTGeekTrivialPlayer` 继承其中一种（选项 A 推荐 `TestComputerPlayer`）。

4. **`createPlayer(String name, RangeOfInfluence)` 与 `createPlayer(Game game, String name, String deckName)` 是两个不同方法：**
   前者创建 `TestPlayer` 对象，后者加载 deck + 调用前者。override 前者只影响 player 类，
   不影响 deck 加载；deck 加载在 `createNewGameAndPlayers()` 里控制。

5. **游戏不保证在有限回合内结束：** 如果 trivial player 只会 pass priority，
   游戏可能在 `stopOnTurn` 前无事发生（不会有赢家）。需要保证 player 至少能消耗 library
   或造成伤害，或用足够大的 `setStopAt(N, ...)` 并在结束后检查 `currentGame.hasEnded()`。
