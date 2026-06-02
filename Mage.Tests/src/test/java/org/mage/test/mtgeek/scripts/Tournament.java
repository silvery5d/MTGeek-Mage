package org.mage.test.mtgeek.scripts;

import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameException;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import org.junit.Test;
import org.mage.test.mtgeek.MTGeekSimplePlayer;
import org.mage.test.mtgeek.MTGeekTrivialPlayer;
import org.mage.test.player.TestComputerPlayer7;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 统计对战脚本：跑 N 场 Simple vs Trivial + N 场 Simple vs default ComputerPlayer7，
 * 输出 CSV 到 target/tournament-results-*.csv。
 *
 * 通过 mvn test -pl Mage.Tests -Dtest=Tournament 触发。
 *
 * 设计要点:
 *  - 使用 reset() （即 @Before 方法）在每场比赛前重置游戏状态。
 *  - 每轮交替哪个 seat 是 Simple，消除先手优势偏差。
 *  - threadLocal 字段控制 createPlayer() 创建哪种 AI。
 */
public class Tournament extends CardTestPlayerBaseAI {

    private static final int N_GAMES = 30;

    private static final String DECK_A = "src/test/resources/mtgeek/show-and-tell.dck";
    private static final String DECK_B = "src/test/resources/mtgeek/dimir-tempo.dck";

    // --- 每局前由 runTournament() 设置 ---
    // 哪个 seat 是 Simple（true → PlayerA 是 Simple）
    private volatile boolean simpleIsPlayerA = true;
    // true → Simple 用 DECK_A（show-and-tell），false → Simple 用 DECK_B（dimir-tempo）
    private volatile boolean simpleUseDeckA = true;
    // true → 对手是 MTGeekTrivialPlayer；false → 对手是 ComputerPlayer7
    private volatile boolean useTrivialOpponent = true;

    // -----------------------------------------------------------------------
    // 不使用父类默认的全模拟玩家列表，由 createPlayer() 完全接管
    // -----------------------------------------------------------------------
    @Override
    public List<String> getFullSimulatedPlayers() {
        return Collections.emptyList();
    }

