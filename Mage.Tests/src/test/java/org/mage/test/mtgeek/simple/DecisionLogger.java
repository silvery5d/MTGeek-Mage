package org.mage.test.mtgeek.simple;

/**
 * SimpleAI 决策日志。每个钩子结束时调一次 log()，写一行进 GameLog 系统
 * （走 GameImpl.informPlayers → DataCollectorServices，与 XMage 原生 game log 交织）。
 * 格式严格固定，便于人工审 log + 正则提取做后处理。
 */
public final class DecisionLogger {
    private DecisionLogger() {}

    /** 单一最佳行动 + runner-up 对照。candCount 是该钩子枚举到的候选总数。 */
    public static void log(mage.game.Game game, String playerName,
                           String hook, String pickedDesc, double pickedScore,
                           String runnerUpDesc, double runnerUpScore, int candCount) {
        String line = (runnerUpDesc == null)
            ? String.format("[Simple|%s:%s] picked: %s (score=%.2f); n=%d",
                playerName, hook, pickedDesc, pickedScore, candCount)
            : String.format("[Simple|%s:%s] picked: %s (score=%.2f); runner-up: %s (score=%.2f); n=%d",
                playerName, hook, pickedDesc, pickedScore, runnerUpDesc, runnerUpScore, candCount);
        game.informPlayers(line);
    }

    /** 单一决定无 runner-up（例如只有 1 候选）。 */
    public static void logOnly(mage.game.Game game, String playerName,
                               String hook, String pickedDesc, double pickedScore) {
        game.informPlayers(String.format(
            "[Simple|%s:%s] picked: %s (score=%.2f); n=1",
            playerName, hook, pickedDesc, pickedScore));
    }

    /** LLM 决策：picked + rationale（中英文均支持）。 */
    public static void logLLM(mage.game.Game game, String hook, String playerName, String picked, String rationale) {
        String safe = rationale.replace('\n', ' ').replace('\r', ' ');
        game.informPlayers(String.format("[LLM|%s:%s] picked: %s; rationale: %s",
            playerName, hook, picked, safe));
    }

    /** LLM fallback：在连续失败后记录失败原因。 */
    public static void logLLMFallback(mage.game.Game game, String hook, String playerName, String reason) {
        String safe = reason.replace('\n', ' ').replace('\r', ' ');
        game.informPlayers(String.format("[LLM-FALLBACK|%s:%s] reason: %s", playerName, hook, safe));
    }

    /** 库顶视野快照：Ponder / Brainstorm / Augur of Bolas 等"看牌库顶 N 张"
     *  效果触发时记录玩家看到的具体卡名。XMage 不会主动写入这些私有信息——
     *  我们从 player 自身的 chooseTarget 钩子拦截。
     *  格式：[LIBVIEW|PlayerA|Ponder] Lightning Bolt|Mountain|Force of Will */
    public static void logLibraryView(mage.game.Game game, String playerName,
                                      String source, java.util.List<String> cardNames) {
        if (cardNames == null || cardNames.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cardNames.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(cardNames.get(i).replace('|', '/'));
        }
        game.informPlayers(String.format("[LIBVIEW|%s|%s] %s", playerName, source, sb));
    }

    /** 牌库快照（观战回放用，顶在前）：[LIB|PlayerA] card1|card2|…
     *  对局中这是隐藏信息，但回放是赛后产物，观众可见牌库顺序
     *  （用于评估 Ponder/Brainstorm 等决策质量）。 */
    public static void logLibrary(mage.game.Game game, String playerName,
                                  java.util.List<String> cardNames) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cardNames.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(cardNames.get(i).replace('|', '/'));
        }
        game.informPlayers(String.format("[LIB|%s] %s", playerName,
            cardNames.isEmpty() ? "(empty)" : sb));
    }

    /** 可用法术力快照：未横置永久物能产出的法术力（启发式，dual land 双计）。
     *  格式：[MANA|PlayerA] W0 U2 B0 R1 G0 C2 */
    public static void logMana(mage.game.Game game, String playerName,
                               int w, int u, int b, int r, int g, int c) {
        game.informPlayers(String.format("[MANA|%s] W%d U%d B%d R%d G%d C%d",
            playerName, w, u, b, r, g, c));
    }

    /** 手牌快照：在 priority 决策前后记录，便于回放时显示该时刻手牌内容。
     *  格式：[HAND|PlayerA] Lightning Bolt|Mountain|Show and Tell
     *  空手牌：[HAND|PlayerA] (empty) */
    public static void logHand(mage.game.Game game, String playerName, java.util.List<String> cardNames) {
        if (cardNames == null || cardNames.isEmpty()) {
            game.informPlayers(String.format("[HAND|%s] (empty)", playerName));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cardNames.size(); i++) {
            if (i > 0) sb.append('|');
            // Escape pipe characters in card names just in case (unlikely but safe).
            sb.append(cardNames.get(i).replace('|', '/'));
        }
        game.informPlayers(String.format("[HAND|%s] %s", playerName, sb));
    }
}
