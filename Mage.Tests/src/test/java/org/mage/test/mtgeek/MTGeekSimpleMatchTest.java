package org.mage.test.mtgeek;

import mage.collectors.DataCollectorServices;
import mage.collectors.services.EmptyDataCollector;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameException;
import mage.game.Table;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import org.junit.After;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertTrue;

/**
 * 端到端测试：MTGeekSimplePlayer（Show and Tell）vs MTGeekTrivialPlayer（Dimir Tempo）。
 * <p>
 * SimpleAI 应能完整跑完一局而不崩，且 game log 中必须出现 [Simple|*] 决策行。
 * 日志写入 target/simple-vs-trivial-match.log 供赛后检视。
 */
public class MTGeekSimpleMatchTest extends CardTestPlayerBaseAI {

    private static final String DECK_A = "src/test/resources/mtgeek/show-and-tell.dck";
    private static final String DECK_B = "src/test/resources/mtgeek/dimir-tempo.dck";

    private final ListGameLogCollector collector = new ListGameLogCollector();

    // -----------------------------------------------------------------------
    // Suppress default AI wrapping — our createPlayer() handles both seats.
    // -----------------------------------------------------------------------
    @Override
    public List<String> getFullSimulatedPlayers() {
        return Collections.emptyList();
    }

    // -----------------------------------------------------------------------
    // Seat A → MTGeekSimplePlayer; Seat B → MTGeekTrivialPlayer.
    // -----------------------------------------------------------------------
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        // playerA is created first; match on name so subsequent calls get Trivial.
        boolean isSimple = "PlayerA".equals(name);
        TestPlayer tp;
        if (isSimple) {
            tp = new TestPlayer(new MTGeekSimplePlayer(name, rangeOfInfluence));
        } else {
            tp = new TestPlayer(new MTGeekTrivialPlayer(name, rangeOfInfluence));
        }
        tp.setAIPlayer(true);
        return tp;
    }

    // -----------------------------------------------------------------------
    // Load the two real Legacy decks (same pattern as MTGeekDeckMatchTest).
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
    // Log collector — mirrors MTGeekDeckMatchTest.ListGameLogCollector exactly.
    // -----------------------------------------------------------------------

    private static final class ListGameLogCollector extends EmptyDataCollector {
        final List<String> lines = new ArrayList<>();

        @Override
        public String getServiceCode() {
            return "mtgeek-simple-match-log";
        }

        @Override
        public void onGameLog(Game game, String message) {
            lines.add(message);
        }

        @Override
        public void onGameStart(Game game) {
            lines.add("[GAME START] id=" + game.getId());
        }

        @Override
        public void onGameEnd(Game game) {
            lines.add("[GAME END] winner=" + game.getWinner() + "  turn=" + game.getTurnNum());
        }

        @Override
        public void onTableStart(Table table) {}

        @Override
        public void onTableEnd(Table table) {}

        @Override
        public void onChatGame(UUID gameId, String userName, String message) {}
    }

    // -----------------------------------------------------------------------
    // Teardown.
    // -----------------------------------------------------------------------
    @After
    public void unregisterCollector() {
        DataCollectorServices.unregister(collector);
    }

    // -----------------------------------------------------------------------
    // The actual test.
    // -----------------------------------------------------------------------
    @Test
    public void simpleVsTrivial_completesAndLogsSimpleDecisions() throws IOException {
        DataCollectorServices.register(collector);

        setStopAt(50, PhaseStep.UNTAP);
        execute();

        // Build log content.
        String summary = String.format(
                "hasEnded=%s  winner=%s  turn=%d",
                currentGame.hasEnded(),
                currentGame.getWinner(),
                currentGame.getTurnNum());

        List<String> allLines = new ArrayList<>(collector.lines);
        allLines.add("");
        allLines.add("=== FINAL SUMMARY ===");
        allLines.add(summary);

        // Write to target/ for post-mortem inspection.
        Path out = Path.of("target/simple-vs-trivial-match.log");
        Files.createDirectories(out.getParent());
        Files.write(out, allLines);

        // Core assertions.
        assertTrue(
                "Game should have ended by turn 50. " + summary,
                currentGame.hasEnded());

        assertTrue(
                "Game log must contain [Simple|*] decision lines",
                collector.lines.stream().anyMatch(s -> s.contains("[Simple|")));
    }
}
