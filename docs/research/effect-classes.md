# XMage Effect 子类研究笔记（B1' ValueFunction 用）

> 调研日期：2026-06-02，XMage commit：add772fc5cbe6e921b8fa58b000d7f58cffc56d2

## Effect 根类层级

```
mage.abilities.effects.Effect  (interface)
  └─ mage.abilities.effects.EffectImpl  (abstract class)
       ├─ mage.abilities.effects.OneShotEffect  (abstract class)
       │    ├─ mage.abilities.effects.SearchEffect  (abstract class)
       │    │    ├─ SearchLibraryPutInHandEffect
       │    │    └─ SearchLibraryPutInPlayEffect
       │    ├─ SacrificeAllEffect
       │    │    └─ SacrificeOpponentsEffect
       │    └─ (所有其它 common 子类，见下方字典)
       └─ mage.abilities.effects.ContinuousEffect  (interface)
            └─ (boost/grant-keyword/continuous 类，不在 B1' 字典中)
```

`Effect` 接口关键方法：
- `boolean apply(Game game, Ability source)`
- `Outcome getOutcome()`
- `TargetPointer getTargetPointer()`
- `String getText(Mode mode)`

`OneShotEffect(Outcome outcome)` 构造器唯一，所有 OneShotEffect 子类都通过 `super(Outcome.XXX)` 调用。

Effect 子类总数（`find Mage/src/main/java/mage/abilities/effects -name '*.java'`）：**802**

---

## B1' instanceof 字典（按 Show-and-Tell + Dimir 常见 effect 倒推排序）

### 1. DamageTargetEffect
- **包路径**：`mage.abilities.effects.common.DamageTargetEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public DamageTargetEffect(int amount)`
  - `public DamageTargetEffect(int amount, String whoDealDamageName)`
  - `public DamageTargetEffect(DynamicValue amount)`
  - `public DamageTargetEffect(DynamicValue amount, String whoDealDamageName)`
- **关键 getter / fluent**：
  - `amount` 字段为 `DynamicValue`（通过 `amount.calculate(game, source, this)` 在 `apply()` 里求值）
  - `public DamageTargetEffect withCantBePrevented()` — 返回自身，设 `preventable = false`
- **用途**：对 target pointer 指向的所有目标（永久物 or 玩家）造成伤害
- **ValueFunction 分配建议**：
  - 目标是玩家：`amount * W_FACE_DAMAGE`（= 10.0/点）
  - 目标是生物：`amount * W_REMOVE_CREATURE`（= 5.0/点，协调 toughness）

---

### 2. DamageAllEffect
- **包路径**：`mage.abilities.effects.common.DamageAllEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public DamageAllEffect(int amount, FilterPermanent filter)`
  - `public DamageAllEffect(int amount, String whoDealDamageName, FilterPermanent filter)`
  - `public DamageAllEffect(DynamicValue amount, FilterPermanent filter)`
- **关键字段**：
  - `DynamicValue amount`
  - `FilterPermanent filter` — 通过 `filter.getMessage()` 获取描述
- **用途**：对战场上所有符合 filter 的永久物造成伤害（板扫型）
- **ValueFunction 分配建议**：`amount * (敌方匹配数 - 我方匹配数) * W_REMOVE_CREATURE`

---

### 3. DamagePlayersEffect
- **包路径**：`mage.abilities.effects.common.DamagePlayersEffect`
- **父类**：`OneShotEffect`
- **构造器签名**（主要重载）：
  - `public DamagePlayersEffect(int amount)`  → 伤害所有玩家
  - `public DamagePlayersEffect(int amount, TargetController controller)`  → 控制伤害对象范围
  - `public DamagePlayersEffect(int amount, TargetController controller, String whoDealDamageName)`
  - `public DamagePlayersEffect(Outcome outcome, DynamicValue amount, TargetController controller, String whoDealDamageName)`
- **关键枚举**：`TargetController` — `ANY`（所有玩家）/ `OPPONENT`（仅对手）
- **用途**：对一批玩家（所有或仅对手）造成伤害
- **ValueFunction 分配建议**：`controller == OPPONENT` → `amount * opponentCount * W_FACE_DAMAGE`

---

### 4. DestroyTargetEffect
- **包路径**：`mage.abilities.effects.common.DestroyTargetEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public DestroyTargetEffect()`  → `Outcome.DestroyPermanent`，可再生
  - `public DestroyTargetEffect(String ruleText)`
  - `public DestroyTargetEffect(boolean noRegen)`
  - `public DestroyTargetEffect(String ruleText, boolean noRegen)`
