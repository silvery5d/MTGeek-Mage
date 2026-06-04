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
}
