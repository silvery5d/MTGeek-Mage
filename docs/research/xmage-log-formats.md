# XMage Log 行格式（B3.1 研究）

> 调研日期：2026-06-04
> XMage commit：a5b618ecda2179d649339a5a0d5a86684c20dd0c（branch mtgeek-b1）
> Raw log 样本：Mage.Tests/target/raw-log-diagnosis-2026-06-04T02-34-17-484063Z.txt（200 行）

---

## 通用 HTML 装饰

XMage 的 informPlayers 输出含两类装饰：

1. **玩家名**：`<font color='#20B2AA'>PlayerA</font>`（固定颜色 #20B2AA）
2. **卡名**：`<font color='<COLOR>' object_id='<UUID>'>CardName</font> [<3位hex>]`
   - 土地类：颜色 `#B0C4DE`
   - 蓝色咒语：颜色 `#87CEFA`
   - 黑色咒语：颜色 `#696969`
   - 多色/金色：颜色 `#DAA520`
3. **hex suffix**：卡名 font 之后紧跟 ` [abc]`（3位16进制），属于 object_id 的短哈希

通用 strip 正则（已在样本中验证）：
```java
String clean = msg.replaceAll("<[^>]+>", "")          // strip HTML tags
                  .replaceAll("\\s*\\[[0-9a-f]{3,}\\]", "") // strip trailing [hex-suffix]
                  .trim();
```

strip 后举例：
- 原始：`<font color='#20B2AA'>PlayerA</font> puts <font color='#B0C4DE' object_id='5da26603-...'>Ancient Tomb</font> [5da] from hand onto the Battlefield`
- strip 后：`PlayerA puts Ancient Tomb from hand onto the Battlefield`

---

## play_land

**关键发现**：XMage 用 "puts ... from hand onto the Battlefield" 而非 "plays Mountain"。
现有 P_PLAYS_LAND 正则完全不匹配，这是 B3' 发现的 Bug 根因。

实际行（从 raw log 第 12 行复制）：
```
<font color='#20B2AA'>PlayerA</font> puts <font color='#B0C4DE' object_id='5da26603-accb-472f-99a6-811e80abedeb'>Ancient Tomb</font> [5da] from hand onto the Battlefield
```

另一例（第 71 行，带触发能力的土地）：
```
<font color='#20B2AA'>PlayerB</font> puts <font color='#B0C4DE' object_id='c7961b9d-f641-4b8b-a07a-c23cb882aa8b'>Undercity Sewers</font> [c79] from hand onto the Battlefield
```

另一例（第 65 行，Fetchland 搜到的土地，from library）：
```
<font color='#20B2AA'>PlayerA</font> puts <font color='#B0C4DE' object_id='b53ee91c-fb23-4f0f-b498-92cd3fe20218'>Volcanic Island</font> [b53] from library onto the Battlefield (source: <font color='#B0C4DE' object_id='a5e6d55c-590e-43f3-b1f4-101c1b6fe98c'>Polluted Delta</font> [a5e])
```

HTML strip 后：
```
PlayerA puts Ancient Tomb from hand onto the Battlefield
PlayerB puts Undercity Sewers from hand onto the Battlefield
PlayerA puts Volcanic Island from library onto the Battlefield (source: Polluted Delta)
```

推导正则（只捕获主动出牌，不含 from library 的搜索放置）：
```java
private static final Pattern P_PLAYS_LAND = Pattern.compile(
    "^(PlayerA|PlayerB) puts (.+?) from hand onto the Battlefield$"
);
```

注意事项：
- `from library onto the Battlefield` 是 fetchland/cheating 效应，不是玩家主动出牌，应单独处理或不计入 play_land
- 咒语解析（cast_spell）也会触发 `puts ... from stack onto the Battlefield`（如 Lotus Petal，第 154 行），strip 后为 `PlayerA puts Lotus Petal from stack onto the Battlefield`——区分关键在 `from hand` vs `from stack` vs `from library`
- 样本中 play_land 出现：第 12、17、59、71、94、105、118、130、161 行，共 9 次/局

边界情况：
- `puts ... from stack onto the Battlefield`：永久物咒语结算（非 play_land，已有单独逻辑）
- `puts ... from library onto the Battlefield`：搜索效应，非主动出牌
- `puts ... from graveyard onto the Battlefield`：重动效应

---

## life_change

**关键发现**：XMage 用 "loses N life from <source>"，而非 "loses N life"（无 source）。
现有 P_LIFE 正则不匹配（缺少 `from <source>` 后缀）。同样有 "at combat from" 变体。

实际行（第 61 行，fetchland 出牌付费）：
```
<font color='#20B2AA'>PlayerA</font> loses 1 life from <font color='#B0C4DE' object_id='a5e6d55c-590e-43f3-b1f4-101c1b6fe98c'>Polluted Delta</font> [a5e]
```