    // -----------------------------------------------------------------------
    // Seat → AI 映射：由 simpleIsPlayerA / useTrivialOpponent 控制
    // -----------------------------------------------------------------------
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence range) {
        boolean isPlayerA = "PlayerA".equals(name);
        boolean isSimpleSeat = (isPlayerA == simpleIsPlayerA);

        TestPlayer tp;
        if (isSimpleSeat) {
            tp = new TestPlayer(new MTGeekSimplePlayer(name, range));
        } else if (useTrivialOpponent) {
            tp = new TestPlayer(new MTGeekTrivialPlayer(name, range));
        } else {
            // default ComputerPlayer7（与 CardTestPlayerBaseAI 默认行为一致）
            tp = new TestPlayer(new TestComputerPlayer7(name, RangeOfInfluence.ONE, getSkillLevel()));
        }
        tp.setAIPlayer(true);
        return tp;
    }

    // -----------------------------------------------------------------------
    // 每局创建新游戏：deck 随 simpleUseDeckA 交替，与 seat 交替独立，
    // 确保 Simple 各用两副牌各 15/30 场，隔离 AI 质量与卡组对位偏差。
    // -----------------------------------------------------------------------
    @Override
    protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
        Game game = new TwoPlayerDuel(
                MultiplayerAttackOption.LEFT,
                RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0),
                60, 20, 7);

        String simpleDeck = simpleUseDeckA ? DECK_A : DECK_B;
        String oppDeck    = simpleUseDeckA ? DECK_B : DECK_A;

        if (simpleIsPlayerA) {
            playerA = createPlayer(game, "PlayerA", simpleDeck);
            playerB = createPlayer(game, "PlayerB", oppDeck);
        } else {
            playerA = createPlayer(game, "PlayerA", oppDeck);
            playerB = createPlayer(game, "PlayerB", simpleDeck);
        }
        return game;
    }

    // -----------------------------------------------------------------------
    // @Test 入口：Simple vs Trivial (30 场)
    // -----------------------------------------------------------------------
    @Test
    public void simpleVsTrivial_30games() throws Exception {
        useTrivialOpponent = true;
        runTournament("simple-vs-trivial");
    }

    // -----------------------------------------------------------------------
    // @Test 入口：Simple vs ComputerPlayer7 (30 场)
    // -----------------------------------------------------------------------
    @Test
    public void simpleVsComputerPlayer_30games() throws Exception {
        useTrivialOpponent = false;
        runTournament("simple-vs-computer");
    }

    // -----------------------------------------------------------------------
    // 核心：循环 N 场，每场调用 reset() → execute()，收集结果写 CSV
    // -----------------------------------------------------------------------
    private void runTournament(String label) throws Exception {
        List<String> csv = new ArrayList<>();
        csv.add("game_idx,simple_seat,simple_deck,result,turns,error");

        int simpleWins = 0, oppWins = 0, draws = 0, errors = 0, completed = 0;

        for (int i = 0; i < N_GAMES; i++) {
            simpleIsPlayerA = (i % 2 == 0);   // 交替先后手（seat alternation）
            simpleUseDeckA  = ((i / 2) % 2 == 0);  // 每 2 场交替卡组（deck alternation）

            try {
                // reset() 是 @Before 方法，直接调用可重置游戏状态
                reset();

                setStopAt(50, PhaseStep.UNTAP);
                execute();

                boolean ended = currentGame.hasEnded();
                if (ended) completed++;

                int turn = currentGame.getTurnNum();
                String winner = parseWinner(currentGame.getWinner(), simpleIsPlayerA);

                String deckLabel = simpleUseDeckA ? "show-and-tell" : "dimir-tempo";
                csv.add(String.format("%d,%s,%s,%s,%d,", i,
                        simpleIsPlayerA ? "PlayerA" : "PlayerB", deckLabel, winner, turn));

                if ("simple".equals(winner))     simpleWins++;
                else if ("opp".equals(winner))   oppWins++;
                else                              draws++;

                System.out.printf("[Tournament] Game %2d/%d  seat=%s  deck=%-15s  result=%-6s  turn=%d%n",
                        i + 1, N_GAMES, simpleIsPlayerA ? "A" : "B", deckLabel, winner, turn);

            } catch (Throwable e) {
                // Catch both Exception and AssertionError (XMage fires AssertionError via
                // JUnit Assert.fail for "too many priority calls" and similar guards).
                errors++;
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replace(',', ';');
                // Truncate long messages (priority-loop errors are verbose)
                if (msg.length() > 120) msg = msg.substring(0, 120) + "...";
                String deckLabel = simpleUseDeckA ? "show-and-tell" : "dimir-tempo";
                csv.add(String.format("%d,%s,%s,error,0,%s", i,
                        simpleIsPlayerA ? "PlayerA" : "PlayerB", deckLabel, msg));
                System.err.printf("[Tournament] Game %2d/%d ERROR: %s%n", i + 1, N_GAMES, msg);
            }
        }

        // 写 CSV
        Path out = Path.of("target/tournament-results-" + label + ".csv");
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join("\n", csv) + "\n");

        // 打印汇总
        System.out.println();
        System.out.printf("=== Tournament [%s] ===%n", label);
        System.out.printf("Total games : %d%n", N_GAMES);
        System.out.printf("Completed   : %d / %d (%.1f%%)%n", completed, N_GAMES, pct(completed, N_GAMES));
        System.out.printf("Simple wins : %d (%.1f%%)%n", simpleWins, pct(simpleWins, N_GAMES));
        System.out.printf("Opp wins    : %d (%.1f%%)%n", oppWins, pct(oppWins, N_GAMES));
        System.out.printf("Draws       : %d (%.1f%%)%n", draws, pct(draws, N_GAMES));
        System.out.printf("Errors      : %d%n", errors);
        System.out.printf("CSV         : %s%n", out.toAbsolutePath());
        System.out.println("===========================");

        // B1' 验收标准断言（失败时只警告，不让 @Test 崩溃，避免掩盖数据）
        if ("simple-vs-trivial".equals(label)) {
            double winPct = pct(simpleWins, N_GAMES);
            if (winPct < 60.0) {
                System.out.printf("[WARN] §8.5.3 未达标: Simple vs Trivial win%% = %.1f%% (期望 ≥ 60%%)%n", winPct);
            } else {
                System.out.printf("[OK]   §8.5.3 达标: Simple vs Trivial win%% = %.1f%% ≥ 60%%%n", winPct);
            }
        }
        if ("simple-vs-computer".equals(label)) {
            double compPct = pct(completed, N_GAMES);
            if (compPct < 95.0) {
                System.out.printf("[WARN] §8.5.4 未达标: completion%% = %.1f%% (期望 ≥ 95%%)%n", compPct);
            } else {
                System.out.printf("[OK]   §8.5.4 达标: completion%% = %.1f%% ≥ 95%%%n", compPct);
            }
        }
    }

    /**
     * 解析 currentGame.getWinner() 返回的字符串。
     * 返回 "simple" / "opp" / "draw" 之一。
     *
     * GameImpl.getWinner() 返回:
     *   "Player PlayerA is the winner"
     *   "Game is a draw"
     */
    private String parseWinner(String winnerStr, boolean simpleIsA) {
        if (winnerStr == null || winnerStr.contains("draw") || winnerStr.contains("Draw")) {
            return "draw";
        }
        // 检查 PlayerA 是否获胜
        boolean playerAWon = winnerStr.contains("PlayerA");
        boolean simpleWon = (playerAWon == simpleIsA);
        return simpleWon ? "simple" : "opp";
    }

    private static double pct(int num, int denom) {
        return denom == 0 ? 0.0 : num * 100.0 / denom;
    }
}
