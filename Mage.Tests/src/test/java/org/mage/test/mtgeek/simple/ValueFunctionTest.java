package org.mage.test.mtgeek.simple;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * TDD tests for ValueFunction.scorePass — B1' SimpleAI Task 7.
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
}
