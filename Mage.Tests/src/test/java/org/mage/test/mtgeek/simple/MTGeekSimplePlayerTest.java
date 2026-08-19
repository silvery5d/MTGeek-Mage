package org.mage.test.mtgeek.simple;

import mage.abilities.Ability;
import mage.abilities.assignment.common.CardTypeAssignment;
import mage.cards.Card;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.filter.FilterCard;
import mage.game.Game;
import mage.players.Player;
import mage.target.TargetCard;
import mage.target.common.TargetCardInLibrary;
import org.junit.Test;
import org.mage.test.mtgeek.MTGeekSimplePlayer;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    /**
     * Mirror of AtraxaGrandUnifierTarget (package-private in Mage.Sets):
     * "up to any number" of cards, but possibleTargets() forbids adding a card
     * that shares a card type with an already-chosen one.
     */
    static class OnePerTypeTarget extends TargetCardInLibrary {
        private static final CardTypeAssignment cardTypeAssigner
                = new CardTypeAssignment(Arrays.stream(CardType.values()).toArray(CardType[]::new));

        OnePerTypeTarget() {
            super(0, Integer.MAX_VALUE, new FilterCard("a card of each card type"));
        }

        private OnePerTypeTarget(final OnePerTypeTarget target) {
            super(target);
        }

        @Override
        public OnePerTypeTarget copy() {
            return new OnePerTypeTarget(this);
        }

        @Override
        public Set<UUID> possibleTargets(UUID sourceControllerId, Ability source, Game game) {
            Set<UUID> possibleTargets = super.possibleTargets(sourceControllerId, source, game);
            Cards existingTargets = new CardsImpl(this.getTargets());
            possibleTargets.removeIf(id -> {
                Card card = game.getCard(id);
                if (card == null) {
                    return true;
                }
                Cards newTargets = existingTargets.copy();
                newTargets.add(card);
                return cardTypeAssigner.hasSharedRoles(newTargets, game);
            });
            return possibleTargets;
        }
    }

    /**
     * Replay bug 2026-06-11: Atraxa's ETB ("for each card type, you may put A card
     * of that type into your hand") let the AI grab all 10 revealed cards because
     * choose() blind-added everything, ignoring target.possibleTargets().
     * With 10 cards of 6 types on top, a legal choice can hold at most one card
     * of each card type.
     */
    @Test
    public void choose_onePerTypeTarget_neverPicksTwoCardsOfSameType() {
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.LIBRARY, playerA, "Island", 2);          // land
        addCard(Zone.LIBRARY, playerA, "Lightning Bolt", 2);  // instant
        addCard(Zone.LIBRARY, playerA, "Grizzly Bears", 2);   // creature
        addCard(Zone.LIBRARY, playerA, "Divination", 2);      // sorcery
        addCard(Zone.LIBRARY, playerA, "Sol Ring", 1);        // artifact
        addCard(Zone.LIBRARY, playerA, "Omniscience", 1);     // enchantment

        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        Player a = currentGame.getPlayer(playerA.getId());
        Cards revealed = new CardsImpl(a.getLibrary().getTopCards(currentGame, 10));
        assertTrue("测试前提：库顶应有 10 张已知牌", revealed.size() == 10);

        TargetCard target = new OnePerTypeTarget();
        MTGeekSimplePlayer ai = (MTGeekSimplePlayer) playerA.getComputerPlayer();
        ai.choose(Outcome.DrawCard, revealed, target, null, currentGame);

        Map<CardType, Integer> counts = new EnumMap<>(CardType.class);
        for (UUID id : target.getTargets()) {
            Card c = currentGame.getCard(id);
            for (CardType t : c.getCardType(currentGame)) {
                counts.merge(t, 1, Integer::sum);
            }
        }
        counts.forEach((type, n) -> assertTrue(
                "每种牌张类别至多选 1 张，但 " + type + " 选了 " + n + " 张（共选 "
                        + target.getTargets().size() + " 张）",
                n <= 1));
        assertTrue("至少应选中 1 张", !target.getTargets().isEmpty());
    }

    /**
     * End-to-end on the real card: AI casts Atraxa, Grand Unifier with 7 mana up;
     * her ETB reveals the top 10 and may take at most one card per card type.
     * With the blind-add bug all 10 went to hand and the library emptied.
     */
    @Test
    public void castAtraxa_etbTakesAtMostOneCardPerType() {
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.LIBRARY, playerA, "Island", 2);          // land
        addCard(Zone.LIBRARY, playerA, "Lightning Bolt", 2);  // instant
        addCard(Zone.LIBRARY, playerA, "Grizzly Bears", 2);   // creature
        addCard(Zone.LIBRARY, playerA, "Divination", 2);      // sorcery
        addCard(Zone.LIBRARY, playerA, "Sol Ring", 1);        // artifact
        addCard(Zone.LIBRARY, playerA, "Omniscience", 1);     // enchantment
        addCard(Zone.HAND, playerA, "Atraxa, Grand Unifier", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mana Confluence", 7);

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Atraxa, Grand Unifier", 1);

        Player a = currentGame.getPlayer(playerA.getId());
        // 至多每类别 1 张（6 类）→ 牌库至少剩 4 张；全拿 bug 会把牌库掏空。
        assertTrue("Atraxa ETB 后牌库应至少剩 4 张，实测=" + a.getLibrary().size(),
                a.getLibrary().size() >= 4);

        Map<CardType, Integer> counts = new EnumMap<>(CardType.class);
        for (Card c : a.getHand().getCards(currentGame)) {
            for (CardType t : c.getCardType(currentGame)) {
                counts.merge(t, 1, Integer::sum);
            }
        }
        counts.forEach((type, n) -> assertTrue(
                "ETB 拿牌每类别至多 1 张，但手牌里 " + type + " 有 " + n + " 张",
                n <= 1));
    }
}
