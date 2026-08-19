package org.mage.test.mtgeek.llm;

import mage.abilities.ActivatedAbility;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.Game;
import mage.players.Player;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Replay bug 2026-06-11 (3/10 games): the LLM cast Snuff Out "to kill Atraxa"
 * — but Atraxa is black and Snuff Out reads "destroy target nonblack creature",
 * so the only legal target was the caster's own Murktide Regent, which the
 * engine then auto-picked silently. The cast option shown to the LLM carried
 * no target information at all.
 *
 * buildPriorityOptions must therefore attach a legal_targets list to every
 * option whose ability requires targets, tagged with whose object each is.
 */
public class HookOptionsLegalTargetsTest extends CardTestPlayerBaseAI {

    private Map<String, Object> findOption(List<HookOptions.Option> options, String labelPart) {
        for (HookOptions.Option o : options) {
            if (o.label != null && o.label.contains(labelPart)) {
                return o.toMap();
            }
        }
        fail("没有找到 label 含 \"" + labelPart + "\" 的选项；options=" + options.size());
        return null;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void buildPriorityOptions_snuffOut_listsOnlyLegalNonblackTargets() {
        addCard(Zone.HAND, playerA, "Snuff Out", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 4);
        // 我方唯一生物：非黑，是 Snuff Out 唯一合法目标
        addCard(Zone.BATTLEFIELD, playerA, "Murktide Regent", 1);
        // 对方生物：黑色，不是合法目标
        addCard(Zone.BATTLEFIELD, playerB, "Atraxa, Grand Unifier", 1);

        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        Game game = currentGame;
        Player a = game.getPlayer(playerA.getId());
        List<ActivatedAbility> playable = a.getPlayable(game, true);
        List<HookOptions.Option> options = HookOptions.buildPriorityOptions(playable, game);

        Map<String, Object> snuff = findOption(options, "Snuff Out");
        Object lt = snuff.get("legal_targets");
        assertNotNull("Cast \"Snuff Out\" 选项必须带 legal_targets 字段", lt);
        List<String> targets = (List<String>) lt;

        assertTrue("legal_targets 应包含我方 Murktide Regent，实际=" + targets,
                targets.stream().anyMatch(s -> s.contains("Murktide Regent")));
        assertTrue("Murktide 是我方生物，应标注归属（yours），实际=" + targets,
                targets.stream().anyMatch(s -> s.contains("Murktide Regent") && s.contains("yours")));
        assertTrue("黑色的 Atraxa 不是 Snuff Out 的合法目标，不应出现，实际=" + targets,
                targets.stream().noneMatch(s -> s.contains("Atraxa")));
    }

    @Test
    public void buildPriorityOptions_untargetedSpell_hasNoLegalTargetsField() {
        addCard(Zone.HAND, playerA, "Opt", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 1);

        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        Game game = currentGame;
        Player a = game.getPlayer(playerA.getId());
        List<ActivatedAbility> playable = a.getPlayable(game, true);
        List<HookOptions.Option> options = HookOptions.buildPriorityOptions(playable, game);

        Map<String, Object> opt = findOption(options, "Opt");
        assertTrue("无目标咒语不应带 legal_targets 字段",
                !opt.containsKey("legal_targets"));
    }

    @Test
    public void buildChooseTargetOptions_withPov_tagsOwnership() {
        addCard(Zone.BATTLEFIELD, playerA, "Murktide Regent", 1);
        addCard(Zone.BATTLEFIELD, playerB, "Atraxa, Grand Unifier", 1);

        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        Game game = currentGame;
        java.util.UUID mine = game.getBattlefield()
                .getAllActivePermanents(playerA.getId()).get(0).getId();
        java.util.UUID theirs = game.getBattlefield()
                .getAllActivePermanents(playerB.getId()).get(0).getId();
        List<HookOptions.Option> options = HookOptions.buildChooseTargetOptions(
                java.util.Arrays.asList(mine, theirs, playerB.getId()), game, playerA.getId());

        assertTrue("我方永久物应标 yours，实际=" + options.get(0).label,
                options.get(0).label.contains("yours"));
        assertTrue("对方永久物应标 opponent's，实际=" + options.get(1).label,
                options.get(1).label.contains("opponent's"));
        assertTrue("对方玩家应标 opponent，实际=" + options.get(2).label,
                options.get(2).label.contains("opponent"));
    }
}
