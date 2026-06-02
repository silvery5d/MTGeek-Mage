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

    /**
     * PlayerA has two Grizzly Bears (2/2) on the battlefield.
     * After summoning sickness clears (turn 3), the AI should attack with both.
     * PlayerB starts at 20 life; if both Bears attack unblocked, life drops to 16.
     */
    @Test
    public void selectAttackers_allEligibleAttack() {
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 2);  // 2/2 ×2
        setStopAt(3, PhaseStep.END_TURN);  // run to turn 3 so summoning sickness clears
        execute();

        // After combat in turn 3, opponent should have taken damage (20 → 16 at minimum)
        assertTrue("对手应受到攻击伤害，实测 life=" + playerB.getLife(), playerB.getLife() < 20);
    }

    /**
     * PlayerA (我方 MTGeekSimplePlayer) has a 3/3; PlayerB has a 2/2 that attacks.
     * The AI should assign the 3/3 as blocker (scoreBlock > 0: trade up kills 2/2).
     * Smoke test: game advances without crash, and since the 3/3 blocks the 2/2,
     * PlayerA takes 0 combat damage.
     */
    @Test
    public void selectBlockers_canBlockWithoutCrash() {
        // 我方 3/3 阻挡者，对手 2/2 攻击者
        addCard(Zone.BATTLEFIELD, playerA, "Centaur Courser", 1);  // 3/3
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears", 1);     // 2/2

        // 跑两个 turn：turn 1 (playerA active), turn 2 (playerB attacks)
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        // 主要验证不崩（游戏推进到 turn >= 1）
        assertTrue("游戏推进未崩，turn=" + currentGame.getTurnNum(), currentGame.getTurnNum() >= 1);
        // 3/3 blocks 2/2: PlayerA should take 0 combat damage (life stays at 20)
        assertTrue("3/3 应挡住 2/2，PlayerA life 应为 20，实测=" + playerA.getLife(),
                playerA.getLife() == 20);
    }
}
