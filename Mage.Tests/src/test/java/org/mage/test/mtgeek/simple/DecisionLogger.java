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
