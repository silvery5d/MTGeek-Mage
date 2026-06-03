package org.mage.test.mtgeek.simple;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * TDD tests for ValueFunction — B1' SimpleAI Tasks 7 + 8.
 *
 * Task 7: scorePass (mana-penalty)
 * Task 8: scorePlayLand + scoreCastSpell + effect handler dict
 *
 * Mana-injection strategy: "Omnath, Locus of Mana" keeps green mana from
 * emptying at end of step, so we can observe unspent mana after execute().
 */
public class ValueFunctionTest extends CardTestPlayerBase {

    /**
     * No lands, no mana in pool: pass should score 0.
     */
    @Test
    public void scorePass_noUnspentMana_returnsZero() {
        // Default start: playerA has no lands, mana pool is empty
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();

        double score = ValueFunction.scorePass(currentGame, playerA.getId());
        assertEquals("Empty mana pool should score 0.0", 0.0, score, 1e-6);
    }

    /**
     * 3 Forests tapped with Omnath on battlefield → 3 green mana persist in pool
     * (Omnath keeps green mana from emptying until end of turn).
     * scorePass should return 3 * UNSPENT_MANA_PENALTY = 3 * -1.5 = -4.5.
     *
     * Omnath, Locus of Mana: "Green mana doesn't empty from your mana pool as
     * steps and phases end."
     */
    @Test
    public void scorePass_threeManaUnspent_isNegativeMultipleOfPenalty() {
        // Omnath keeps green mana in pool; 3 Forests provide {G}{G}{G}
        addCard(Zone.BATTLEFIELD, playerA, "Omnath, Locus of Mana", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);

        // Tap all 3 Forests during PRECOMBAT_MAIN — injects 3 {G} that will NOT empty
        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {G}", 3);

        // Stop in the same phase before mana is spent
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();

        double score = ValueFunction.scorePass(currentGame, playerA.getId());
        assertTrue("3 unspent mana should yield negative score, got: " + score, score < 0);
        // 3 * -1.5 = -4.5
        assertEquals("3 unspent green mana (held by Omnath): 3 * -1.5 = -4.5", -4.5, score, 1e-6);
    }

    // -------------------------------------------------------------------------
    // Task 8 tests: scorePlayLand + scoreCastSpell
    // -------------------------------------------------------------------------

    @Test
    public void scorePlayLand_anyLand_returnsModestPositive() {
        addCard(Zone.HAND, playerA, "Island", 1);
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
        mage.cards.Card island = playerA.getHand().getCards(currentGame).iterator().next();
        double score = ValueFunction.scorePlayLand(currentGame, island, playerA.getId());
        assertTrue("playLand 应得正分，实测=" + score, score > 0);
    }

    @Test
    public void scoreCastSpell_damageSpell_returnsPositive() {
        // Lightning Bolt = "deal 3 damage to any target" — score should be positive (damage dispatch)
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
        mage.cards.Card bolt = playerA.getHand().getCards(currentGame).iterator().next();
        double score = ValueFunction.scoreCastSpell(currentGame, bolt, playerA.getId(), null);
        // amount=3 → 3*5 = 15, + cmc bonus (1 * CURVE_PLAY_HIGHEST_FIRST).
        // If resolveAmount still returns 1 (bug), score = 1*5 + cmc ≈ 5.5 — fails this assertion.
        assertTrue("Lightning Bolt 3-damage 应得 ~15+ 分（amount=3 × 5 + cmc bonus），实测=" + score, score >= 14.0);
    }

    @Test
    public void scoreCastSpell_drawSpell_returnsPositive() {
        // Brainstorm = "draw 3 cards then put 2 back" — score should be positive (draw dispatch)
        addCard(Zone.HAND, playerA, "Brainstorm", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 1);
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
        mage.cards.Card brainstorm = playerA.getHand().getCards(currentGame).iterator().next();
        double score = ValueFunction.scoreCastSpell(currentGame, brainstorm, playerA.getId(), null);
        assertTrue("draw spell 应得正分，实测=" + score, score > 0);
    }