- **关键字段**：`protected boolean noRegen`
- **用途**：摧毁目标永久物（生物、神器、结界等均可）
- **ValueFunction 分配建议**：`W_REMOVE_CREATURE`（= 30.0），noRegen 略加权 +5.0

---

### 5. DestroyAllEffect
- **包路径**：`mage.abilities.effects.common.DestroyAllEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public DestroyAllEffect(FilterPermanent filter)`
  - `public DestroyAllEffect(FilterPermanent filter, boolean noRegen)`
- **关键字段**：`FilterPermanent filter`、`boolean noRegen`
- **用途**：摧毁所有符合 filter 的永久物（Wrath of God 型）
- **ValueFunction 分配建议**：`(敌方命中数 - 我方命中数) * W_REMOVE_CREATURE`

---

### 6. CounterTargetEffect
- **包路径**：`mage.abilities.effects.common.CounterTargetEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public CounterTargetEffect()`  → `Outcome.Detriment`，无参数
- **用途**：反击目标法术/异能（将其从堆叠移除）
- **ValueFunction 分配建议**：`W_COUNTER_SPELL`（= 25.0；保护板面估值）

---

### 7. CounterUnlessPaysEffect
- **包路径**：`mage.abilities.effects.common.CounterUnlessPaysEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public CounterUnlessPaysEffect(Cost cost)`
  - `public CounterUnlessPaysEffect(Cost cost, boolean exile)` — exile 为真时放逐而非进墓
  - `public CounterUnlessPaysEffect(DynamicValue genericMana)`
  - `public CounterUnlessPaysEffect(DynamicValue genericMana, boolean exile)`
- **fluent**：`public CounterUnlessPaysEffect withIfTheyDo(Effect effect)` — 附加"如果他们付费则…"效果
- **关键字段**：`protected Cost cost`、`protected DynamicValue genericMana`、`boolean exile`
- **用途**：条件反击（除非对手付费），Mana Leak / Force Spike 型
- **ValueFunction 分配建议**：`W_COUNTER_SPELL * 0.7`（有偿反击折扣）

---

### 8. DrawCardSourceControllerEffect
- **包路径**：`mage.abilities.effects.common.DrawCardSourceControllerEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public DrawCardSourceControllerEffect(int amount)`
  - `public DrawCardSourceControllerEffect(int amount, boolean youDraw)` — `youDraw=true` 时文本为"you draw X"
  - `public DrawCardSourceControllerEffect(DynamicValue amount)`
  - `public DrawCardSourceControllerEffect(DynamicValue amount, boolean youDraw)`
- **关键字段**：`protected DynamicValue amount`
- **用途**：来源控制者摸牌
- **ValueFunction 分配建议**：`amount * W_CARD_VALUE`（= 8.0/张）

---

### 9. DrawCardAllEffect
- **包路径**：`mage.abilities.effects.common.DrawCardAllEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public DrawCardAllEffect(int amount)` → `TargetController.ANY`（所有玩家）
  - `public DrawCardAllEffect(DynamicValue amount)`
  - `public DrawCardAllEffect(int amount, TargetController targetController)`
  - `public DrawCardAllEffect(DynamicValue amount, TargetController targetController)`
- **关键枚举**：`TargetController` — `ANY` / `OPPONENT`
- **用途**：所有玩家（或仅对手）摸牌
- **ValueFunction 分配建议**：`controller == OPPONENT` → 负分 `-amount * opponentCount * W_CARD_VALUE`

---

### 10. DiscardTargetEffect
- **包路径**：`mage.abilities.effects.common.discard.DiscardTargetEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public DiscardTargetEffect(int amount)`
  - `public DiscardTargetEffect(int amount, boolean randomDiscard)`
  - `public DiscardTargetEffect(DynamicValue amount)`
  - `public DiscardTargetEffect(DynamicValue amount, boolean randomDiscard)`
- **关键字段**：`protected DynamicValue amount`、`protected boolean randomDiscard`
- **用途**：令 target 玩家弃牌（手牌破坏）
- **ValueFunction 分配建议**：`amount * W_DISCARD`（= 6.0/张；弃牌弱于摸牌）

---

