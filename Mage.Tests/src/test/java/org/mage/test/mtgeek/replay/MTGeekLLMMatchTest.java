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
import org.junit.Ignore;
import org.junit.Test;
import org.mage.test.mtgeek.MTGeekSimplePlayer;
import org.mage.test.mtgeek.llm.MTGeekLLMPlayer;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * B2' T9 — End-to-end LLM vs Simple replay test.
 *
 * <p><b>@Ignore by default</b> — requires:
 * <ul>
 *   <li>MTGeek dev server running on {@code localhost:3000} ({@code npm run dev})</li>
 *   <li>{@code MINIMAX_API_KEY} set in MTGeek's {@code .env}</li>
 *   <li>5–25 minutes wall-clock per game</li>
 * </ul>
 *
 * <p>Runs {@link MTGeekLLMPlayer} (PlayerA) vs {@link MTGeekSimplePlayer} (PlayerB),
 * records events via {@link MatchRecorder}, and writes a {@code llm-vs-simple-<ts>.json}
 * replay file to {@code MTGeek/public/replays/} (cross-repo path, with fallback to
 * {@code target/replays/}).
 *
 * <p>After the game, the test asserts that the replay JSON contains at least one
 * decision event whose payload carries {@code "source":"LLM"} — verifying that the
 * LLM player actually made (or attempted) at least one priority decision via the
 * remote endpoint.
 */
@Ignore("Manual run: requires MTGeek dev server on localhost:3000 with MINIMAX_API_KEY set; takes 5-25 min")
public class MTGeekLLMMatchTest extends CardTestPlayerBaseAI {

    private static final String DECK_A = "src/test/resources/mtgeek/show-and-tell.dck";
    private static final String DECK_B = "src/test/resources/mtgeek/dimir-tempo.dck";

    private MatchRecorder recorder;

    // -----------------------------------------------------------------------
    // PlayerA = MTGeekLLMPlayer (LLM-backed); PlayerB = MTGeekSimplePlayer.
    // Dispatch on name — same pattern as MTGeekSimpleMatchTest.
    // -----------------------------------------------------------------------
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence range) {
        TestPlayer tp;
        if ("PlayerA".equals(name)) {
            tp = new TestPlayer(new MTGeekLLMPlayer(name, range));
        } else {
            tp = new TestPlayer(new MTGeekSimplePlayer(name, range));
        }
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
    // Load the two real Legacy decks — same structure as MTGeekReplayMatchTest.
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
    // Teardown — unregister recorder even if the test throws.
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
    public void llmVsSimple_completesOneGame_writesReplayWithLLMSource() throws IOException {
        recorder = new MatchRecorder();

        // Diagnostic dump — raw log lines for post-mortem inspection.
        String ts = Instant.now().toString().replaceAll("[:.]", "-");
        Path rawDump = Path.of("target/raw-log-diagnosis-llm-" + ts + ".txt");
        Files.createDirectories(rawDump.getParent());
        recorder.setRawLogPath(rawDump);
        System.out.println("[B2'T9] Raw log dump: " + rawDump.toAbsolutePath());

        DataCollectorServices.register(recorder);

        setStopAt(50, PhaseStep.UNTAP);
        execute();
        assertTrue("game should have ended", currentGame.hasEnded());

        // --- build metadata ---
        String matchId = "llm-vs-simple-" + ts;

        ReplayWriter.Metadata meta = new ReplayWriter.Metadata();
        meta.matchId   = matchId;
        meta.timestamp = Instant.now().toString();
        meta.deckA     = "show-and-tell.dck";
        meta.deckB     = "dimir-tempo.dck";
        meta.playerA   = "MTGeekLLMPlayer";
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

        // --- resolve output path (cross-repo write, llm-prefixed filename) ---
        Path out = resolveOutputPath(matchId);

        // --- write ---
        ReplayWriter.write(out, meta, recorder.getEvents());
        System.out.println("[B2'T9] Replay written: " + out
                + " (" + recorder.getEvents().size() + " events)");

        // --- assertions ---
        assertTrue("replay file should exist", Files.exists(out));
        assertTrue(
                "events should be > 20, got " + recorder.getEvents().size(),
                recorder.getEvents().size() > 20);

        // Assert at least one LLM-sourced decision appears in the replay JSON.
        // DecisionLogger.logLLM() emits [LLM|...] lines that MatchRecorder picks up
        // and tags with source="LLM" in the replay event payload.
        String replayJson = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
        assertTrue(
                "replay JSON should contain at least one LLM-sourced decision (\"source\":\"LLM\")",
                replayJson.contains("\"source\":\"LLM\"") || replayJson.contains("\"source\": \"LLM\""));
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
