package org.mage.test.mtgeek;

import mage.abilities.ActivatedAbility;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import org.mage.test.mtgeek.simple.DecisionLogger;
import org.mage.test.mtgeek.simple.ValueFunction;
import org.mage.test.mtgeek.simple.Weights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * B1' SimpleAI。值函数驱动的可解释 AI。
 * 决策方法在 Task 13-17 中逐个 override；当前骨架仅满足编译 + 端到端跑通。
 */
public class MTGeekSimplePlayer extends MTGeekBasePlayer {

    public MTGeekSimplePlayer(String name, RangeOfInfluence range) {
        super(name, range);
    }

    protected MTGeekSimplePlayer(final MTGeekSimplePlayer player) {
        super(player);
    }

    @Override
    public MTGeekSimplePlayer copy() {
        return new MTGeekSimplePlayer(this);
    }

    // -----------------------------------------------------------------------
    // priority(): enumerate → score → argmax + lethal short-circuit + legacy fallback
    // -----------------------------------------------------------------------

    @Override
    public boolean priority(Game game) {
        // 1) 非我方回合 / 栈非空 / 非主阶段 → pass
        if (!getId().equals(game.getActivePlayerId())) {
            pass(game);
            return false;
        }
        if (!game.getStack().isEmpty()) {
            pass(game);
            return false;
        }
        PhaseStep step = game.getTurnStepType(); // can be null — guard below
        boolean isMain = step == PhaseStep.PRECOMBAT_MAIN || step == PhaseStep.POSTCOMBAT_MAIN;
        if (!isMain) {
            pass(game);
            return false;
        }

        // 2) Lethal short-circuit: if we can deal lethal this turn, signal to pass
        //    now; selectAttackers() (Task 14) will commit the actual attackers.
        if (tryLethal(game)) {
            pass(game);
            return false;
        }

        // 3) Enumerate candidates: playable spells + lands from hand + pass
        List<Candidate> cands = new ArrayList<>();

        List<ActivatedAbility> playable = getPlayable(game, true);
        for (ActivatedAbility ab : playable) {
            Card src = game.getCard(ab.getSourceId());
            if (src == null) continue;
            double s = ValueFunction.scoreCastSpell(game, src, getId(), null);
            cands.add(new Candidate("cast", ab.getSourceId(), s, "Cast \"" + src.getName() + "\""));
        }

        if (canPlayLand()) {
            for (Card c : getHand().getCards(game)) {
                if (c.isLand(game)) {
                    double s = ValueFunction.scorePlayLand(game, c, getId());
                    cands.add(new Candidate("land", c.getId(), s, "Play land \"" + c.getName() + "\""));
                }
            }
        }

        double passScore = ValueFunction.scorePass(game, getId());
        cands.add(new Candidate("pass", null, passScore, "Pass"));

        // 4) argmax + deterministic tiebreak (type ordinal, then UUID lexicographic)
        cands.sort(Comparator
                .comparingDouble((Candidate c) -> -c.score)
                .thenComparingInt(c -> typeOrdinal(c.type))
                .thenComparing(c -> c.id == null ? "" : c.id.toString())
        );
        Candidate best = cands.get(0);
        Candidate runnerUp = cands.size() > 1 ? cands.get(1) : null;

        // 5) Legacy fallback: if best is pass with negative score, try land → cast
        if ("pass".equals(best.type) && best.score < 0) {
            Candidate fallback = legacyFallback(cands);
            if (fallback != null) {
                best = fallback;
            }
        }

        // 6) Log + execute
        DecisionLogger.log(game, "priority", best.desc, best.score,
                runnerUp == null ? null : runnerUp.desc,
                runnerUp == null ? 0.0 : runnerUp.score,
                cands.size());

        return executeAction(best, game, playable);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Immutable candidate for sorting. */
    private static final class Candidate {
        final String type;
        final UUID id;
        final double score;
        final String desc;

        Candidate(String type, UUID id, double score, String desc) {
            this.type = type;
            this.id = id;
            this.score = score;
            this.desc = desc;
        }
    }

    /**
     * Checks whether our untapped, non-summoning-sick creatures can deal lethal
     * to the first opponent. Logs when lethal is detected.
     */
    private boolean tryLethal(Game game) {
        UUID oppId = game.getOpponents(getId()).iterator().next();
        Player opp = game.getPlayer(oppId);
        if (opp == null) return false;

        int totalPower = 0;
        for (Permanent p : game.getBattlefield().getAllActivePermanents(getId())) {
            if (p.isCreature(game)
                    && !p.isTapped()
                    && !p.hasSummoningSickness()
                    && p.canAttack(oppId, game)) {
                totalPower += p.getPower().getValue();
            }
        }
        if (totalPower < opp.getLife()) return false;

        DecisionLogger.logOnly(game, "priority",
                "Lethal detected (total power " + totalPower + " >= life " + opp.getLife() + ")",
                Weights.LETHAL_BONUS);
        return true;
    }

    /**
     * Legacy fallback: land first, then any cast, otherwise null (stay with pass).
     */
    private Candidate legacyFallback(List<Candidate> cands) {
        for (Candidate c : cands) {
            if ("land".equals(c.type)) return c;
        }
        for (Candidate c : cands) {
            if ("cast".equals(c.type)) return c;
        }
        return null;
    }

    private int typeOrdinal(String t) {
        if ("cast".equals(t)) return 0;
        if ("land".equals(t)) return 1;
        return 2; // pass
    }

    private boolean executeAction(Candidate best, Game game, List<ActivatedAbility> playable) {
        switch (best.type) {
            case "cast":
                for (ActivatedAbility ab : playable) {
                    if (ab.getSourceId().equals(best.id)) {
                        return activateAbility(ab, game);
                    }
                }
                break;
            case "land":
                Card land = game.getCard(best.id);
                if (land != null) return playLand(land, game, false);
                break;
            case "pass":
            default:
                pass(game);
                return false;
        }
        pass(game);
        return false;
    }

    // -----------------------------------------------------------------------
    // selectAttackers(): full per-attacker scored implementation (Task 14).
    // Attacks all eligible creatures whose scoreAttacker > 0.
    // -----------------------------------------------------------------------

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        if (!attackingPlayerId.equals(getId())) return;
        java.util.Set<UUID> opps = game.getOpponents(getId());
        if (opps.isEmpty()) return;
        UUID oppId = opps.iterator().next();

        // Enumerate per-attacker candidates with scores.
        List<AtkCandidate> cands = new ArrayList<>();
        for (Permanent p : game.getBattlefield().getAllActivePermanents(getId())) {
            if (!p.isCreature(game) || p.isTapped() || p.hasSummoningSickness()) continue;
            if (!p.canAttack(oppId, game)) continue;
            double s = ValueFunction.scoreAttacker(game, p, oppId);
            cands.add(new AtkCandidate(p.getId(), oppId, s));
        }
        if (cands.isEmpty()) return;

        // Declare all attackers with score > 0.
        int picked = 0;
        double sumScore = 0;
        for (AtkCandidate c : cands) {
            if (c.score > 0) {
                declareAttacker(c.attackerId, c.defenderId, game, false);
                picked++;
                sumScore += c.score;
            }
        }
        if (picked > 0) {
            DecisionLogger.logOnly(game, "selectAttackers",
                    picked + " attacker(s) declared (of " + cands.size() + " eligible)",
                    sumScore);
        }
    }