### 11. DiscardEachPlayerEffect
- **包路径**：`mage.abilities.effects.common.discard.DiscardEachPlayerEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public DiscardEachPlayerEffect()` → 每人弃 1 张，`TargetController.ANY`
  - `public DiscardEachPlayerEffect(TargetController targetController)`
  - `public DiscardEachPlayerEffect(int amount, boolean randomDiscard)`
  - `public DiscardEachPlayerEffect(DynamicValue amount, boolean randomDiscard)`
  - `public DiscardEachPlayerEffect(DynamicValue amount, boolean randomDiscard, TargetController targetController)`
- **关键字段**：`protected DynamicValue amount`、`protected boolean randomDiscard`、`TargetController targetController`
- **用途**：令多位（或所有/仅对手）玩家弃牌（Hymn to Tourach 型）
- **ValueFunction 分配建议**：`controller == OPPONENT → amount * opponentCount * W_DISCARD`

---

### 12. MillCardsTargetEffect
- **包路径**：`mage.abilities.effects.common.MillCardsTargetEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public MillCardsTargetEffect(int numberCards)`
  - `public MillCardsTargetEffect(DynamicValue numberCards)`
- **关键字段**：`DynamicValue numberCards`（mill 数量）
- **用途**：令 target 玩家从牌库顶磨牌
- **ValueFunction 分配建议**：`numberCards * W_MILL_PER_CARD`（= 1.5/张；Dimir 场景略高）

---

### 13. MillCardsControllerEffect
- **包路径**：`mage.abilities.effects.common.MillCardsControllerEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public MillCardsControllerEffect(int numberCards)`
  - `public MillCardsControllerEffect(DynamicValue numberCards)`
- **关键字段**：`DynamicValue numberCards`
- **用途**：控制者自己磨牌（自磨 = 负分或填墓策略加分）
- **ValueFunction 分配建议**：默认 `-numberCards * W_MILL_PER_CARD`（自磨对非自磨策略是代价）

---

### 14. GainLifeEffect
- **包路径**：`mage.abilities.effects.common.GainLifeEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public GainLifeEffect(int life)` → `Outcome.GainLife`
  - `public GainLifeEffect(DynamicValue life)`
  - `public GainLifeEffect(DynamicValue life, String rule)` — 自定义规则文本
- **关键字段**：`DynamicValue life`（gainLife 的量，由控制者获得）
- **用途**：控制者回血
- **ValueFunction 分配建议**：`life * W_LIFE_PER_POINT`（= 2.0/点；生命不如牌值钱）

---

### 15. LoseLifeTargetEffect
- **包路径**：`mage.abilities.effects.common.LoseLifeTargetEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public LoseLifeTargetEffect(int amount)` → `Outcome.Damage`
  - `public LoseLifeTargetEffect(DynamicValue amount)`
- **关键字段**：`protected DynamicValue amount`
- **用途**：令 target 玩家失去生命（不是伤害，无法被防止）
- **ValueFunction 分配建议**：`amount * W_FACE_DAMAGE`（= 10.0/点，同直伤）

---

### 16. LoseLifeOpponentsEffect
- **包路径**：`mage.abilities.effects.common.LoseLifeOpponentsEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public LoseLifeOpponentsEffect(int amount)` → `Outcome.Damage`
  - `public LoseLifeOpponentsEffect(DynamicValue amount)`
- **关键字段**：`DynamicValue amount`（对所有 `game.getOpponents()` 生效）
- **用途**：所有对手失去生命
- **ValueFunction 分配建议**：`amount * opponentCount * W_FACE_DAMAGE`

---

### 17. ExileTargetEffect
- **包路径**：`mage.abilities.effects.common.ExileTargetEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public ExileTargetEffect()` → `Outcome.Exile`，放逐到公共放逐区
  - `public ExileTargetEffect(String effectText)`
  - `public ExileTargetEffect(UUID exileId, String exileZone)` — 放逐到特定具名区域
  - `public ExileTargetEffect(UUID exileId, String exileZone, Zone onlyFromZone)` — 限制来源区域
- **fluent**：`public ExileTargetEffect setToSourceExileZone(boolean toSourceExileZone)` — 自动绑定来源 exile zone
- **关键字段**：`UUID exileId`、`String exileZone`、`Zone onlyFromZone`
- **用途**：放逐目标（可以是永久物、手牌、墓地牌等）
- **ValueFunction 分配建议**：`W_REMOVE_CREATURE + 5.0`（放逐比摧毁更优，阻止墓地利用）

