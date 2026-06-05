package org.mage.test.mtgeek.llm;

import mage.abilities.ActivatedAbility;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import org.mage.test.mtgeek.MTGeekSimplePlayer;
import org.mage.test.mtgeek.simple.DecisionLogger;

import java.util.List;
import java.util.Map;

/**
 * B2' T8 — LLM-backed player. Extends {@link MTGeekSimplePlayer}, overrides
 * only the {@code priority(Game)} hook to consult the remote MiniMax-backed
 * decision endpoint via {@link HttpDecisionClient}. All other hooks
 * ({@code selectAttackers}, {@code selectBlockers}, {@code chooseTarget},
 * {@code chooseMode}, {@code chooseUse}) are intentionally left to the parent
 * SimpleAI for this iteration — T9 end-to-end will tell us whether priority
 * alone is enough or whether more hooks need full LLM treatment.
 *
 * <p>Failure mode: if the HTTP client throws {@link DecisionFailedException}
 * (network down, 503, malformed JSON, …) we log a {@code [LLM-FALLBACK|…]}
 * line and fall through to {@code super.priority(game)} so the game still
 * advances using SimpleAI's value-function logic.</p>
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
        // Mirror SimpleAI's "only act on my main, stack empty" guard so we don't
        // burn LLM calls on windows where the only legal move is pass anyway.
        if (!getId().equals(game.getActivePlayerId())) {
            pass(game);
            return false;
        }
        if (!game.getStack().isEmpty()) {
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
            // Activation failed (targeting impossible, cost can't be paid, …)
            // — pass so the engine doesn't loop on the same broken pick.
            pass(game);
            return false;
        }
        return true;
    }
}