    @Test
    public void scoreCastSpell_counterspell_returnsCounterspellOpportunism() {
        // Counterspell = "counter target spell" — score should reflect COUNTERSPELL_OPPORTUNISM
        addCard(Zone.HAND, playerA, "Counterspell", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
        mage.cards.Card cs = playerA.getHand().getCards(currentGame).iterator().next();
        double score = ValueFunction.scoreCastSpell(currentGame, cs, playerA.getId(), null);
        assertTrue("Counterspell 应得正分，实测=" + score, score >= Weights.COUNTERSPELL_OPPORTUNISM);
    }

    // -------------------------------------------------------------------------
    // Task 9 tests: scoreAttacker + scoreBlock
    // -------------------------------------------------------------------------

    @Test
    public void scoreAttacker_attackOpponentFace_scalesWithPower() {
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 1);  // 2/2
        setStopAt(2, PhaseStep.DECLARE_ATTACKERS);
        execute();
        mage.game.permanent.Permanent bear = currentGame.getBattlefield()
                .getAllActivePermanents(playerA.getId()).iterator().next();
        double score = ValueFunction.scoreAttacker(currentGame, bear, playerB.getId());
        assertTrue("攻击对手脸应得高正分，实测=" + score, score >= 10.0);
    }

    @Test
    public void scoreBlock_blockKillsAttacker_returnsRemoveBase() {
        // 我 3/3 阻挡对手 2/1：对手生物被消灭（attackerDies），我不死（!blockerDies）
        addCard(Zone.BATTLEFIELD, playerA, "Centaur Courser", 1);  // 3/3
        addCard(Zone.BATTLEFIELD, playerB, "Goblin Piker", 1);     // 2/1
        setStopAt(2, PhaseStep.DECLARE_BLOCKERS);
        execute();
        mage.game.permanent.Permanent blocker = currentGame.getBattlefield()
                .getAllActivePermanents(playerA.getId()).iterator().next();
        mage.game.permanent.Permanent attacker = currentGame.getBattlefield()
                .getAllActivePermanents(playerB.getId()).iterator().next();
        double score = ValueFunction.scoreBlock(currentGame, blocker, attacker);
        assertTrue("成功 trade up（消灭更小的）应得正分，实测=" + score, score > 0);
    }

