package org.mage.test.mtgeek;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.cards.Card;
import mage.cards.Cards;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.Target;
import mage.target.TargetCard;
import mage.target.common.TargetCardInHand;
import org.mage.test.mtgeek.simple.DecisionLogger;
import org.mage.test.mtgeek.simple.ValueFunction;
import org.mage.test.mtgeek.simple.Weights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * B1' SimpleAI。值函数驱动的可解释 AI。
 * 决策方法在 Task 13-17 中逐个 override；当前骨架仅满足编译 + 端到端跑通。
 */
public class MTGeekSimplePlayer extends MTGeekBasePlayer {

    /**
     * Tracks (turnNum → Set of sourceIds) that have been tried and failed
     * (activateAbility returned false) during priority() this turn.
     * Cleared each time a new turn number is seen.
     * Prevents the loop: "pick Surgical Extraction → no target → fail → repeat".
     */
    protected int failedTurn = -1;
    protected final java.util.HashSet<UUID> failedThisTurn = new java.util.HashSet<>();

    public MTGeekSimplePlayer(String name, RangeOfInfluence range) {
        super(name, range);
    }

    protected MTGeekSimplePlayer(final MTGeekSimplePlayer player) {
        super(player);
        this.failedTurn = player.failedTurn;
        this.failedThisTurn.addAll(player.failedThisTurn);
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
        // Snapshot hand contents (delta only) for replay UI hand-popover.
        snapshotHandIfChanged(game);
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

        // Reset per-turn failure tracker when a new turn begins.
        int thisTurn = game.getTurnNum();
        if (thisTurn != failedTurn) {
            failedTurn = thisTurn;
            failedThisTurn.clear();
        }

        List<Candidate> cands = new ArrayList<>();

        List<ActivatedAbility> playable = getPlayable(game, true);
        // Filter 1: drop mana abilities — activating them adds mana but doesn't
        // advance priority (XMage re-invokes priority on same player immediately).
        // Exception: sacrifice-mana abilities (Lotus Petal 类) are one-shot and
        // cannot loop, so we keep them for consideration.
        playable.removeIf(ab -> {
            if (!(ab instanceof ActivatedManaAbilityImpl)) return false;
            boolean hasSacrificeCost = ab.getCosts().stream()
                .anyMatch(c -> c instanceof mage.abilities.costs.common.SacrificeSourceCost);
            return !hasSacrificeCost; // land tap-mana (no sac cost) → filter out
        });
        // Filter 2: drop spells/abilities whose sourceId has already failed
        // activateAbility() this turn (e.g. Surgical Extraction with no targets).
        playable.removeIf(ab -> failedThisTurn.contains(ab.getSourceId()));
        for (ActivatedAbility ab : playable) {
            // Skip PlayLandAbility — playing a land has its own dedicated
            // candidate path (canPlayLand loop below) with the correct
            // "Play land" verb and scorePlayLand scoring. Including it here
            // would double-count and produce misleading "Activate \"X\""
            // labels for what is actually a land drop.
            if (ab instanceof mage.abilities.PlayLandAbility) continue;
            Card src = game.getCard(ab.getSourceId());
            if (src == null) continue;
            double s = ValueFunction.scoreCastSpell(game, src, getId(), null);
            // Distinguish ability action type by ability class + source zone, so
            // fetchland sacrifice/search etc. don't get the misleading "Cast"
            // verb in the decision log.
            String verb;
            if (ab instanceof mage.abilities.SpellAbility) {
                verb = "Cast";
            } else {
                mage.constants.Zone z = game.getState().getZone(ab.getSourceId());
                if (z == mage.constants.Zone.BATTLEFIELD) verb = "Activate ability of";
                else verb = "Activate";
            }
            cands.add(new Candidate("cast", ab.getSourceId(), s, verb + " \"" + src.getName() + "\""));
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
        DecisionLogger.log(game, getName(), "priority", best.desc, best.score,
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

        DecisionLogger.logOnly(game, getName(), "priority",
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
                        boolean ok = activateAbility(ab, game);
                        if (!ok) {
                            // Record this sourceId as failed so we don't retry it
                            // in subsequent priority() calls this same turn.
                            failedThisTurn.add(best.id);
                        }
                        return ok;
                    }
                }
                // No matching ability found — record failure and fall through to pass.
                if (best.id != null) failedThisTurn.add(best.id);
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
            DecisionLogger.logOnly(game, getName(), "selectAttackers",
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
                DecisionLogger.logOnly(game, getName(), "selectBlockers",
                        bestBlocker.getName() + " blocks " + attacker.getName(), bestScore);
            }
        }
    }

    // ── Task 6 (Layer B): choose(Outcome, Cards, TargetCard, ...) — hand-card pick ──

