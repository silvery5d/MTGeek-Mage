package org.mage.test.mtgeek;

import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameException;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * End-to-end AI-vs-AI deck match test (RED in TDD cycle).
 * <p>
 * Loads two real 60-card .dck files and runs a game to completion using
 * {@link MTGeekTrivialPlayer} for both sides.
 * <p>
 * RED because {@code MTGeekTrivialPlayer.priority()} is a no-op from
 * {@code ComputerPlayer}: the game will stall or end without any player
 * winning inside 50 turns.  Task 13 overrides {@code priority()} to make
 * this GREEN.
 * <p>
 * Note on log capture: {@code DataCollectorServices.activeServices} is
 * package-private, so we cannot inject a collector from this package without
 * reflection.  The game log therefore flows only to the standard console
 * output captured by Surefire.  The {@code target/match-log.txt} file
 * produced here records the final game state summary instead.
 *
 * @author MTGeek / Task 12
 */
public class MTGeekDeckMatchTest extends CardTestPlayerBaseAI {

    // Deck paths relative to Mage.Tests/ working directory (same convention
    // as the default "RB Aggro.dck" in CardTestPlayerBase).
    private static final String DECK_A = "src/test/resources/mtgeek/show-and-tell.dck";
    private static final String DECK_B = "src/test/resources/mtgeek/dimir-tempo.dck";

    // -----------------------------------------------------------------------
    // Bypass the harness's AI branch: return empty list so createPlayer()
    // does not wrap anyone in TestComputerPlayer7.  Our override of
    // createPlayer(String, RangeOfInfluence) handles both players instead.
    // -----------------------------------------------------------------------
    @Override
    public List<String> getFullSimulatedPlayers() {
        return Collections.emptyList();
    }

    // -----------------------------------------------------------------------
    // Both players use MTGeekTrivialPlayer (our custom ComputerPlayer subclass).
    // setAIPlayer(true) tells TestPlayer.tryToPlayPriority() to call
    // computerPlayer.priority() rather than computerPlayer.pass().
    // -----------------------------------------------------------------------
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        TestPlayer tp = new TestPlayer(new MTGeekTrivialPlayer(name, rangeOfInfluence));
        tp.setAIPlayer(true);
        return tp;
    }

    // -----------------------------------------------------------------------
    // Override game creation to load our custom decks.
    // The base createPlayer(Game, String, String) resolves paths relative to
    // the Mage.Tests/ working dir, the same way "RB Aggro.dck" is found.
    // -----------------------------------------------------------------------
    @Override
    protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
        Game game = new TwoPlayerDuel(
                MultiplayerAttackOption.LEFT,
                RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0),
                60, 20, 7);

        playerA = createPlayer(game, "PlayerA", DECK_A);
        playerB = createPlayer(game, "PlayerB", DECK_B);
        return game;
    }

    // -----------------------------------------------------------------------
    // The actual test.
    // -----------------------------------------------------------------------
    @Test
    public void trivialMatchCompletes_ShowAndTell_vs_DimirTempo() throws IOException {
        // 50 turns gives the game plenty of room — or enough rope to confirm
        // that the game loops because priority() is a no-op (RED state).
        setStopAt(50, PhaseStep.UNTAP);
        execute();

        // Write a brief game-state summary to target/ for post-mortem inspection.
        Path out = Path.of("target/match-log.txt");
        Files.createDirectories(out.getParent());
        String summary = String.format(
                "hasEnded=%s  winner=%s  turn=%d%n",
                currentGame.hasEnded(),
                currentGame.getWinner(),
                currentGame.getTurnNum());
        Files.writeString(out, summary);

        // RED assertion: will fail until Task 13 overrides priority().
        // ComputerPlayer.priority() is a no-op, so the game should NOT end
        // by turn 50 — making this assertion reliably false in the RED state.
        assertTrue(
                "Game should have ended by turn 50 — FAIL expected until Task 13 implements priority(). "
                        + "Game summary: " + summary.trim(),
                currentGame.hasEnded());
    }
}