    @Test
    public void scoreBlock_blockingMutualKill_isMixed() {
        // 双方 2/2 互杀：attackerDies+blockerDies 都 true
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 1);  // 2/2
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears", 1);  // 2/2
        setStopAt(2, PhaseStep.DECLARE_BLOCKERS);
        execute();
        mage.game.permanent.Permanent blocker = currentGame.getBattlefield()
                .getAllActivePermanents(playerA.getId()).iterator().next();
        mage.game.permanent.Permanent attacker = currentGame.getBattlefield()
                .getAllActivePermanents(playerB.getId()).iterator().next();
        double score = ValueFunction.scoreBlock(currentGame, blocker, attacker);
        // 2/2 互杀：score = (5 + 2*1) + (-3 + 2*-1) = 7 - 5 = 2
        assertTrue("2/2 互杀应得正分（trade up 主导），实测=" + score, score > 0);
    }

    // -------------------------------------------------------------------------
    // Task 10 tests: scoreTarget + scoreYesNo
    // -------------------------------------------------------------------------

    @Test
    public void scoreTarget_largeOppCreature_betterThanSmall() {
        addCard(Zone.BATTLEFIELD, playerB, "Centaur Courser", 1);  // 3/3
        addCard(Zone.BATTLEFIELD, playerB, "Goblin Piker", 1);     // 2/1
        setStopAt(1, PhaseStep.END_TURN);
        execute();
        java.util.Iterator<mage.game.permanent.Permanent> it =
            currentGame.getBattlefield().getAllActivePermanents(playerB.getId()).iterator();
        mage.game.permanent.Permanent first = it.next();
        mage.game.permanent.Permanent second = it.next();
        double s1 = ValueFunction.scoreTarget(currentGame, null, first.getId(), false);
        double s2 = ValueFunction.scoreTarget(currentGame, null, second.getId(), false);
        // 让 power 高的得分更高（不假设 iterator 顺序）
        boolean firstStronger = first.getPower().getValue() > second.getPower().getValue();
        if (firstStronger) {
            assertTrue("大生物 ("+first.getName()+") 分应更高: s1=" + s1 + " s2=" + s2, s1 > s2);
        } else {
            assertTrue("大生物 ("+second.getName()+") 分应更高: s1=" + s1 + " s2=" + s2, s2 > s1);
        }
    }

    @Test
    public void scoreTarget_friendlyVsHostile_differentScale() {
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 1);
        setStopAt(1, PhaseStep.END_TURN);
        execute();
        mage.game.permanent.Permanent myBear = currentGame.getBattlefield()
            .getAllActivePermanents(playerA.getId()).iterator().next();
        double friendly = ValueFunction.scoreTarget(currentGame, null, myBear.getId(), true);
        double hostile = ValueFunction.scoreTarget(currentGame, null, myBear.getId(), false);
        // 同一目标：friendly=buff（小幅正），hostile=remove（更大正）
        assertTrue("hostile 分应大于 friendly: hostile=" + hostile + " friendly=" + friendly, hostile > friendly);
    }

    @Test
    public void scoreYesNo_unknownHint_returnsZero() {
        setStopAt(1, PhaseStep.END_TURN);
        execute();
        double score = ValueFunction.scoreYesNo(currentGame, null, "unknown-prompt");
        assertEquals("未知 hint 应得 0（保守同 Tians）", 0.0, score, 1e-6);
    }

    @Test
    public void scoreYesNo_drawHint_isPositive() {
        setStopAt(1, PhaseStep.END_TURN);
        execute();
        double score = ValueFunction.scoreYesNo(currentGame, null, "Draw a card?");
        assertTrue("含 draw 的 hint 应得正分: " + score, score > 0);
    }

    // -------------------------------------------------------------------------
    // Task 2 tests: scoreHandCardAsThreat (Layer C)
    // -------------------------------------------------------------------------

    @Test
    public void scoreHandCardAsThreat_emrakulHigherThanLotusPetal() {
        addCard(Zone.HAND, playerA, "Emrakul, the Aeons Torn", 1);
        addCard(Zone.HAND, playerA, "Lotus Petal", 1);
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
        mage.cards.Card emrakul = null, petal = null;
        for (mage.cards.Card c : playerA.getHand().getCards(currentGame)) {
            if (c.getName().equals("Emrakul, the Aeons Torn")) emrakul = c;
            if (c.getName().equals("Lotus Petal")) petal = c;
        }
        double emrakulScore = ValueFunction.scoreHandCardAsThreat(emrakul, currentGame);
        double petalScore   = ValueFunction.scoreHandCardAsThreat(petal,   currentGame);
        assertTrue("Emrakul (15/15, Annihilator 6) should score >> Lotus Petal: emrakul=" + emrakulScore + " petal=" + petalScore,
            emrakulScore > petalScore + 20);
    }

    @Test
    public void scoreHandCardAsThreat_creatureScalesWithPower() {
        addCard(Zone.HAND, playerA, "Grizzly Bears", 1);     // 2/2
        addCard(Zone.HAND, playerA, "Centaur Courser", 1);   // 3/3
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
        mage.cards.Card bears = null, courser = null;
        for (mage.cards.Card c : playerA.getHand().getCards(currentGame)) {
            if (c.getName().equals("Grizzly Bears")) bears = c;
            if (c.getName().equals("Centaur Courser")) courser = c;
        }
        assertTrue("3/3 应比 2/2 评分高",
            ValueFunction.scoreHandCardAsThreat(courser, currentGame) >
            ValueFunction.scoreHandCardAsThreat(bears, currentGame));
    }
}