    /** Immutable attacker candidate for selectAttackers(). */
    private static final class AtkCandidate {
        final UUID attackerId;
        final UUID defenderId;
        final double score;

        AtkCandidate(UUID attackerId, UUID defenderId, double score) {
            this.attackerId = attackerId;
            this.defenderId = defenderId;
            this.score = score;
        }
    }

    // -----------------------------------------------------------------------
    // selectBlockers(): per-attacker scored blocker assignment (Task 15).
    // For each incoming attacker, find the best eligible blocker via scoreBlock.
    // -----------------------------------------------------------------------

    @Override
    public void selectBlockers(mage.abilities.Ability source, Game game, UUID defendingPlayerId) {
        if (!defendingPlayerId.equals(getId())) return;

        java.util.Set<UUID> attackerIds = game.getCombat().getAttackers();
        if (attackerIds == null || attackerIds.isEmpty()) return;

        for (UUID attackerId : attackerIds) {
            Permanent attacker = game.getPermanent(attackerId);
            if (attacker == null) continue;

            Permanent bestBlocker = null;
            double bestScore = 0;
            for (Permanent candidate : game.getBattlefield().getAllActivePermanents(getId())) {
                if (!candidate.isCreature(game) || candidate.isTapped()) continue;
                if (candidate.getBlocking() != 0) continue; // already blocking another attacker
                double s = ValueFunction.scoreBlock(game, candidate, attacker);
                if (s > bestScore) {
                    bestScore = s;
                    bestBlocker = candidate;
                }
            }
            if (bestBlocker != null) {
                this.declareBlocker(getId(), bestBlocker.getId(), attackerId, game);
                DecisionLogger.logOnly(game, "selectBlockers",
                        bestBlocker.getName() + " blocks " + attacker.getName(), bestScore);
            }
        }
    }

    // chooseTarget() / chooseMode() / chooseUse() override 在 Task 16-17 添加
}
