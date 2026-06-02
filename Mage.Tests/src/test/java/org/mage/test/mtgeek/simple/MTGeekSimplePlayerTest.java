package org.mage.test.mtgeek.simple;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.mtgeek.MTGeekSimplePlayer;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * TDD tests for Task 13: MTGeekSimplePlayer.priority() with lethal short-circuit
 * and legacy fallback.
 */
public class MTGeekSimplePlayerTest extends CardTestPlayerBaseAI {

    // Disable AI wrapping from CardTestPlayerBaseAI for the default PlayerA slot.
    // We inject MTGeekSimplePlayer ourselves in createPlayer().
    @Override
    public List<String> getFullSimulatedPlayers() {
        return Collections.emptyList();
    }

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence range) {
        // PlayerA uses MTGeekSimplePlayer; PlayerB uses the default TestPlayer (pass-all).
        if ("PlayerA".equals(name)) {
            TestPlayer tp = new TestPlayer(new MTGeekSimplePlayer(name, range));
            tp.setAIPlayer(true);
            return tp;
        }
        return super.createPlayer(name, range);
    }

    /**
     * PlayerA has Lightning Bolt in hand and 1 Mountain: the AI should cast it
     * during main phase (scoreCastSpell > scorePass).
     * After execute() the bolt should either be in PlayerA's graveyard (resolved)
     * or still on the stack (targeting requires interaction, not yet resolved).
     */
    @Test
    public void priority_mainPhaseWithBolt_castsTheBolt() {
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // Bolt should have been cast: either resolved into graveyard or sitting on stack.
        boolean inGraveyard = playerA.getGraveyard().getCards(currentGame).stream()
                .anyMatch(c -> "Lightning Bolt".equals(c.getName()));
        boolean onStack = !currentGame.getStack().isEmpty();

        assertTrue("Lightning Bolt should have been cast (in graveyard or on stack)",
                inGraveyard || onStack);
    }

    /**
     * PlayerB starts at 1 life; PlayerA has Serra Angel (4/4 flying vigilance)
     * which bypasses summoning sickness after turn 2.
     * The AI lethal-detection path should trigger and the game should end via
     * combat damage by turn 3 (or 4 to be safe).
     */
    @Test
    public void priority_lethalAvailable_attacksAll() {
        // Serra Angel needs summoning sickness to clear (takes until opponent's turn 3).
        addCard(Zone.BATTLEFIELD, playerA, "Serra Angel", 1);

        // Set PlayerB to 1 life: any attack ends the game.
        playerB.setLife(1, currentGame, null);

        setStopAt(4, PhaseStep.END_TURN);
        execute();

        boolean gameOver = currentGame.hasEnded() || playerB.getLife() <= 0;
        assertTrue("Lethal should have been delivered by turn 4", gameOver);
    }
}