实际行（第 81 行，Thoughtseize 付费）：
```
<font color='#20B2AA'>PlayerB</font> loses 2 life from <font color='#696969' object_id='c9cfee50-1264-4ee9-aea4-facda026255a'>Thoughtseize</font> [c9c]
```

实际行（第 91 行，战斗伤害）：
```
<font color='#20B2AA'>PlayerA</font> loses 2 life at combat from <font color='#696969' object_id='08b6fcfe-ac5a-484a-83f4-2fd7f6f6eb99'>Nethergoyf</font> [08b]
```

实际行（第 179 行，Ancient Tomb 代价）：
```
<font color='#20B2AA'>PlayerA</font> loses 2 life from <font color='#B0C4DE' object_id='5da26603-accb-472f-99a6-811e80abedeb'>Ancient Tomb</font> [5da]
```

HTML strip 后：
```
PlayerA loses 1 life from Polluted Delta
PlayerB loses 2 life from Thoughtseize
PlayerA loses 2 life at combat from Nethergoyf
PlayerA loses 2 life from Ancient Tomb
```

推导正则（涵盖两种 phrasing）：
```java
private static final Pattern P_LIFE = Pattern.compile(
    "^(PlayerA|PlayerB) (loses|gains) (\\d+) life(?: at combat)? from (.+)$"
);
```

group mapping：
- group(1) = player ("PlayerA"/"PlayerB")
- group(2) = "loses"/"gains"
- group(3) = amount
- group(4) = source card name（strip 后的纯文本，可忽略或放入 payload）

边界情况 / 已出现写法：
- `loses N life from <card>`（土地、咒语代价）
- `loses N life at combat from <card>`（战斗伤害）
- 样本中**未出现** `gains N life`，但从对称性看 XMage 应该有此格式
- 样本中**未出现**无 source 的 `loses N life`

样本数：第 61、81、91、115、120、132、144、150、169、179、197、198 行，共 12 次/局

---

## draw（抽牌）

**关键发现**：XMage 用 "puts a card from library into their hand"（不含具体卡名），不含玩家名直接能看，需要从 actor 段识别。

实际行（第 100 行，Flow State 抽牌）：
```
<font color='#20B2AA'>PlayerB</font> puts a card from library into their hand
```

实际行（第 181 行，Stock Up 抽牌）：
```
<font color='#20B2AA'>PlayerA</font> puts a card from library into their hand
```

HTML strip 后：
```
PlayerB puts a card from library into their hand
PlayerA puts a card from library into their hand
```

推导正则：
```java
private static final Pattern P_DRAW = Pattern.compile(
    "^(PlayerA|PlayerB) puts a card from library into their hand$"
);
```

注意事项：
- 此格式对于所有抽牌效果都相同（普通抽牌、cantrip 效果、大量抽牌均触发多次此行）
- 同一行不含卡名（XMage 对其他玩家隐藏抽到的牌）
- Ponder 的 "puts a card from library to the top of their library" 是不同行，不是 draw

样本数：第 100、101、181、182 行，共 4 次/局（对应 Flow State 2 张 + Stock Up 2 张）

---

## turn_start

**关键发现**：样本（200 行，游戏结束于约第 9 回合）中**完全没有**出现 "Turn N" 格式的行。

XMage 的回合计数由 GameImpl 内部管理，并不在 informPlayers 里广播一条独立的 "Turn N" log 行。
现有 P_TURN 正则：`^Turn (\\d+).*` 在此样本中零命中。

可能原因：
1. XMage 仅在某些 UI 通知路径（非 informPlayers）里发 Turn 信息
2. 或者只在特定条件（多人游戏？客户端模式？）下才广播
3. 本次测试模式（headless AI vs AI）没有触发 turn 广播

结论：`P_TURN` 在实际游戏 log 中无效，`currentTurn` 永远为 0。
T7 任务应改为从 GameImpl 的 `getTurnNum()` 轮询，或在 `onGameLog` 以外的回调（如 `onTurnChange` 如果有的话）捕获。

---

## NICE events

### attack

实际行（第 89 行）：
```
<font color='#20B2AA'>PlayerB</font> attacks <font color='#20B2AA'>PlayerA</font> with 1 creature
```

实际行（第 113 行）：
```
<font color='#20B2AA'>PlayerB</font> attacks <font color='#20B2AA'>PlayerA</font> with 1 creature
```

实际行（第 194 行，多个攻击者）：
```
<font color='#20B2AA'>PlayerB</font> attacks <font color='#20B2AA'>PlayerA</font> with 2 creatures
```

攻击者明细行（第 90、114、195、196 行）：
```
Attacker: <font color='#696969' object_id='08b6fcfe-ac5a-484a-83f4-2fd7f6f6eb99'>Nethergoyf</font> [08b] (2/3) unblocked
Attacker: <font color='#87CEFA' object_id='2bd5ae0d-6470-47f3-ac4d-3ad864438f21'>Brazen Borrower</font> [2bd] (3/1) unblocked
```

