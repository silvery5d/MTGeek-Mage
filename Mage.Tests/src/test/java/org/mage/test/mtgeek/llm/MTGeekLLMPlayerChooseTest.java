package org.mage.test.mtgeek.llm;

import mage.cards.Card;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.filter.FilterCard;
import mage.players.Player;
import mage.target.TargetCard;
import mage.target.common.TargetCardInLibrary;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Replay bug 2026-06-11: for "up to any number" targets (e.g. Atraxa's ETB,
 * max = Integer.MAX_VALUE) the LLM's picks were always discarded by the
 * "seen.size() < needed" under-return check, so choose() silently fell back
 * to SimpleAI on every such hook — the LLM never got a say.
 * A pick count within [minTargets, maxTargets] must be applied as-is.
 */
public class MTGeekLLMPlayerChooseTest extends CardTestPlayerBaseAI {

    private final MTGeekLLMPlayerTest.FakeHttpClient fake = new MTGeekLLMPlayerTest.FakeHttpClient();

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

    @Test
    public void choose_llmPicksFewerThanUnboundedMax_appliesLLMChoice() {
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.LIBRARY, playerA, "Island", 2);
        addCard(Zone.LIBRARY, playerA, "Lightning Bolt", 2);
        addCard(Zone.LIBRARY, playerA, "Grizzly Bears", 2);
        addCard(Zone.LIBRARY, playerA, "Divination", 2);
        addCard(Zone.LIBRARY, playerA, "Sol Ring", 1);
        addCard(Zone.LIBRARY, playerA, "Omniscience", 1);

        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        Player a = currentGame.getPlayer(playerA.getId());
        Cards revealed = new CardsImpl(a.getLibrary().getTopCards(currentGame, 10));
        assertEquals("测试前提：库顶应有 10 张已知牌", 10, revealed.size());
        List<Card> candidates = new ArrayList<>(revealed.getCards(currentGame));

        TargetCard target = new TargetCardInLibrary(0, Integer.MAX_VALUE, new FilterCard("cards"));
        fake.queueResponse(new DecisionResponse(new int[]{0, 1}, "只拿最有价值的两张"));

        MTGeekLLMPlayer ai = (MTGeekLLMPlayer) playerA.getComputerPlayer();
        boolean ok = ai.choose(Outcome.DrawCard, revealed, target, null, currentGame);

        assertTrue("choose 应返回 true", ok);
        assertEquals("LLM 选了 2 张就应采纳 2 张（不许回退到启发式全选）",
                2, target.getTargets().size());
        assertTrue("选中的应是 LLM 指定的第 0、1 号候选",
                target.getTargets().contains(candidates.get(0).getId())
                        && target.getTargets().contains(candidates.get(1).getId()));
    }
}
