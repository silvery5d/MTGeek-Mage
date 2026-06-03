package org.mage.test.mtgeek.simple;

import mage.abilities.Ability;
import mage.abilities.effects.Effect;
import mage.abilities.effects.common.BrainstormEffect;
import mage.abilities.effects.common.CounterTargetEffect;
import mage.abilities.effects.common.CounterUnlessPaysEffect;
import mage.abilities.effects.common.CreateTokenEffect;
import mage.abilities.effects.common.DamageAllEffect;
import mage.abilities.effects.common.DamagePlayersEffect;
import mage.abilities.effects.common.DamageTargetEffect;
import mage.abilities.effects.common.DestroyAllEffect;
import mage.abilities.effects.common.DestroyTargetEffect;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.abilities.effects.common.ExileTargetEffect;
import mage.abilities.effects.common.GainLifeEffect;
import mage.abilities.effects.common.PutCardFromHandOntoBattlefieldEffect;
import mage.abilities.effects.common.PutCardFromHandOrGraveyardOntoBattlefieldEffect;
import mage.abilities.effects.common.PutCardIntoPlayWithHasteAndSacrificeEffect;
import mage.abilities.effects.common.PutCreatureAndOrLandFromHandOntoBattlefieldEffect;
import mage.abilities.effects.common.LoseLifeTargetEffect;
import mage.abilities.effects.common.MillCardsTargetEffect;
import mage.abilities.effects.common.ReturnToHandTargetEffect;
import mage.abilities.effects.common.SacrificeOpponentsEffect;
import mage.abilities.effects.common.SacrificeTargetEffect;
import mage.abilities.effects.common.discard.DiscardTargetEffect;
import mage.abilities.effects.common.search.SearchLibraryPutInHandEffect;
import mage.abilities.effects.common.search.SearchLibraryPutInPlayEffect;
import mage.abilities.effects.common.turn.AddExtraTurnControllerEffect;
import mage.cards.Card;
import mage.constants.Outcome;
import mage.counters.CounterType;
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
        if (card == null) return 0.0;
        double score = 0.0;
        for (Ability ability : card.getAbilities()) {
            for (Effect e : ability.getEffects()) {
                score += scoreEffect(e, ability, g);
            }
        }
        // Curve bonus: prefer higher-cost playable spells when scores otherwise tie
        score += card.getManaValue() * Weights.CURVE_PLAY_HIGHEST_FIRST;
        return score;
    }

    private static double scoreEffect(Effect e, Ability source, Game g) {
        // --- Damage ---
        if (e instanceof DamageTargetEffect) {
            int amount = resolveAmount(e, source, g);
            return amount * 5.0;
        }
        if (e instanceof DamageAllEffect) {
            // Rough proxy: damage to all creatures ~= amount * 3 (assume hits ~3 creatures)
            return resolveAmount(e, source, g) * 3.0;
        }
        if (e instanceof DamagePlayersEffect) {
            return resolveAmount(e, source, g) * Weights.FACE_DAMAGE_PER_POINT;
        }

        // --- Destroy ---
        if (e instanceof DestroyAllEffect) {
            return Weights.REMOVE_CREATURE_BASE * 3.0; // board wipe proxy
        }
        if (e instanceof DestroyTargetEffect) {
            return Weights.REMOVE_CREATURE_BASE;
        }

        // --- Counter (check conditional first, then unconditional) ---
        if (e instanceof CounterUnlessPaysEffect) {
            return Weights.COUNTERSPELL_OPPORTUNISM * 0.7; // conditional counter discount
        }
        if (e instanceof CounterTargetEffect) {
            return Weights.COUNTERSPELL_OPPORTUNISM;
        }

        // --- Draw (specific classes first) ---
        if (e instanceof BrainstormEffect) {
            // Net draw value: toDraw cards minus toPutOnTop (cards returned to library)
            // Access via reflection; default Brainstorm = 3 draw - 2 put back = net 1
            int netDraw = resolveBrainstormNet((BrainstormEffect) e);
            return netDraw * Weights.OWN_HAND_PER_CARD;
        }
        if (e instanceof DrawCardSourceControllerEffect) {
            int amount = resolveAmount(e, source, g);
            return amount * Weights.OWN_HAND_PER_CARD;
        }
        // Fallback for any draw effect not matched above (check Outcome)
        if (e.getOutcome() == Outcome.DrawCard) {
            return Weights.OWN_HAND_PER_CARD; // conservative: assume 1 card net value
        }

        // --- Discard ---
        if (e instanceof DiscardTargetEffect) {
            return resolveAmount(e, source, g) * Weights.OPP_HAND_DISCARD_PER_CARD;
        }

        // --- Exile ---
        if (e instanceof ExileTargetEffect) {
            return Weights.REMOVE_CREATURE_BASE + 1.0; // exile > destroy
        }

        // --- Layer A.1: Put-from-hand onto battlefield (Show and Tell 类、Sneak Attack 类) ---
        // 基础奖励 PUT_FROM_HAND_PAYOFF + 控制者手牌中最佳威胁度的 50% 加成
        if (e instanceof PutCardFromHandOntoBattlefieldEffect
                || e instanceof PutCreatureAndOrLandFromHandOntoBattlefieldEffect
                || e instanceof PutCardFromHandOrGraveyardOntoBattlefieldEffect
                || e instanceof PutCardIntoPlayWithHasteAndSacrificeEffect
                || e.getOutcome() == Outcome.PutCardInPlay) {
            double base = Weights.PUT_FROM_HAND_PAYOFF;
            UUID controllerId = source.getControllerId();
            Player controller = g.getPlayer(controllerId);
            if (controller != null) {
                double bestThreat = 0;
                for (Card hc : controller.getHand().getCards(g)) {
                    double t = scoreHandCardAsThreat(hc, g);
                    if (t > bestThreat) bestThreat = t;
                }
                base += bestThreat * 0.5;
            }
            return base;
        }

        // --- Sacrifice (check subclass before parent) ---
        if (e instanceof SacrificeOpponentsEffect) {
            return Weights.REMOVE_CREATURE_BASE * 0.7; // edict: not guaranteed to hit best threat
        }
        if (e instanceof SacrificeTargetEffect) {
            return Weights.REMOVE_CREATURE_BASE * 0.9; // target sac slightly weaker than destroy
        }

        // --- Return to hand ---
        if (e instanceof ReturnToHandTargetEffect) {
            return Weights.REMOVE_CREATURE_BASE * 0.6; // tempo bounce, opponent can replay
        }

        // --- Mill ---
        if (e instanceof MillCardsTargetEffect) {
            return 1.0; // modest positive
        }

        // --- Extra turn ---
        if (e instanceof AddExtraTurnControllerEffect) {
            return Weights.EXTRA_TURN_BASE;
        }

        // --- Token creation ---
        if (e instanceof CreateTokenEffect) {
            return 2.0;
        }

        // --- Search / Tutor (check put-in-play before put-in-hand) ---
        if (e instanceof SearchLibraryPutInPlayEffect) {
            return Weights.TUTOR_VALUE + 2.0; // put in play is stronger
        }
        if (e instanceof SearchLibraryPutInHandEffect) {
            return Weights.TUTOR_VALUE;
        }

        // --- Life gain/loss ---
        if (e instanceof GainLifeEffect) {
            return resolveAmount(e, source, g) * 0.3; // life gain is weak
        }
        if (e instanceof LoseLifeTargetEffect) {
            return resolveAmount(e, source, g) * Weights.FACE_DAMAGE_PER_POINT * 0.5;
        }

        return 0.0; // unknown effect — conservative skip (same as Tians strategy)
    }

    /**
     * Resolve Brainstorm net draw value.
     * Default BrainstormEffect: draw 3, put back 2 → net = 1.
     * Uses reflection to read toDraw and toPutOnTop fields.
     */
    private static int resolveBrainstormNet(BrainstormEffect effect) {
        try {
            java.lang.reflect.Field toDrawField = BrainstormEffect.class.getDeclaredField("toDraw");
            toDrawField.setAccessible(true);
            java.lang.reflect.Field toPutOnTopField = BrainstormEffect.class.getDeclaredField("toPutOnTop");
            toPutOnTopField.setAccessible(true);
            int toDraw = (int) toDrawField.get(effect);
            int toPutOnTop = (int) toPutOnTopField.get(effect);
            return Math.max(1, toDraw - toPutOnTop);
        } catch (Exception ignored) {
            return 1; // default Brainstorm net = 3 - 2 = 1
        }
    }

    /**
     * Generic amount resolver.
     *
     * Strategy:
     *   1. Walk the class hierarchy looking for a DynamicValue field named "amount"
     *      (covers DamageTargetEffect, DamageAllEffect, DamagePlayersEffect,
     *       DrawCardSourceControllerEffect, LoseLifeTargetEffect, DiscardTargetEffect, …).
     *   2. Fall back to field "life" (GainLifeEffect).
     *   3. Conservative default of 1 for anything unrecognised.
     *
     * The old approach (reflection for getAmount(Ability, Game)) always threw
     * NoSuchMethodException because those effects store the value as a DynamicValue
     * field, not a method with that signature.
     */
    private static int resolveAmount(Effect e, Ability source, Game g) {
        // 试 1：DynamicValue 字段 "amount"（多数效果用这个名称）
        Object v = readDynamicField(e, "amount");
        if (v != null) {
            if (v instanceof mage.abilities.dynamicvalue.DynamicValue) return ((mage.abilities.dynamicvalue.DynamicValue) v).calculate(g, source, e);
            if (v instanceof Integer) return (Integer) v;
            if (v instanceof Number) return ((Number) v).intValue();
        }
        // 试 2：DynamicValue 字段 "life"（GainLifeEffect 用这个名称）
        Object lv = readDynamicField(e, "life");
        if (lv != null) {
            if (lv instanceof mage.abilities.dynamicvalue.DynamicValue) return ((mage.abilities.dynamicvalue.DynamicValue) lv).calculate(g, source, e);
            if (lv instanceof Integer) return (Integer) lv;
            if (lv instanceof Number) return ((Number) lv).intValue();
        }
        return 1; // 真没 amount，保守默认
    }

    /**
     * Walk the class hierarchy (including superclasses) and return the value of
     * a field by name, or null if not found.
     */
    private static Object readDynamicField(Object obj, String fieldName) {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignore) {
                cls = cls.getSuperclass();
            } catch (Exception other) {
                return null;
            }
        }
        return null;
    }

    public static double scorePlayLand(Game g, Card land, UUID controller) {
        // Playing a land unlocks 1 mana for future spells.
        // Value = potential mana gain, calibrated against UNSPENT_MANA_PENALTY (magnitude).
        return Math.abs(Weights.UNSPENT_MANA_PENALTY) * 0.5;
    }

    public static double scoreAttacker(Game g, Permanent attacker, UUID defender) {
        if (attacker == null) return 0.0;
        int power = attacker.getPower().getValue();
        // 攻击对手脸
        Player defenderPlayer = g.getPlayer(defender);
        if (defenderPlayer != null) {
            return power * Weights.FACE_DAMAGE_PER_POINT;
        }
        // 攻击 planeswalker（defender 是 PW UUID）
        Permanent pw = g.getPermanent(defender);
        if (pw != null && pw.isPlaneswalker(g)) {
            int loyalty = pw.getCounters(g).getCount(CounterType.LOYALTY);
            double score = 0;
            if (power >= loyalty) score += Weights.REMOVE_PLANESWALKER_BASE;
            score += Math.min(power, loyalty) * Weights.REMOVE_PLANESWALKER_LOYALTY;
            return score;
        }
        return 0.0;
    }

    public static double scoreBlock(Game g, Permanent blocker, Permanent attacker) {
        if (blocker == null || attacker == null) return 0.0;
        int myPower = blocker.getPower().getValue();
        int myTough = blocker.getToughness().getValue();
        int oppPower = attacker.getPower().getValue();
        int oppTough = attacker.getToughness().getValue();

        boolean blockerDies = oppPower >= myTough;
        boolean attackerDies = myPower >= oppTough;

        double score = 0.0;
        if (attackerDies) score += Weights.REMOVE_CREATURE_BASE + oppPower * Weights.REMOVE_CREATURE_POWER;
        if (blockerDies)  score += Weights.SELF_KILL_BASE + myPower * Weights.SELF_KILL_POWER;
        // chump block (只挡不死敌)：blocker 死 attacker 不死，但避免脸 → +oppPower * FACE_DAMAGE * 0.3
        if (!attackerDies && blockerDies) score += oppPower * Weights.FACE_DAMAGE_PER_POINT * 0.3;
        return score;
    }

    public static double scoreTarget(Game g, UUID source, UUID candidateTarget, boolean isFriendly) {
        if (candidateTarget == null) return 0.0;
        Permanent p = g.getPermanent(candidateTarget);
        if (p == null) {
            // 目标可能是 player UUID（直伤打脸时）
            Player pl = g.getPlayer(candidateTarget);
            if (pl != null && !isFriendly) return Weights.FACE_DAMAGE_PER_POINT;
            return 0.0;
        }
        if (isFriendly) {
            // buff 自己人：偏好 power 高的
            return p.getPower().getValue() * Weights.OWN_BOARD_POWER_PER * 0.3;
        }
        // 打对手永久物
        if (p.isPlaneswalker(g)) {
            return Weights.REMOVE_PLANESWALKER_BASE
                 + p.getCounters(g).getCount(CounterType.LOYALTY) * Weights.REMOVE_PLANESWALKER_LOYALTY;
        }
        if (p.isCreature(g)) {
            return Weights.REMOVE_CREATURE_BASE + p.getPower().getValue() * Weights.REMOVE_CREATURE_POWER;
        }
        return 1.0; // 其它类型（神器/结界）小幅正分
    }

    public static double scoreYesNo(Game g, UUID source, String hint) {
        if (hint == null) return 0.0;
        String h = hint.toLowerCase();
        // 已知模式：付生命 → 看当前血量决定
        if (h.contains("pay") && h.contains("life")) {
            if (source == null) return 0.0;
            Player p = g.getPlayer(source);
            if (p != null && p.getLife() > 10) return 1.0;
            return -1.0;
        }
        if (h.contains("draw")) return Weights.OWN_HAND_PER_CARD;
        if (h.contains("discard")) return Weights.OWN_HAND_PER_CARD * -0.5;
        return 0.0; // 未知 hint 保守
    }

    public static double scorePass(Game g, UUID self) {
        Player p = g.getPlayer(self);
        if (p == null) return 0.0;
        int unspent = p.getManaPool().count();
        return unspent * Weights.UNSPENT_MANA_PENALTY;
    }

    /** Layer C: 评估一张手牌"如果上场"的威胁度。Layer B chooseFromHand 与
     *  Layer A.1 PutFromHand effect handler 共用。 */
    public static double scoreHandCardAsThreat(Card c, Game g) {
        if (c == null) return 0;
        double s = 0;
        if (c.isCreature(g)) {
            try {
                s += c.getPower().getValue() * 2 + c.getToughness().getValue();
            } catch (Exception ignored) {}
        }
        if (c.isPlaneswalker(g)) s += 10;
        s += c.getManaValue() * 0.5;

        String oracle;
        try {
            oracle = String.join(" ", c.getRules(g)).toLowerCase();
        } catch (Exception e) {
            oracle = "";
        }
        if (oracle.contains("annihilator")) s += 20;
        if (oracle.contains("extra turn") || oracle.contains("extra turns")) s += 15;
        if (oracle.contains("trample")) s += 2;
        if (oracle.contains("flying")) s += 1;
        if (oracle.contains("hexproof") || oracle.contains("shroud")) s += 3;
        if (oracle.contains("indestructible")) s += 5;
        return s;
    }

    /**
     * Public surface so MTGeekSimplePlayer.chooseMode can score per-mode effects.
     */
    public static double scoreEffectPublic(Effect e, Ability source, Game g) {
        return scoreEffect(e, source, g);
    }

    private ValueFunction() {}
}