HTML strip 后：
```
PlayerB attacks PlayerA with 1 creature
PlayerB attacks PlayerA with 2 creatures
Attacker: Nethergoyf (2/3) unblocked
Attacker: Brazen Borrower (3/1) unblocked
```

推导正则：
```java
// 攻击宣言
private static final Pattern P_ATTACK = Pattern.compile(
    "^(PlayerA|PlayerB) attacks (PlayerA|PlayerB) with (\\d+) creatures?$"
);
// 攻击者明细（可选捕获）
private static final Pattern P_ATTACKER = Pattern.compile(
    "^Attacker: (.+?) \\((\\d+)/(\\d+)\\)(?: unblocked)?$"
);
```

样本数：attack 宣言 4 次（第 89、113、148、167、194 行实际 5 行，其中 194 是多生物攻击），每轮均为 PlayerB 攻击 PlayerA（PlayerA 未攻击）。

### block

样本中**未出现**。PlayerA 未派出任何生物，所有攻击均 unblocked。
B3.1 T8 中 block 事件的正则需另行采样，或参考 XMage 源码。

推测格式（参考 XMage GameImpl 源码，待验证）：
```
PlayerA blocks [CardName] with [BlockerName]
```
或
```
Blocker: <CardName> (<P>/<T>)
```

### damage（战斗外伤害）

样本中战斗伤害全部通过 life_change 行体现（`loses N life at combat from <card>`），
没有单独的 "deals N damage" 行。
结论：不需要单独 damage 事件类型；life_change 正则已涵盖。

### game_end

实际行（第 199 行）：
```
<font color='#20B2AA'>PlayerA</font> has lost the game.
```

实际行（第 200 行）：
```
<font color='#20B2AA'>PlayerB</font> has won the game
```

HTML strip 后：
```
PlayerA has lost the game.
PlayerB has won the game
```

注意：`has lost the game.` 末尾有句点，`has won the game` 末尾无句点（XMage 格式不一致）。

推导正则：
```java
private static final Pattern P_GAME_END = Pattern.compile(
    "^(PlayerA|PlayerB) has (lost|won) the game\\.?$"
);
```

样本数：每局 1 对（lost + won）。

---

## 现有正则 vs 实际格式 对照表

| 事件 | 现有正则（会匹配的格式） | 实际 XMage 格式 | 状态 |
|------|------------------------|----------------|------|
| decision | `^[Simple\|hook] picked: ...` | 与实际一致 | OK |
| play_land | `^PlayerA plays (.+)$` | `PlayerA puts X from hand onto the Battlefield` | **不匹配** |
| cast_spell | `^PlayerA casts (.+?)...` | `PlayerA casts X [uuid] from hand` | 部分匹配（strip 后 uuid 被移除，但 "from hand" 残留） |
| life_change | `^PlayerA (loses\|gains) N life$` | `PlayerA loses N life from <source>` | **不匹配** |
| turn_start | `^Turn N.*` | 样本中不存在此格式行 | **无效** |
| draw | 无 | `PlayerA puts a card from library into their hand` | **缺失** |
| attack | 无 | `PlayerA attacks PlayerB with N creatures` | **缺失** |
| game_end | 无 | `PlayerA has lost/won the game.?` | **缺失** |

cast_spell 实际格式（strip 后）：
```
PlayerB casts Ponder from hand
PlayerB casts Nethergoyf from hand
PlayerB casts Thoughtseize from hand targeting PlayerA
```
现有正则 `^(PlayerA|PlayerB) casts (.+?)(?:\\s+targeting .+)?$` 匹配 `PlayerB casts Ponder from hand`——此时 group(2) = "Ponder from hand"，而非仅卡名。需要在正则末尾加 ` from .*` 限定。

---

## 风险 / 不确定

1. **turn_start 缺失**：本样本完全没有 Turn N 行。T7 任务需放弃基于 onGameLog 的 turn 追踪，改为其他机制。
2. **block 缺失**：本样本无 block（双方均无互相攻击阻挡场面），T8 的 block 正则需通过含 block 的测试场景另行采样。
3. **cast_spell from hand 后缀**：现有正则捕获到 "Ponder from hand"（group(2) 含 "from hand"），需要在正则中明确切断。
4. **gains life 缺失**：样本中无 gains life 行；正则参考对称性推测，实际格式需含 source（`gains N life from <card>`）。
5. **XMage 版本锁定**：本笔记对应 commit a5b618ec；XMage upstream 升级可能改变 informPlayers 措辞，届时需重新采样。
6. **卡名特殊字符**：如 `Atraxa, Grand Unifier`（含逗号）、`Emrakul, the Aeons Torn`——strip 后正则需用 `.+` 而非 `\\w+`。
7. **多行攻击宣言**：`attacks ... with N creatures` 后跟 N 条 `Attacker:` 明细行，解析时需关联。
