package org.mage.test.mtgeek.llm;

import mage.abilities.Ability;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.permanent.Permanent;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Replay bug 2026-06-11 (rationale 视角混乱): chooseUse/chooseTarget requests
 * carried no information about WHICH effect the choice serves or WHOSE it is.
 * The LLM answering Thoughtseize's "choose a card to discard" believed it was
 * protecting its own hand; answering its own Surgical Extraction's phyrexian
 * prompt it spoke of "对手的Surgical Extraction". Requests must carry a
 * choice_context naming the source card and its controller.
 */
public class MTGeekLLMPlayerChoiceContextTest extends CardTestPlayerBaseAI {

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastChoiceContext() {
        Map<String, Object> req = fake.requests.get(fake.requests.size() - 1);
        return (Map<String, Object>) req.get("choice_context");
    }

    @Test
    public void chooseUse_forOpponentSourcedEffect_carriesSourceCardAndController() {
        addCard(Zone.BATTLEFIELD, playerB, "Sneak Attack", 1);

        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        Permanent sneak = currentGame.getBattlefield()
                .getAllActivePermanents(playerB.getId()).get(0);
        Ability source = sneak.getAbilities().get(0);

        MTGeekLLMPlayer ai = (MTGeekLLMPlayer) playerA.getComputerPlayer();
        fake.queueResponse(new DecisionResponse(new int[]{0}, "拒绝"));
        ai.chooseUse(Outcome.Neutral, "Some question?", source, currentGame);

        Map<String, Object> ctx = lastChoiceContext();
        assertNotNull("chooseUse 请求必须带 choice_context", ctx);
        assertEquals("Sneak Attack", ctx.get("source_card"));
        assertEquals("对手的效果发起了本次选择", "opponent", ctx.get("source_controller"));
    }

    @Test
    public void chooseUse_forOwnEffect_saysYou() {
        addCard(Zone.BATTLEFIELD, playerA, "Sneak Attack", 1);

        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        Permanent sneak = currentGame.getBattlefield()
                .getAllActivePermanents(playerA.getId()).get(0);
        Ability source = sneak.getAbilities().get(0);

        MTGeekLLMPlayer ai = (MTGeekLLMPlayer) playerA.getComputerPlayer();
        fake.queueResponse(new DecisionResponse(new int[]{1}, "确认"));
        ai.chooseUse(Outcome.Neutral, "Some question?", source, currentGame);

        Map<String, Object> ctx = lastChoiceContext();
        assertNotNull("chooseUse 请求必须带 choice_context", ctx);
        assertEquals("Sneak Attack", ctx.get("source_card"));
        assertEquals("you", ctx.get("source_controller"));
    }
}
