package org.mage.test.mtgeek.llm;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.cards.Card;
import mage.cards.Cards;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.target.Target;
import mage.target.TargetCard;
import org.mage.test.mtgeek.MTGeekSimplePlayer;
import org.mage.test.mtgeek.simple.DecisionLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * B2.x — LLM-backed player. Extends {@link MTGeekSimplePlayer} and overrides
 * <b>all six</b> decision hooks ({@code priority}, {@code selectAttackers},
 * {@code selectBlockers}, {@code chooseTarget}, {@code chooseMode},
 * {@code chooseUse}) to consult the remote MiniMax-backed decision endpoint
 * via {@link HttpDecisionClient}.
 *
 * <p>Each override follows the same pattern:</p>
 * <ol>
 *   <li>Trivial-skip — if there's no real choice (0 or 1 candidate, wrong
 *       turn/player, etc.) delegate to {@code super} so we don't burn LLM
 *       calls on forced moves.</li>
 *   <li>Enumerate candidates the same way SimpleAI does and build a
 *       numbered {@link HookOptions.Option} list.</li>
 *   <li>POST the request via {@link HttpDecisionClient#decide(Map)}.</li>
 *   <li>Decode {@code resp.choices} per hook semantics (subset / index /
 *       boolean); validate bounds — on any mismatch fall through to
 *       {@code super}.</li>
 *   <li>Execute the XMage action ({@code declareAttacker},
 *       {@code declareBlocker}, {@code target.addTarget}, …) and log via
 *       {@link DecisionLogger}.</li>
 *   <li>On {@link RuntimeException} from the client → log a
 *       {@code [LLM-FALLBACK|…]} line and delegate to {@code super}.</li>
 * </ol>
 */
public class MTGeekLLMPlayer extends MTGeekSimplePlayer {

    private final HttpDecisionClient client;

    public MTGeekLLMPlayer(String name, RangeOfInfluence range) {
        super(name, range);
        this.client = new HttpDecisionClient();
    }

    /** Test-only constructor: injectable client (for fake / fixture / local httpserver). */
    MTGeekLLMPlayer(String name, RangeOfInfluence range, HttpDecisionClient client) {
        super(name, range);
        this.client = client;
    }

    /** Copy constructor required by XMage game-copy mechanism. */
    protected MTGeekLLMPlayer(final MTGeekLLMPlayer player) {
        super(player);
        this.client = player.client;
    }

    @Override
    public MTGeekLLMPlayer copy() {
        return new MTGeekLLMPlayer(this);
    }

    // -----------------------------------------------------------------------
    // priority() — full LLM path with fallback to super (SimpleAI)
    // -----------------------------------------------------------------------

    @Override
    public boolean priority(Game game) {
        // Snapshot hand contents (delta only) for replay UI hand-popover.
        snapshotHandIfChanged(game);

        // Reset per-turn failed-source tracking when a new turn begins.
        // failedThisTurn (inherited from MTGeekSimplePlayer) prevents the LLM
        // from being re-presented with options that already failed activation
        // this turn — the prior bug had Lotus Petal cast successfully twice
        // then offered again 2 more times, generating duplicate "Cast Lotus
        // Petal" decision events with no actual cast following.
        int thisTurn = game.getTurnNum();
        if (thisTurn != failedTurn) {
            failedTurn = thisTurn;
            failedThisTurn.clear();
        }

        // === RESPOND WINDOW ===
        // Something is on the stack and we hold priority. The old guard
        // auto-passed here, which made counterspells (Force of Will / Daze /
        // Spell Pierce) ARCHITECTURALLY uncastable — Dimir could never
        // counter Show and Tell. Consult the LLM when the TOP stack object
        // belongs to the opponent and we have legal instant-speed plays.
        if (!game.getStack().isEmpty()) {
            mage.game.stack.StackObject top = game.getStack().getFirst();
            if (top != null && !getId().equals(top.getControllerId())) {
                return respondToStack(game);
            }
            // Our own spell on top — responding to ourselves is rare; pass.
            pass(game);
            return false;
        }

        // === SORCERY-SPEED WINDOW (own main phase, empty stack) ===
        if (!getId().equals(game.getActivePlayerId())) {
            pass(game);
            return false;
        }
        PhaseStep step = game.getTurnStepType();
        boolean isMain = step == PhaseStep.PRECOMBAT_MAIN || step == PhaseStep.POSTCOMBAT_MAIN;
        if (!isMain) {
            pass(game);
            return false;
        }

        // Same enumeration MTGeekSimplePlayer uses — keep the action list and the
        // options list index-aligned so the LLM's choice maps cleanly back.
        List<ActivatedAbility> playable = getPlayable(game, true);
        // Filter out ALL mana abilities (tap-mana + sacrifice-mana like Lotus
        // Petal). Mana empties at end of step, so activating without a spell
        // to cast is waste. XMage's auto-pay handles tap+sacrifice automatically
        // when actually casting a spell that needs the mana. SimpleAI keeps
        // sacrifice-mana because its value function judges when sacrificing
        // Lotus Petal is worth it; LLM lacks that nuance and tends to waste them.
        playable.removeIf(ab -> ab instanceof mage.abilities.mana.ActivatedManaAbilityImpl);
        playable.removeIf(ab -> failedThisTurn.contains(ab.getSourceId()));
        List<HookOptions.Option> options = HookOptions.buildPriorityOptions(playable, game);

        // Build request payload (state + hook + actor + turn), then append options.
        Map<String, Object> req = GameStateSerializer.buildRequest("priority", this, game);
        req.put("options", HookOptions.toRequestList(options));

        try {
            DecisionResponse resp = client.decide(req);
            int idx = (resp.choices != null && resp.choices.length > 0) ? resp.choices[0] : 0;
            if (idx < 0 || idx >= options.size()) {
                // LLM returned an out-of-range index — degrade gracefully to pass.
                DecisionLogger.logLLMFallback(game, "priority", getName(),
                        "LLM returned out-of-range index " + idx + " (options=" + options.size() + ")");
                return super.priority(game);
            }
            HookOptions.Option chosen = options.get(idx);
            DecisionLogger.logLLM(game, "priority", getName(), chosen.label, resp.rationale);
            return executePriorityChoice(idx, playable, game);
        } catch (RuntimeException e) {
            DecisionLogger.logLLMFallback(game, "priority", getName(), e.getMessage());
            return super.priority(game);
        }
    }

    /**
     * Execute the LLM-picked option. Index 0 = pass; index 1..N = the i-th
     * entry of the same {@code playable} list passed to
     * {@link HookOptions#buildPriorityOptions(List, Game)}.
     */
    private boolean executePriorityChoice(int idx, List<ActivatedAbility> playable, Game game) {
        if (idx == 0) {
            pass(game);
            return false;
        }
        ActivatedAbility chosen = playable.get(idx - 1);
        boolean ok = activateAbility(chosen, game);
        if (!ok) {
            // Don't offer this ability again this turn (e.g. Surgical Extraction
            // with no targets, Lotus Petal already sacrificed).
            failedThisTurn.add(chosen.getSourceId());
            // Activation failed (targeting impossible, cost can't be paid, …)
            // — surface the rollback so the replay doesn't keep an orphan
            // decision, then pass so the engine doesn't loop on the pick.
            mage.cards.Card src = game.getCard(chosen.getSourceId());
            String verb = chosen instanceof mage.abilities.SpellAbility ? "Cast" : "Activate";
            DecisionLogger.logCastAborted(game, getName(),
                    verb + " \"" + (src != null ? src.getName() : "?") + "\"",
                    "activation failed");
            pass(game);
            return false;
        }
        return true;
    }

    /**
     * Respond window: an opponent's spell/ability is on top of the stack and
     * we hold priority. Enumerate instant-speed legal plays (XMage's
     * getPlayable handles timing legality, including alternative costs like
     * Force of Will's exile-a-blue-card or Daze's return-an-Island); if any
     * exist, consult the LLM under the dedicated "respond" hook — the
     * request's state.stack carries "[B] Cast Show and Tell" style entries
     * so the model knows exactly what it would be countering.
     */
    private boolean respondToStack(Game game) {
        List<ActivatedAbility> playable = getPlayable(game, true);
        playable.removeIf(ab -> ab instanceof mage.abilities.mana.ActivatedManaAbilityImpl);
        playable.removeIf(ab -> failedThisTurn.contains(ab.getSourceId()));
        if (playable.isEmpty()) {
            pass(game);
            return false;
        }

        List<HookOptions.Option> options = HookOptions.buildPriorityOptions(playable, game);
        Map<String, Object> req = GameStateSerializer.buildRequest("respond", this, game);
        req.put("options", HookOptions.toRequestList(options));

        try {
            DecisionResponse resp = client.decide(req);
            int idx = (resp.choices != null && resp.choices.length > 0) ? resp.choices[0] : 0;
            if (idx < 0 || idx >= options.size()) {
                DecisionLogger.logLLMFallback(game, "respond", getName(),
                        "LLM returned out-of-range index " + idx + " (options=" + options.size() + ")");
                pass(game);
                return false;
            }
            HookOptions.Option chosen = options.get(idx);
            DecisionLogger.logLLM(game, "respond", getName(), chosen.label, resp.rationale);
            return executePriorityChoice(idx, playable, game);
        } catch (RuntimeException e) {
            // SimpleAI has no respond logic either — fallback is simply pass.
            DecisionLogger.logLLMFallback(game, "respond", getName(), e.getMessage());
            pass(game);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // selectAttackers() — LLM picks a subset of eligible attackers
    // -----------------------------------------------------------------------

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        snapshotHandIfChanged(game);
        // Only act when it's our turn to declare attackers.
        if (!attackingPlayerId.equals(getId())) return;
        Set<UUID> opps = game.getOpponents(getId());
        if (opps.isEmpty()) {
            super.selectAttackers(game, attackingPlayerId);
            return;
        }
        UUID oppId = opps.iterator().next();

        // Enumerate eligible attackers same way SimplePlayer does.
        List<Permanent> eligible = new ArrayList<>();
        for (Permanent p : game.getBattlefield().getAllActivePermanents(getId())) {
            if (!p.isCreature(game) || p.isTapped() || p.hasSummoningSickness()) continue;
            if (!p.canAttack(oppId, game)) continue;
            eligible.add(p);
        }
        if (eligible.isEmpty()) {
            super.selectAttackers(game, attackingPlayerId);
            return;
        }

        List<HookOptions.Option> options = HookOptions.buildSelectAttackersOptions(eligible, game);
        Map<String, Object> req = GameStateSerializer.buildRequest("selectAttackers", this, game);
        req.put("options", HookOptions.toRequestList(options));

        try {
            DecisionResponse resp = client.decide(req);
            if (resp.choices == null) {
                DecisionLogger.logLLMFallback(game, "selectAttackers", getName(),
                        "LLM returned null choices");
                super.selectAttackers(game, attackingPlayerId);
                return;
            }
            // Validate every chosen index — if anything is out of range, fall back.
            for (int idx : resp.choices) {
                if (idx < 0 || idx >= eligible.size()) {
                    DecisionLogger.logLLMFallback(game, "selectAttackers", getName(),
                            "out-of-range attacker index " + idx + " (eligible=" + eligible.size() + ")");
                    super.selectAttackers(game, attackingPlayerId);
                    return;
                }
            }
            // De-dupe — multiple identical indices are harmless but noisy.
            Set<Integer> uniq = new LinkedHashSet<>();
            for (int idx : resp.choices) uniq.add(idx);
            StringBuilder picked = new StringBuilder();
            int n = 0;
            for (int idx : uniq) {
                Permanent atk = eligible.get(idx);
                declareAttacker(atk.getId(), oppId, game, false);
                if (n > 0) picked.append(", ");
                picked.append(atk.getName());
                n++;
            }
            String summary = n + "/" + eligible.size() + " attack(s): "
                    + (n == 0 ? "(none)" : picked.toString());
            DecisionLogger.logLLM(game, "selectAttackers", getName(), summary, resp.rationale);
        } catch (RuntimeException e) {
            DecisionLogger.logLLMFallback(game, "selectAttackers", getName(), e.getMessage());
            super.selectAttackers(game, attackingPlayerId);
        }
    }

    // -----------------------------------------------------------------------
    // selectBlockers() — LLM assigns each blocker to one attacker (or -1)
    // -----------------------------------------------------------------------

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        snapshotHandIfChanged(game);
        if (!defendingPlayerId.equals(getId())) return;

        Set<UUID> attackerIds = game.getCombat().getAttackers();
        if (attackerIds == null || attackerIds.isEmpty()) {
            super.selectBlockers(source, game, defendingPlayerId);
            return;
        }

        // Stabilise attacker order — getAttackers() is a Set; we need an
        // ordered list so the LLM's k-th choice maps deterministically to
        // the same permanent every time.
        List<Permanent> attackers = new ArrayList<>();
        for (UUID id : attackerIds) {
            Permanent a = game.getPermanent(id);
            if (a != null) attackers.add(a);
        }
        // Eligible blockers — my untapped creatures not already blocking.
        List<Permanent> blockers = new ArrayList<>();
        for (Permanent p : game.getBattlefield().getAllActivePermanents(getId())) {
            if (!p.isCreature(game) || p.isTapped()) continue;
            if (p.getBlocking() != 0) continue;
            blockers.add(p);
        }
        if (attackers.isEmpty() || blockers.isEmpty()) {
            super.selectBlockers(source, game, defendingPlayerId);
            return;
        }

        List<HookOptions.Option> options = HookOptions.buildSelectBlockersOptions(blockers, attackers);
        Map<String, Object> req = GameStateSerializer.buildRequest("selectBlockers", this, game);
        req.put("options", HookOptions.toRequestList(options));

        try {
            DecisionResponse resp = client.decide(req);
            if (resp.choices == null || resp.choices.length != blockers.size()) {
                DecisionLogger.logLLMFallback(game, "selectBlockers", getName(),
                        "choices length " + (resp.choices == null ? "null" : resp.choices.length)
                                + " != blockers " + blockers.size());
                super.selectBlockers(source, game, defendingPlayerId);
                return;
            }
            // Validate each entry: -1 (no block) OR a valid attacker index.
            for (int v : resp.choices) {
                if (v != -1 && (v < 0 || v >= attackers.size())) {
                    DecisionLogger.logLLMFallback(game, "selectBlockers", getName(),
                            "blocker target " + v + " out of attacker range " + attackers.size());
                    super.selectBlockers(source, game, defendingPlayerId);
                    return;
                }
            }
            int assigned = 0;
            StringBuilder picked = new StringBuilder();
            for (int j = 0; j < blockers.size(); j++) {
                int v = resp.choices[j];
                if (v == -1) continue;
                Permanent blocker = blockers.get(j);
                Permanent atk = attackers.get(v);
                this.declareBlocker(getId(), blocker.getId(), atk.getId(), game);
                if (assigned > 0) picked.append(", ");
                picked.append(blocker.getName()).append("→").append(atk.getName());
                assigned++;
            }
            String summary = assigned + "/" + blockers.size() + " block(s): "
                    + (assigned == 0 ? "(none)" : picked.toString());
            DecisionLogger.logLLM(game, "selectBlockers", getName(), summary, resp.rationale);
        } catch (RuntimeException e) {
            DecisionLogger.logLLMFallback(game, "selectBlockers", getName(), e.getMessage());
            super.selectBlockers(source, game, defendingPlayerId);
        }
    }

    // -----------------------------------------------------------------------
    // chooseTarget() — LLM picks subset of candidate target indices
    // -----------------------------------------------------------------------

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        snapshotHandIfChanged(game);
        if (target != null) {
            snapshotLibraryViewOnce(game, target.possibleTargets(getId(), source, game), source);
        }
        if (target == null) return false;

        Set<UUID> possible = target.possibleTargets(getId(), source, game);
        if (possible == null || possible.isEmpty()) {
            return super.chooseTarget(outcome, target, source, game);
        }
        // No real choice — let SimpleAI's path handle the trivial cases
        // (it also handles the "single candidate, just add it" branch).
        if (possible.size() <= 1) {
            return super.chooseTarget(outcome, target, source, game);
        }

        // Materialise as ordered list so LLM choices[i] points to a stable UUID.
        List<UUID> candidates = new ArrayList<>(possible);

        int needed = target.getMaxNumberOfTargets();
        if (needed <= 0) needed = 1;

        List<HookOptions.Option> options = HookOptions.buildChooseTargetOptions(candidates, game, getId());
        Map<String, Object> req = GameStateSerializer.buildRequest("chooseTarget", this, game);
        Map<String, Object> choiceCtx = GameStateSerializer.buildChoiceContext(source, this, game);
        if (choiceCtx != null) req.put("choice_context", choiceCtx);
        req.put("options", HookOptions.toRequestList(options));

        try {
            DecisionResponse resp = client.decide(req);
            if (resp.choices == null || resp.choices.length == 0) {
                DecisionLogger.logLLMFallback(game, "chooseTarget", getName(),
                        "LLM returned empty/null choices");
                return super.chooseTarget(outcome, target, source, game);
            }
            // Validate each index — anything weird falls back to SimpleAI.
            for (int idx : resp.choices) {
                if (idx < 0 || idx >= candidates.size()) {
                    DecisionLogger.logLLMFallback(game, "chooseTarget", getName(),
                            "out-of-range target index " + idx + " (candidates=" + candidates.size() + ")");
                    return super.chooseTarget(outcome, target, source, game);
                }
            }
            // Cap at needed; de-dupe to avoid double-add on the same target.
            Set<Integer> uniq = new LinkedHashSet<>();
            for (int idx : resp.choices) {
                uniq.add(idx);
                if (uniq.size() >= needed) break;
            }
            int picked = 0;
            StringBuilder summary = new StringBuilder();
            for (int idx : uniq) {
                target.addTarget(candidates.get(idx), source, game);
                if (picked > 0) summary.append(", ");
                summary.append(options.get(idx).label);
                picked++;
            }
            DecisionLogger.logLLM(game, "chooseTarget", getName(),
                    picked + " target(s): " + (picked == 0 ? "(none)" : summary.toString()),
                    resp.rationale);
            return picked > 0;
        } catch (RuntimeException e) {
            DecisionLogger.logLLMFallback(game, "chooseTarget", getName(), e.getMessage());
            return super.chooseTarget(outcome, target, source, game);
        }
    }

    // -----------------------------------------------------------------------
    // chooseMode() — LLM picks one of the available modes
    // -----------------------------------------------------------------------

    @Override
    public Mode chooseMode(Modes modes, Ability source, Game game) {
        snapshotHandIfChanged(game);
        Collection<Mode> available = modes.getAvailableModes(source, game);
        if (available == null || available.isEmpty()) {
            return super.chooseMode(modes, source, game);
        }
        if (available.size() <= 1) {
            return super.chooseMode(modes, source, game);
        }

        // Stabilise mode ordering — getAvailableModes returns List<Mode> but
        // we accept Collection here so we always materialise our own list.
        List<Mode> ordered = new ArrayList<>(available);
        List<HookOptions.Option> options = HookOptions.buildChooseModeOptions(ordered);
        Map<String, Object> req = GameStateSerializer.buildRequest("chooseMode", this, game);
        Map<String, Object> choiceCtx = GameStateSerializer.buildChoiceContext(source, this, game);
        if (choiceCtx != null) req.put("choice_context", choiceCtx);
        req.put("options", HookOptions.toRequestList(options));

        try {
            DecisionResponse resp = client.decide(req);
            if (resp.choices == null || resp.choices.length == 0) {
                DecisionLogger.logLLMFallback(game, "chooseMode", getName(),
                        "LLM returned empty/null choices");
                return super.chooseMode(modes, source, game);
            }
            int idx = resp.choices[0];
            if (idx < 0 || idx >= ordered.size()) {
                DecisionLogger.logLLMFallback(game, "chooseMode", getName(),
                        "out-of-range mode index " + idx + " (modes=" + ordered.size() + ")");
                return super.chooseMode(modes, source, game);
            }
            Mode picked = ordered.get(idx);
            DecisionLogger.logLLM(game, "chooseMode", getName(),
                    "mode " + idx + ": " + picked.toString(), resp.rationale);
            return picked;
        } catch (RuntimeException e) {
            DecisionLogger.logLLMFallback(game, "chooseMode", getName(), e.getMessage());
            return super.chooseMode(modes, source, game);
        }
    }

    // -----------------------------------------------------------------------
    // chooseUse() — LLM picks Yes (1) or No (0)
    // -----------------------------------------------------------------------

    @Override
    public boolean chooseUse(Outcome outcome, String message, Ability source, Game game) {
        snapshotHandIfChanged(game);
        List<HookOptions.Option> options = HookOptions.buildChooseUseOptions(message);
        Map<String, Object> req = GameStateSerializer.buildRequest("chooseUse", this, game);
        Map<String, Object> choiceCtx = GameStateSerializer.buildChoiceContext(source, this, game);
        if (choiceCtx != null) req.put("choice_context", choiceCtx);
        req.put("options", HookOptions.toRequestList(options));

        try {
            DecisionResponse resp = client.decide(req);
            if (resp.choices == null || resp.choices.length == 0) {
                DecisionLogger.logLLMFallback(game, "chooseUse", getName(),
                        "LLM returned empty/null choices");
                return super.chooseUse(outcome, message, source, game);
            }
            int idx = resp.choices[0];
            if (idx != 0 && idx != 1) {
                DecisionLogger.logLLMFallback(game, "chooseUse", getName(),
                        "out-of-range yes/no index " + idx);
                return super.chooseUse(outcome, message, source, game);
            }
            boolean yes = idx == 1;
            DecisionLogger.logLLM(game, "chooseUse", getName(),
                    (yes ? "YES" : "NO") + " (\"" + message + "\")", resp.rationale);
            return yes;
        } catch (RuntimeException e) {
            DecisionLogger.logLLMFallback(game, "chooseUse", getName(), e.getMessage());
            return super.chooseUse(outcome, message, source, game);
        }
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage,
                             String trueText, String falseText, Ability source, Game game) {
        // Delegate to the 4-arg overload, same as MTGeekSimplePlayer does.
        return chooseUse(outcome, message, source, game);
    }

    // -----------------------------------------------------------------------
    // choose() — pick N cards from a Cards collection (e.g. Show and Tell hand).
    // Same signature as MTGeekSimplePlayer.choose; SimplePlayer uses
    // ValueFunction.scoreHandCardAsThreat. We route through LLM instead.
    // -----------------------------------------------------------------------

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target,
                          Ability source, Game game) {
        snapshotHandIfChanged(game);
        if (cards == null || cards.isEmpty()) return false;
        List<Card> candidates = new ArrayList<>(cards.getCards(game));
        if (candidates.size() <= 1) {
            return super.choose(outcome, cards, target, source, game);
        }
        // "up to N" targets (e.g. Atraxa's ETB has max = Integer.MAX_VALUE) are
        // legal with any pick count in [min, max] — validate against min, and
        // only cap the number of applied picks at max.
        int minNeeded = Math.max(0, target.getMinNumberOfTargets());
        int maxAllowed = target.getMaxNumberOfTargets();
        if (maxAllowed <= 0) maxAllowed = 1;

        List<HookOptions.Option> options = HookOptions.buildChooseFromHandOptions(candidates);
        Map<String, Object> req = GameStateSerializer.buildRequest("chooseFromHand", this, game);
        Map<String, Object> choiceCtx = GameStateSerializer.buildChoiceContext(source, this, game);
        if (choiceCtx != null) req.put("choice_context", choiceCtx);
        req.put("options", HookOptions.toRequestList(options));
        // Extra context: outcome category + number of picks needed
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) req.get("state");
        if (state != null) {
            state.put("outcome", outcome != null ? outcome.toString() : "Unknown");
            state.put("picks_min", minNeeded);
            state.put("picks_max", Math.min(maxAllowed, candidates.size()));
        }

        try {
            DecisionResponse resp = client.decide(req);
            if (resp.choices == null || resp.choices.length == 0) {
                DecisionLogger.logLLMFallback(game, "chooseFromHand", getName(),
                        "LLM returned empty choices");
                return super.choose(outcome, cards, target, source, game);
            }
            // Validate: all indices in range, no duplicates, length matches needed
            Set<Integer> seen = new LinkedHashSet<>();
            for (int idx : resp.choices) {
                if (idx < 0 || idx >= candidates.size() || !seen.add(idx)) {
                    DecisionLogger.logLLMFallback(game, "chooseFromHand", getName(),
                            "LLM returned invalid choice (idx=" + idx + ", size=" + candidates.size() + ")");
                    return super.choose(outcome, cards, target, source, game);
                }
            }
            // Trim to max if LLM over-returned; pad via super only when the
            // pick count is below the target's true minimum.
            if (seen.size() < minNeeded) {
                DecisionLogger.logLLMFallback(game, "chooseFromHand", getName(),
                        "LLM returned " + seen.size() + " picks, need at least " + minNeeded);
                return super.choose(outcome, cards, target, source, game);
            }
            java.util.UUID abilityControllerId = target.getAffectedAbilityControllerId(getId());
            int picked = 0;
            StringBuilder summary = new StringBuilder();
            for (int idx : seen) {
                if (picked >= maxAllowed) break;
                Card c = candidates.get(idx);
                // Re-check legality before each add: constrained targets (e.g.
                // Atraxa's one-per-card-type) shrink as picks accumulate.
                if (!target.possibleTargets(abilityControllerId, source, game, cards)
                        .contains(c.getId())) {
                    DecisionLogger.logLLMFallback(game, "chooseFromHand", getName(),
                            "LLM pick \"" + c.getName() + "\" not a legal target here");
                    target.clearChosen();
                    return super.choose(outcome, cards, target, source, game);
                }
                target.add(c.getId(), game);
                if (picked > 0) summary.append(", ");
                summary.append(c.getName());
                picked++;
            }
            DecisionLogger.logLLM(game, "chooseFromHand", getName(),
                    picked + " card(s): " + summary, resp.rationale);
            return picked > 0;
        } catch (RuntimeException e) {
            DecisionLogger.logLLMFallback(game, "chooseFromHand", getName(), e.getMessage());
            return super.choose(outcome, cards, target, source, game);
        }
    }
}
