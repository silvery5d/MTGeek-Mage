package org.mage.test.mtgeek.replay;

import mage.collectors.DataCollectorServices;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameException;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import org.junit.After;
import org.junit.Test;
import org.mage.test.mtgeek.MTGeekSimplePlayer;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * B3 T4 — End-to-end replay test.
 * Runs MTGeekSimplePlayer vs MTGeekSimplePlayer, records events via MatchRecorder,
 * and writes a JSON replay file to MTGeek/public/replays/ (cross-repo).
 */
public class MTGeekReplayMatchTest extends CardTestPlayerBaseAI {

    private static final String DECK_A = "src/test/resources/mtgeek/show-and-tell.dck";
    private static final String DECK_B = "src/test/resources/mtgeek/dimir-tempo.dck";

    private MatchRecorder recorder;

    // -----------------------------------------------------------------------
    // Both seats run MTGeekSimplePlayer — no trivial opponent.
    // -----------------------------------------------------------------------
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence range) {
        TestPlayer tp = new TestPlayer(new MTGeekSimplePlayer(name, range));
        tp.setAIPlayer(true);
        return tp;
    }

    // -----------------------------------------------------------------------
    // Suppress default AI wrapping.
    // -----------------------------------------------------------------------
    @Override
    public List<String> getFullSimulatedPlayers() {
        return new ArrayList<>();
    }

    // -----------------------------------------------------------------------
    // Load the two real Legacy decks — body copied verbatim from
    // MTGeekSimpleMatchTest.createNewGameAndPlayers().
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
    // Teardown — unregister recorder even if test throws.
    // -----------------------------------------------------------------------
    @After
    public void cleanup() {
        if (recorder != null) {
            try {
                DataCollectorServices.unregister(recorder);
            } catch (Exception ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // The actual test.
    // -----------------------------------------------------------------------
    @Test
    public void recordSimpleVsSimple_andWriteJson() throws IOException {
        recorder = new MatchRecorder();
        DataCollectorServices.register(recorder);

        setStopAt(50, PhaseStep.UNTAP);
        execute();
        assertTrue("game should have ended", currentGame.hasEnded());

        // --- build metadata ---
        ReplayWriter.Metadata meta = new ReplayWriter.Metadata();
        String ts = Instant.now().toString().replaceAll("[:.]", "-");
        meta.matchId   = "match-" + ts;
        meta.timestamp = Instant.now().toString();
        meta.deckA     = "show-and-tell.dck";
        meta.deckB     = "dimir-tempo.dck";
        meta.playerA   = "MTGeekSimplePlayer";
        meta.playerB   = "MTGeekSimplePlayer";

        String winnerName = currentGame.getWinner();
        if (winnerName == null || winnerName.isEmpty()) {
            meta.winner = "draw";
        } else if (winnerName.contains("PlayerA")) {
            meta.winner = "A";
        } else {
            meta.winner = "B";
        }

        meta.endTurn   = currentGame.getTurnNum();
        meta.endReason = (winnerName == null || winnerName.isEmpty()) ? "draw" : winnerName;

        // --- resolve output path (cross-repo write) ---
        Path out = resolveOutputPath(meta.matchId);

        // --- write ---
        ReplayWriter.write(out, meta, recorder.getEvents());
        System.out.println("[B3] Replay written: " + out
                + " (" + recorder.getEvents().size() + " events)");

        // --- assertions ---
        assertTrue("replay file should exist", Files.exists(out));
        assertTrue(
                "events should be > 20, got " + recorder.getEvents().size(),
                recorder.getEvents().size() > 20);
    }

    /**
     * Cross-repo write: default target is ~/Documents/claude/MTGeek/public/replays/.
     * Override via MTGEEK_REPLAY_DIR env var.
     * Fallback to target/replays/ when the cross-repo path does not exist.
     */
    private Path resolveOutputPath(String matchId) {
        String overrideDir = System.getenv("MTGEEK_REPLAY_DIR");
        if (overrideDir != null && !overrideDir.isEmpty()) {
            return Path.of(overrideDir, matchId + ".json");
        }
        Path crossRepo = Path.of(System.getProperty("user.home"),
                "Documents/claude/MTGeek/public/replays", matchId + ".json");
        if (Files.isDirectory(crossRepo.getParent())) {
            return crossRepo;
        }
        return Path.of("target/replays", matchId + ".json");
    }
}
