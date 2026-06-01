# XMage .dck 文件格式

> 调研日期：2026-06-01，XMage 提交：22dc75f2b6f640ccaf21dfee96e2d29dd858c675
> 解析器源码：`Mage/src/main/java/mage/cards/decks/importer/DckDeckImporter.java`
> 样本文件参考：
> - `Mage.Tests/UW Control.dck`
> - `Mage.Tests/Power Hungry.dck`
> - `Mage.Tests/Companion_ZirdaValid.dck`
> - `Mage.Tests/CommanderDuel.dck`

---

## 行格式（主牌区）

解析器使用的正则表达式（摘自源码第 24 行）：

```
(SB:)?\s*(\d*)\s*\[([^]:]+):([^]:]+)\]\s*(.*)\s*$
```

每张牌一行，格式：

```
<数量> [<SET>:<编号>] <卡名>
```

例（直接摘自 `UW Control.dck`）：

```
4 [WWK:31] Jace, the Mind Sculptor
3 [ROE:21] Gideon Jura
2 [CON:15] Path to Exile
4 [M10:226] Glacial Fortress
1 [ZEN:220] Misty Rainforest
```

字段说明：
- `<数量>`：一个或多个十进制数字（`\d*`，所以理论上可省略，但实际样本均有值）
- `[SET:编号]`：用方括号包裹，冒号分隔；SET 为 XMage 内部集合代码，编号为卡片在该集合中的序号
- `<卡名>`：方括号后跟一个空格，然后是卡名；卡名可含空格、撇号、斜杠等

数量与方括号之间只需空格分隔，多个空格也被接受（解析器会 trim 整行后用正则匹配）。

---

## 文件头（可选元数据行）

```
NAME:<牌组名称>
AUTHOR:<作者名>
```

例（摘自 `UW Control.dck` 第 1 行）：

```
NAME:UW Control
```

`NAME:` 与 `AUTHOR:` 行可出现在文件任意位置（解析器逐行处理），但惯例放在第一行。两者均可省略。

---

## 副牌（Sideboard）

前缀 `SB: ` 加上与主牌相同的行格式：

```
SB: <数量> [<SET>:<编号>] <卡名>
```

例（摘自 `CommanderDuel.dck` 第 73 行）：

```
SB: 1 [C14:27] Ob Nixilis of the Black Oath
```

例（摘自 `Companion_ZirdaValid.dck` 第 20 行）：

```
SB: 1 [IKO:233] Zirda, the Dawnwaker
```

例（摘自 `Power Hungry.dck` 第 84 行）：

```
SB: 1 [C13:204] Prossh, Skyraider of Kher
```

解析器通过正则捕获组 1（`(SB:)?`）判断是否副牌，匹配到 `"SB:"` 时把卡加入 `deckList.getSideboard()`，否则加入 `deckList.getCards()`。

注意：在 Commander 格式牌组中，指挥官通常放在 `SB:` 行（即副牌区），这是 XMage 的惯例。

---

## 注释

以 `#` 开头的行被跳过（源码第 39 行）：

```java
if (line.isEmpty() || line.startsWith("#")) {
    return;
}
```

不支持 `//` 注释（`//` 在牌名中用于拆分牌，见下文）。

---

## 空行

允许空行；解析器遇到 `line.isEmpty()` 直接 `return`，不报错（源码第 39 行）。样本 `Companion_ZirdaValid.dck` 第 2 行即为空行。

---

## 集合代码（set code）

XMage 使用自己的集合代码，与 Scryfall 三字母代码基本一致，但有时有差异。常见示例：

| 代码 | 集合 |
|------|------|
| USG | Urza's Saga |
| LRW | Lorwyn |
| EMA | Eternal Masters |
| WWK | Worldwake |
| ZEN | Zendikar |
| ROE | Rise of the Eldrazi |
| M10 | Magic 2010 |
| IKO | Ikoria: Lair of Behemoths |
| 2XM | Double Masters |
| CLU | Commander Legends: Battle for Baldur's Gate |
| C14 | Commander 2014 |
| C13 | Commander 2013 |
| SOM | Scars of Mirrodin |

编号是该版本中的印刷序号（整数字符串，如 `31`、`226`）。

### CardRepository 查找逻辑

解析器首先调用：

```java
CardRepository.instance.findCard(setCode, cardNum, true)
```

按 SET + 编号精确查找。如果找不到（或找到的卡名与文件中的卡名不符），再回退到：

```java
CardRepository.instance.findPreferredCoreExpansionCard(cardName, setCode)
```