---

### 18. SacrificeTargetEffect
- **包路径**：`mage.abilities.effects.common.SacrificeTargetEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public SacrificeTargetEffect()` → `Outcome.Sacrifice`
  - `public SacrificeTargetEffect(String text)`
  - `public SacrificeTargetEffect(String text, UUID playerIdThatHasToSacrifice)` — 只限特定玩家牺牲
- **关键字段**：`protected UUID playerIdThatHasToSacrifice`（null = 任意控制者牺牲）
- **用途**：令目标永久物的控制者牺牲它
- **ValueFunction 分配建议**：`W_REMOVE_CREATURE * 0.9`（目标者选择牺牲对象，略弱于 Destroy）

---

### 19. SacrificeOpponentsEffect
- **包路径**：`mage.abilities.effects.common.SacrificeOpponentsEffect`
- **父类**：`SacrificeAllEffect extends OneShotEffect`
- **构造器签名**：
  - `public SacrificeOpponentsEffect(FilterPermanent filter)` → 每个对手牺牲 1 个
  - `public SacrificeOpponentsEffect(int amount, FilterPermanent filter)` → 每个对手牺牲 N 个
  - `public SacrificeOpponentsEffect(DynamicValue amount, FilterPermanent filter)`
- **关键字段**（继承自 `SacrificeAllEffect`）：`DynamicValue amount`、`FilterPermanent filter`、`boolean onlyOpponents = true`
- **用途**：令每个对手牺牲永久物（Dictate of Erebos 型）
- **ValueFunction 分配建议**：`amount * opponentCount * W_REMOVE_CREATURE`

---

### 20. ReturnToHandTargetEffect
- **包路径**：`mage.abilities.effects.common.ReturnToHandTargetEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public ReturnToHandTargetEffect()` → `Outcome.ReturnToHand`，无参数
- **用途**：将目标（永久物或法术）退回手牌（弹回，tempo 效果）
- **ValueFunction 分配建议**：`W_BOUNCE`（= 15.0；弹回保留在游戏中，不如永久移除）

---

### 21. AddExtraTurnControllerEffect
- **包路径**：`mage.abilities.effects.common.turn.AddExtraTurnControllerEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public AddExtraTurnControllerEffect()` → `Outcome.ExtraTurn`，不失去游戏
  - `public AddExtraTurnControllerEffect(boolean loseGameAtEnd)`
  - `public AddExtraTurnControllerEffect(boolean loseGameAtEnd, TurnModApplier turnModApplier)`
- **关键字段**：`boolean loseGameAtEnd`（true 时 outcome 改为 `Outcome.AIDontUseIt`）
- **用途**：控制者获得额外回合（Time Walk 型）
- **ValueFunction 分配建议**：`W_EXTRA_TURN`（= 50.0；极高价值，loseGameAtEnd 时 = 0）

---

### 22. CreateTokenEffect
- **包路径**：`mage.abilities.effects.common.CreateTokenEffect`
- **父类**：`OneShotEffect`
- **构造器签名**：
  - `public CreateTokenEffect(Token token)` → 创建 1 个
  - `public CreateTokenEffect(Token token, int amount)`
  - `public CreateTokenEffect(Token token, DynamicValue amount)`
  - `public CreateTokenEffect(Token token, int amount, boolean tapped)`
  - `public CreateTokenEffect(Token token, int amount, boolean tapped, boolean attacking)`
  - `public CreateTokenEffect(Token token, DynamicValue amount, boolean tapped, boolean attacking)`
- **fluent**：
  - `public CreateTokenEffect entersWithCounters(CounterType counterType, DynamicValue numberOfCounters)`
  - `public CreateTokenEffect withAdditionalTokens(Token... tokens)`
  - `public CreateTokenEffect withAdditionalRules(String additionalRules)`
- **关键字段**：`List<Token> tokens`（支持多种 token 类型）、`DynamicValue amount`、`boolean tapped`、`boolean attacking`
- **用途**：创建 token 永久物（生物 token 为主）
- **ValueFunction 分配建议**：`amount * token.getPower() * W_TOKEN_PER_POWER`（= 4.0/power 点）

---