    /**
     * Overrides the "choose from hand" decision used by effects like Show and Tell
     * ("put a permanent from your hand onto the battlefield").
     *
     * Strategy: score each card via ValueFunction.scoreHandCardAsThreat, then pick
     * the highest-scored card(s) for favorable outcomes (PutCreatureInPlay etc.)
     * or the lowest-scored card(s) for unfavorable outcomes (Discard etc.).
     */
    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target,
                          Ability source, Game game) {
        snapshotHandIfChanged(game);
        if (cards == null || cards.isEmpty()) return false;

        int needed = target.getMaxNumberOfTargets();
        if (needed <= 0) needed = 1;

        boolean pickMax = isOutcomeFavorable(outcome);
        java.util.List<Card> sorted = new java.util.ArrayList<>(cards.getCards(game));
        sorted.sort((a, b) -> {
            double sa = ValueFunction.scoreHandCardAsThreat(a, game);
            double sb = ValueFunction.scoreHandCardAsThreat(b, game);
            return pickMax ? Double.compare(sb, sa) : Double.compare(sa, sb);
        });

        int picked = 0;
        for (Card c : sorted) {
            if (picked >= needed) break;
            target.add(c.getId(), game);
            picked++;
        }
        if (!sorted.isEmpty()) {
            DecisionLogger.logOnly(game, getName(), "chooseFromHand",
                    (pickMax ? "PUT max" : "DROP min") + " " + picked + "/" + cards.size()
                            + " \"" + sorted.get(0).getName() + "\"",
                    ValueFunction.scoreHandCardAsThreat(sorted.get(0), game));
        }
        return picked > 0;
    }

    /**
     * Returns true when the outcome is favorable for us (we want to pick
     * the best/most-threatening card). Uses the enum's own isGood() flag as
     * the primary signal, but forces a few "anyTargetHasSameValue" outcomes
     * (PutCardInPlay, PlayForFree, Copy) that are always good for the caster.
     */
    private static boolean isOutcomeFavorable(Outcome outcome) {
        // Outcomes explicitly tagged as good for the targeting player
        if (outcome.isGood()) return true;
        // GainControl is tagged false (good for the controller of the effect,
        // bad for the target owner) — but when *we* choose from our hand it is
        // always favorable, so include it explicitly.
        return outcome == Outcome.GainControl;
    }

    // ── Task 16: chooseTarget (most-common overload) ──────────────────────────

    @Override
    public boolean chooseTarget(Outcome outcome, Target target,
                                Ability source, Game game) {
        snapshotHandIfChanged(game);
        if (target != null) {
            snapshotLibraryViewOnce(game, target.possibleTargets(getId(), source, game), source);
        }
        if (target == null) return false;

        Set<UUID> possible = target.possibleTargets(getId(), source, game);
        if (possible == null || possible.isEmpty()) {
            return !target.isRequired(source);
        }

        if (possible.size() == 1) {
            UUID only = possible.iterator().next();
            target.addTarget(only, source, game);
            return true;
        }

        // Detect hand-card targets (e.g. Show and Tell TargetCardInHand):
        // scoreTarget() can only handle permanents/players; for hand cards we
        // must use scoreHandCardAsThreat so we pick the biggest payoff.
        boolean isHandTarget = target instanceof TargetCardInHand
                || (possible.stream().anyMatch(id ->
                        game.getState().getZone(id) == Zone.HAND));

        UUID sourceId = source == null ? null : source.getSourceId();
        boolean isFriendly = outcome.isGood();
        UUID best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        if (isHandTarget) {
            // Pick the highest-threat card from hand (favorable outcome = put big thing down).
            // For unfavorable outcomes (Discard etc.) we want lowest threat,
            // but for all Put-onto-battlefield outcomes we always want max.
            boolean pickMax = isOutcomeFavorable(outcome);
            for (UUID id : possible) {
                Card c = game.getCard(id);
                if (c == null) continue;
                double s = ValueFunction.scoreHandCardAsThreat(c, game);
                double adjusted = pickMax ? s : -s; // invert for discard-like outcomes
                if (adjusted > bestScore) {
                    bestScore = adjusted;
                    best = id;
                }
            }
            if (best != null) {
                target.addTarget(best, source, game);
                Card bc = game.getCard(best);
                DecisionLogger.logOnly(game, getName(), "chooseTarget",
                        (pickMax ? "handMax" : "handMin") + " \""
                                + (bc != null ? bc.getName() : best.toString().substring(0, 8))
                                + "\", possible=" + possible.size(),
                        Math.abs(bestScore));
                return true;
            }
        }

        // Multiple candidates: score each and pick the best.
        for (UUID id : possible) {
            double s = ValueFunction.scoreTarget(game, sourceId, id, isFriendly);
            if (s > bestScore) {
                bestScore = s;
                best = id;
            }
        }

        if (best != null) {
            target.addTarget(best, source, game);
            DecisionLogger.logOnly(game, getName(), "chooseTarget",
                    "target=" + best.toString().substring(0, 8) + "..., possible=" + possible.size(),
                    bestScore);
            return true;
        }
        return false;
    }

    // ── Task 17: chooseMode + chooseUse ──────────────────────────────────────

    @Override
    public mage.abilities.Mode chooseMode(mage.abilities.Modes modes,
                                          Ability source, Game game) {
        java.util.Collection<mage.abilities.Mode> available = modes.getAvailableModes(source, game);
        if (available == null || available.isEmpty()) return null;
        if (available.size() == 1) return available.iterator().next();
        mage.abilities.Mode best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (mage.abilities.Mode m : available) {
            double s = 0;
            for (mage.abilities.effects.Effect e : m.getEffects()) {
                s += ValueFunction.scoreEffectPublic(e, source, game);
            }
            if (s > bestScore) {
                bestScore = s;
                best = m;
            }
        }
        DecisionLogger.logOnly(game, getName(), "chooseMode",
                "mode score=" + String.format("%.2f", bestScore) + " (of " + available.size() + ")",
                bestScore);
        return best;
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message,
                             Ability source, Game game) {
        UUID sourceId = source == null ? null : source.getSourceId();
        double s = ValueFunction.scoreYesNo(game, sourceId, message);
        boolean yes = s > 0;
        DecisionLogger.logOnly(game, getName(), "chooseUse",
                (yes ? "YES" : "NO") + " (\"" + message + "\")", s);
        return yes;
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage,
                             String trueText, String falseText,
                             Ability source, Game game) {
        return chooseUse(outcome, message, source, game);
    }
}
