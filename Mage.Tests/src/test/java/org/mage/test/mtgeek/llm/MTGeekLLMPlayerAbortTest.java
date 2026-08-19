package org.mage.test.mtgeek.llm;

import mage.collectors.DataCollectorServices;
import mage.collectors.services.EmptyDataCollector;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.Table;
import org.junit.After;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertTrue;

/**
 * Replay bug 2026-06-11 / 2026-08-18 (ghost cast): the LLM picks a cast, a
 * payment choice makes the cost unpayable (e.g. Surgical Extraction phyrexian
 * {B/P} answered "pay {B}" with zero black sources), the engine silently rolls
 * the cast back, and the replay keeps an orphan decision with no cast_spell.
 * The aborted attempt must be logged so MatchRecorder can emit cast_aborted.
 */
public class MTGeekLLMPlayerAbortTest extends CardTestPlayerBaseAI {

    private final MTGeekLLMPlayerTest.FakeHttpClient fake = new MTGeekLLMPlayerTest.FakeHttpClient();
    private final ListGameLogCollector collector = new ListGameLogCollector();

    @Override
    public List<String> getFullSimulatedPlayers() {
        return Collections.emptyList();
    }

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence range) {
        if ("PlayerA".equals(name)) {
            TestPlayer tp = new TestPlayer(new MTGeekLLMPlayer(name, range, fake));
            tp.setAIPlayer(true);
            return tp;
        }
        return super.createPlayer(name, range);
    }

    private static final class ListGameLogCollector extends EmptyDataCollector {
        final List<String> lines = new ArrayList<>();

        @Override
        public String getServiceCode() {
            return "mtgeek-llm-abort-test-log";
        }

        @Override
        public void onGameLog(Game game, String message) {
            lines.add(message);
        }

        @Override
        public void onTableStart(Table table) {}

        @Override
        public void onTableEnd(Table table) {}

        @Override
        public void onChatGame(UUID gameId, String userName, String message) {}
    }

    @After
    public void unregisterCollector() {
        DataCollectorServices.unregister(collector);
    }

    @Test
    public void cast_paymentChoiceMakesCostUnpayable_logsCastAborted() {
        DataCollectorServices.register(collector);

        // Surgical Extraction {B/P}：无地、无黑法力 → 唯一可付方式是 2 点生命。
        addCard(Zone.HAND, playerA, "Surgical Extraction", 1);
        // 对手坟场给一个合法目标，保证 getPlayable 提供该咒语。
        addCard(Zone.GRAVEYARD, playerB, "Lightning Bolt", 1);

        // priority：选 Cast Surgical Extraction（index 1，0 是 Pass）
        fake.queueResponse(new DecisionResponse(new int[]{1}, "抽掉对手的关键牌"));
        // phyrexian chooseUse "Pay 2 life instead of {B}?"：答 NO（付 {B}）→ 付不起 → 回滚
        fake.queueResponse(new DecisionResponse(new int[]{0}, "不付生命，付黑法力即可"));
        // 后续任何 decide 调用：空队列抛异常 → fallback（不影响本断言）

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        boolean aborted = collector.lines.stream().anyMatch(l ->
                l.startsWith("[ABORT|PlayerA]") && l.contains("Surgical Extraction"));
        assertTrue("施放被回滚时必须写 [ABORT|PlayerA] ... Surgical Extraction 日志；实际日志:\n"
                        + String.join("\n", collector.lines.stream()
                                .filter(l -> l.contains("Surgical") || l.contains("ABORT")
                                        || l.contains("LLM") || l.contains("Simple"))
                                .toArray(String[]::new)),
                aborted);
    }
}
