package org.mage.test.mtgeek.simple;

import mage.cards.Card;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.Targets;

import java.util.UUID;

/**
 * B1' SimpleAI 的值函数模块。所有方法都是 pure functions：
 * 接 Game + 行动上下文 → double，不读写外部状态，结果确定性。
 * 决策权重统一从 Weights 常量表取。
 */
public final class ValueFunction {

    public static double scoreCastSpell(Game g, Card card, UUID controller, Targets t) {
        return 0.0; // implemented in Task 8
    }

    public static double scorePlayLand(Game g, Card land, UUID controller) {
        return 0.0; // implemented in Task 8
    }

    public static double scoreAttacker(Game g, Permanent attacker, UUID defender) {
        return 0.0; // implemented in Task 9
    }

    public static double scoreBlock(Game g, Permanent blocker, Permanent attacker) {
        return 0.0; // implemented in Task 9
    }

    public static double scoreTarget(Game g, UUID source, UUID candidateTarget, boolean isFriendly) {
        return 0.0; // implemented in Task 10
    }

    public static double scoreYesNo(Game g, UUID source, String hint) {
        return 0.0; // implemented in Task 10
    }

    public static double scorePass(Game g, UUID self) {
        Player p = g.getPlayer(self);
        if (p == null) return 0.0;
        int unspent = p.getManaPool().count();
        return unspent * Weights.UNSPENT_MANA_PENALTY;
    }

    private ValueFunction() {}
}
