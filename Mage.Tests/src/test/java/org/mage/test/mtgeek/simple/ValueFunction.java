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
     * Generic amount resolver: tries getAmount(Ability, Game) via reflection,
     * then falls back to 1 for dynamic/X spells.
     */
    private static int resolveAmount(Effect e, Ability source, Game g) {
        try {
            java.lang.reflect.Method m = e.getClass().getMethod("getAmount",
                    Ability.class, Game.class);
            Object r = m.invoke(e, source, g);
            if (r instanceof Integer) return (Integer) r;
            if (r instanceof Number) return ((Number) r).intValue();
        } catch (Exception ignored) {}
        return 1; // conservative fallback for dynamic/X values
    }

    public static double scorePlayLand(Game g, Card land, UUID controller) {
        // Playing a land unlocks 1 mana for future spells.
        // Value = potential mana gain, calibrated against UNSPENT_MANA_PENALTY (magnitude).
        return Math.abs(Weights.UNSPENT_MANA_PENALTY) * 0.5;
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