### 23. SearchLibraryPutInHandEffect
- **包路径**：`mage.abilities.effects.common.search.SearchLibraryPutInHandEffect`
- **父类**：`SearchEffect extends OneShotEffect`
- **构造器签名**：
  - `public SearchLibraryPutInHandEffect(TargetCardInLibrary target, boolean reveal)`
  - `public SearchLibraryPutInHandEffect(TargetCardInLibrary target, boolean reveal, boolean textThatCard)`
- **关键字段**：`TargetCardInLibrary target`（继承自 `SearchEffect`）、`boolean reveal`
- **用途**：搜库取牌到手（Demonic Tutor 手牌版）
- **ValueFunction 分配建议**：`target.getMaxNumberOfTargets() * W_TUTOR_CARD`（= 12.0/张；手牌搜索 > 普通摸牌）

---

### 24. SearchLibraryPutInPlayEffect
- **包路径**：`mage.abilities.effects.common.search.SearchLibraryPutInPlayEffect`
- **父类**：`SearchEffect extends OneShotEffect`
- **构造器签名**：
  - `public SearchLibraryPutInPlayEffect(TargetCardInLibrary target)`
  - `public SearchLibraryPutInPlayEffect(TargetCardInLibrary target, boolean tapped)`
  - `public SearchLibraryPutInPlayEffect(TargetCardInLibrary target, boolean tapped, boolean textThatCard)`
  - `public SearchLibraryPutInPlayEffect(TargetCardInLibrary target, boolean tapped, boolean textThatCard, boolean optional)`
- **关键字段**：`TargetCardInLibrary target`、`boolean tapped`、`boolean optional`
- **用途**：搜库直接放战场（地搜 / 生物搜）
- **ValueFunction 分配建议**：`W_TUTOR_PLAY`（= 20.0；直接入场比入手更高效）

---

## XMage Effect 子类总数（估计）

`find Mage/src/main/java/mage/abilities/effects -name '*.java' | wc -l` → **802**

common 子目录下的 Damage 类变种较多（约 20+），Exile 类变种约 15+，Mill/Draw/Discard/Sacrifice 各约 5-10。

---

## 不在 B1' instanceof 列表中的 effect（保守跳过）

以下 effect 在用户两副牌（Show-and-Tell + Dimir）中可能出现，但 B1' 阶段不计分（`return 0.0`，保守同 Tian 的 skip-unknown 策略）：

| 类名 | 说明 |
|---|---|
| `GainAbilityTargetEffect` | 给目标授予异能（ContinuousEffect） |
| `BoostTargetEffect` | +N/+N 强化目标 |
| `TapTargetEffect` | 横置目标 |
| `UntapTargetEffect` | 解横置 |
| `PreventDamageToTargetEffect` | 防止伤害 |
| `CopySpellForEachItCouldTargetEffect` | 复制法术 |
| `ScryEffect` / `LookLibraryTopCardTargetEffect` | 占卜 / 看顶 |
| `PutOnLibraryTargetEffect` | 将牌放到牌库顶/底 |
| `TransformSourceEffect` | 转化（双面牌） |

---

## 风险 / 不确定项

1. **`DynamicValue amount` 在运行时才求值**：所有 Damage/Draw/Discard/Mill 系列的 `amount` 都是 `DynamicValue` 接口，无法在 `scoreCastSpell` 时拿到真实数值。B1' 建议用 `StaticValue.get(amount).calculate(game, source, effect)` 或对 `DynamicValue` 使用 `amount.toString()` 解析静态数字，动态值（X spell）统一用默认 fallback。
2. **`SacrificeOpponentsEffect` 继承链**：其 `apply()` 在 `SacrificeAllEffect` 中实现，`instanceof SacrificeOpponentsEffect` 会 true 同时 `instanceof SacrificeAllEffect` 也为 true。建议优先检查 `SacrificeOpponentsEffect`（更具体）。
3. **`SearchEffect` 抽象类**：`SearchLibraryPutInHandEffect` 和 `SearchLibraryPutInPlayEffect` 都继承自 `SearchEffect`，可用 `instanceof SearchEffect` 做一级过滤。
4. **路径变化**：Discard 系列在子包 `discard/` 下（`mage.abilities.effects.common.discard`），AddExtraTurn 在子包 `turn/` 下，Search 系列在子包 `search/` 下——均已在本笔记中记录正确包路径。
5. **802 个文件中绝大多数不在此字典**：B1' 仅覆盖约 24 类，遇到未知 instanceof 一律 `return 0.0` 以保守估值，防止错误激励。