按卡名查找。这意味着 SET 代码写错不会直接报错，只是会降级为按名查找；但如果 SET 与编号组合对应的卡名和文件中的卡名不同，则视为"过时"并尝试修复。

---

## 卡名特殊符号

### 拆分牌 / 双面牌

拆分牌使用 ` // ` 分隔两个子名（两边各一空格）。例（摘自 `Power Hungry.dck` 第 52 行）：

```
1 [C13:118] Rough // Tumble
```

### "Æ" 等 Unicode 字符

`.dck` 文件中直接写 ASCII 等价形式，不写原始 Unicode。`CardNameUtil.normalizeCardName()` 在解析时做替换，所以即使文件里写了某些 Unicode 字符也会被转换。主要映射（摘自源码）：

| Unicode | .dck 中写法 |
|---------|-------------|
| `Æ`（Ã†） | `Ae` |
| `ö` | `o` |
| `û` | `u` |
| `í`, `ï`, `î` | `i` |
| `â`, `á`, `à` | `a` |
| `é` | `e` |
| `ú` | `u` |
| `ó`, `ō` | `o` |
| `ä` | `a` |
| `ü` | `u` |
| `É` | `E` |
| `ñ` | `n` |

例：`Aether Vial`（非 `Æther Vial`），`Lim-Dul's Vault`（非 Unicode 变体）。

### 撇号 / 单引号

直接写标准 ASCII 单引号（`'`），例：

```
1 [DST:82] Mishra's Bauble
```

`CardNameUtil.CARD_NAME_PATTERN` 明确包含 `'` 在合法字符集中。

---

## LAYOUT 行（可选，编辑器专用）

格式：

```
LAYOUT MAIN:(rows,cols)<settings>|<stack-data>
LAYOUT SIDEBOARD:(rows,cols)<settings>|<stack-data>
```

这是 XMage 牌组编辑器保存的视觉布局信息，对导入逻辑没有功能影响。测试时生成的 `.dck` 文件无需包含此行。

---

## 我们的两副牌

两副牌均为 60 张主牌、无副牌，所以 `.dck` 文件结构极简：

```
NAME:<牌组名>
<60 行卡牌，每行格式：数量 [SET:编号] 卡名>
```

不需要 SB: 行，不需要 LAYOUT 行，不需要 AUTHOR: 行。

---

## 与 DeckImporter 的关系

### 主入口

```java
DeckCardLists result = DeckImporter.importDeckFromFile(path, errorMessages, saveAutoFixedFile);
```

- `path` 接受绝对路径字符串（`new File(fileName)` 直接构造）
- 当 `path` 以 `.dck` 结尾时自动选择 `DckDeckImporter`
- `saveAutoFixedFile = false` 时不会覆写原文件

### 测试环境中的路径

`PlainTextDeckImporter.importDeck()` 内部用 `new File(fileName)` 打开文件（第 32 行），这意味着：

- 相对路径以 JVM 工作目录为基准（通常是项目根目录 `MTGeek-Mage/`）
- 推荐在测试中使用绝对路径，或利用 `getClass().getResource()` 转换为绝对路径

推荐测试写法：

```java
String deckPath = getClass().getClassLoader()
    .getResource("mtgeek/show-and-tell.dck")
    .getPath();
DeckCardLists deck = DeckImporter.importDeckFromFile(deckPath, false);
```

对应资源文件放置于：`Mage.Tests/src/test/resources/mtgeek/show-and-tell.dck`（或相应模块的 `src/test/resources/`）。

---

## 风险 / 不确定

1. **SET 代码确认**：Task 8 需要为每张具体卡片查找正确的 XMage SET 代码和编号。推荐从 `CardRepository` 数据库（SQLite 文件，位于 `~/.xmage/`）或运行 `CardRepository.instance.findPreferredCoreExpansionCard(cardName)` 获取，而非手工猜测。如果 SET+编号填错，解析器会回退到按名查找并记录警告，但不会报 fatal error——牌还是能加入牌组。

2. **XMage SET 代码与 Scryfall 代码差异**：部分集合代码不同。例如 XMage 用 `2XM` 而 Scryfall 也用 `2XM`；但某些老版本集合可能存在差异。Task 8 实现时需交叉验证。

3. **数量上限**：解析器调用 `DeckCardInfo.makeSureCardAmountFine(count, cardName)` 检查数量合法性，但 60 张主牌的标准构造都在合理范围内，不是风险点。

4. **文件编码**：`PlainTextDeckImporter` 用 `new Scanner(f)` 不指定编码，默认为平台编码（JVM 默认，通常 UTF-8）。只要 `.dck` 文件保存为 UTF-8，卡名用 ASCII 写法，就不会有编码问题。
