package org.mage.test.mtgeek.llm;

import mage.constants.RangeOfInfluence;
import org.junit.Test;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * B2' T8 — unit-level coverage for {@link MTGeekLLMPlayer}.
 *
 * <p>End-to-end game integration is owned by T9
 * ({@code MTGeekLLMMatchTest}). This file pins down the smaller building
 * blocks the player relies on:</p>
 * <ul>
 *   <li>{@link HookOptions#buildPriorityOptions(List, mage.game.Game)} index
 *       layout (Pass is always 0, then 1..N for each ActivatedAbility).</li>
 *   <li>The {@code FakeHttpClient} pattern used by T9 — verifies that queued
 *       success responses and queued exceptions both surface correctly when
 *       {@code decide(...)} is invoked.</li>
 *   <li>{@link MTGeekLLMPlayer} can be instantiated (default + injectable
 *       client constructors) and produces a non-null {@code copy()}.</li>
 * </ul>
 */
public class MTGeekLLMPlayerTest {

    // -----------------------------------------------------------------------
    // FakeHttpClient — reused by T9 end-to-end tests.
    // -----------------------------------------------------------------------

    /**
     * Drop-in stand-in for {@link HttpDecisionClient}: each call to
     * {@link #decide(Map)} pops one queued element. A queued
     * {@code DecisionResponse} is returned; a queued {@link RuntimeException}
     * is thrown.
     */
    static class FakeHttpClient extends HttpDecisionClient {
        final Queue<Object> queue = new LinkedList<>();
        /** Every request passed to decide(), in order — lets tests assert payload contents. */
        final java.util.List<Map<String, Object>> requests = new java.util.ArrayList<>();

        FakeHttpClient() {
            // Endpoint + http + backoff are irrelevant — we override decide().
            super("http://unused.invalid", HttpClient.newHttpClient(), new int[0]);
        }

        void queueResponse(DecisionResponse r) { queue.add(r); }
        void queueException(RuntimeException e) { queue.add(e); }

        @Override
        public DecisionResponse decide(Map<String, Object> request) {
            requests.add(request);
            Object o = queue.poll();
            if (o == null) {
                throw new IllegalStateException("FakeHttpClient: no queued response/exception");
            }
            if (o instanceof RuntimeException) throw (RuntimeException) o;
            return (DecisionResponse) o;
        }
    }

    // -----------------------------------------------------------------------
    // HookOptions.buildPriorityOptions — Pass-at-0, no XMage Game required.
    // -----------------------------------------------------------------------

    @Test
    public void buildPriorityOptions_emptyPlayable_returnsSinglePassOption() {
        List<HookOptions.Option> opts = HookOptions.buildPriorityOptions(null, null);
        assertEquals("only the Pass option", 1, opts.size());
        HookOptions.Option pass = opts.get(0);
        assertEquals(0, pass.i);
        assertEquals("Pass priority", pass.label);
        assertNull("Pass has no card_name", pass.cardName);
    }

    // -----------------------------------------------------------------------
    // FakeHttpClient queue mechanism — the contract T9 will rely on.
    // -----------------------------------------------------------------------

    @Test
    public void fakeClient_queuedResponse_isReturnedByDecide() {
        FakeHttpClient fake = new FakeHttpClient();
        DecisionResponse expected = new DecisionResponse(new int[]{0}, "稳健 pass");
        fake.queueResponse(expected);

        DecisionResponse actual = fake.decide(new HashMap<>());
        assertSame("queued response should be returned verbatim", expected, actual);
    }

    @Test
    public void fakeClient_queuedException_isThrownByDecide() {
        FakeHttpClient fake = new FakeHttpClient();
        fake.queueException(new DecisionFailedException("simulated network failure"));

        try {
            fake.decide(new HashMap<>());
            fail("expected DecisionFailedException");
        } catch (DecisionFailedException e) {
            assertEquals("simulated network failure", e.getMessage());
        }
    }

    @Test
    public void fakeClient_emptyQueue_throwsIllegalState() {
        FakeHttpClient fake = new FakeHttpClient();
        try {
            fake.decide(new HashMap<>());
            fail("expected IllegalStateException on empty queue");
        } catch (IllegalStateException expected) {
            // OK
        }
    }

    // -----------------------------------------------------------------------
    // MTGeekLLMPlayer instantiation + copy.
    // -----------------------------------------------------------------------

    @Test
    public void player_canBeConstructedWithDefaultClient() {
        MTGeekLLMPlayer p = new MTGeekLLMPlayer("PlayerA", RangeOfInfluence.ONE);
        assertNotNull(p);
        assertEquals("PlayerA", p.getName());
    }

    @Test
    public void player_canBeConstructedWithInjectedClient_andCopies() {
        FakeHttpClient fake = new FakeHttpClient();
        MTGeekLLMPlayer p = new MTGeekLLMPlayer("PlayerA", RangeOfInfluence.ONE, fake);
        assertNotNull(p);

        MTGeekLLMPlayer copy = p.copy();
        assertNotNull("copy() must not return null", copy);
        assertEquals("PlayerA", copy.getName());
    }

    // -----------------------------------------------------------------------
    // B2.x — HookOptions builder coverage for the 5 newly-routed hooks.
    // Each builder is exercised without a real Game so failures are isolated
    // to label/index semantics. Integration with the live game engine is
    // exercised by MTGeekLLMMatchTest (manual, @Ignore by default).
    // -----------------------------------------------------------------------

    @Test
    public void buildChooseUseOptions_returnsTwoIndexedYesNo() {
        List<HookOptions.Option> opts = HookOptions.buildChooseUseOptions("Pay 2 life?");
        assertEquals("must be [No, Yes]", 2, opts.size());
        assertEquals(0, opts.get(0).i);
        assertTrue("No label contains prompt",
                opts.get(0).label.startsWith("No ") && opts.get(0).label.contains("Pay 2 life?"));
        assertEquals(1, opts.get(1).i);
        assertTrue("Yes label contains prompt",
                opts.get(1).label.startsWith("Yes ") && opts.get(1).label.contains("Pay 2 life?"));
    }

    @Test
    public void buildChooseUseOptions_nullPromptIsHandled() {
        List<HookOptions.Option> opts = HookOptions.buildChooseUseOptions(null);
        assertEquals(2, opts.size());
        // Just confirm labels are non-null + don't NPE.
        assertNotNull(opts.get(0).label);
        assertNotNull(opts.get(1).label);
    }

    @Test
    public void buildSelectAttackersOptions_nullInput_returnsEmpty() {
        List<HookOptions.Option> opts = HookOptions.buildSelectAttackersOptions(null, null);
        assertNotNull(opts);
        assertTrue("null attacker list → empty options", opts.isEmpty());
    }

    @Test
    public void buildSelectBlockersOptions_nullInput_returnsEmpty() {
        List<HookOptions.Option> opts = HookOptions.buildSelectBlockersOptions(null, null);
        assertNotNull(opts);
        assertTrue("null blocker list → empty options", opts.isEmpty());
    }

    @Test
    public void buildChooseTargetOptions_nullInput_returnsEmpty() {
        List<HookOptions.Option> opts = HookOptions.buildChooseTargetOptions(null, null);
        assertNotNull(opts);
        assertTrue("null candidates → empty options", opts.isEmpty());
    }

    @Test
    public void buildChooseModeOptions_nullInput_returnsEmpty() {
        List<HookOptions.Option> opts = HookOptions.buildChooseModeOptions(null);
        assertNotNull(opts);
        assertTrue("null modes → empty options", opts.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Player override structural coverage: each of the 5 newly-routed hooks
    // is verified to be overridden on MTGeekLLMPlayer itself (not just
    // inherited from MTGeekSimplePlayer), via reflection. This pins the
    // override contract so future refactors can't silently drop one.
    // -----------------------------------------------------------------------

    @Test
    public void player_overridesAll5NewHooks() throws Exception {
        Class<?> cls = MTGeekLLMPlayer.class;

        assertEquals("selectAttackers must be on MTGeekLLMPlayer",
                MTGeekLLMPlayer.class,
                cls.getDeclaredMethod("selectAttackers",
                        mage.game.Game.class, java.util.UUID.class).getDeclaringClass());

        assertEquals("selectBlockers must be on MTGeekLLMPlayer",
                MTGeekLLMPlayer.class,
                cls.getDeclaredMethod("selectBlockers",
                        mage.abilities.Ability.class, mage.game.Game.class, java.util.UUID.class)
                        .getDeclaringClass());

        assertEquals("chooseTarget(Outcome,Target,Ability,Game) must be on MTGeekLLMPlayer",
                MTGeekLLMPlayer.class,
                cls.getDeclaredMethod("chooseTarget",
                        mage.constants.Outcome.class, mage.target.Target.class,
                        mage.abilities.Ability.class, mage.game.Game.class)
                        .getDeclaringClass());

        assertEquals("chooseMode must be on MTGeekLLMPlayer",
                MTGeekLLMPlayer.class,
                cls.getDeclaredMethod("chooseMode",
                        mage.abilities.Modes.class, mage.abilities.Ability.class, mage.game.Game.class)
                        .getDeclaringClass());

        assertEquals("chooseUse(4-arg) must be on MTGeekLLMPlayer",
                MTGeekLLMPlayer.class,
                cls.getDeclaredMethod("chooseUse",
                        mage.constants.Outcome.class, String.class,
                        mage.abilities.Ability.class, mage.game.Game.class)
                        .getDeclaringClass());

        assertEquals("chooseUse(6-arg) must be on MTGeekLLMPlayer",
                MTGeekLLMPlayer.class,
                cls.getDeclaredMethod("chooseUse",
                        mage.constants.Outcome.class, String.class, String.class,
                        String.class, String.class,
                        mage.abilities.Ability.class, mage.game.Game.class)
                        .getDeclaringClass());

        assertEquals("choose(Outcome,Cards,TargetCard,Ability,Game) must be on MTGeekLLMPlayer",
                MTGeekLLMPlayer.class,
                cls.getDeclaredMethod("choose",
                        mage.constants.Outcome.class, mage.cards.Cards.class,
                        mage.target.TargetCard.class, mage.abilities.Ability.class,
                        mage.game.Game.class).getDeclaringClass());
    }

    @Test
    public void buildChooseFromHandOptions_nullCardsReturnsEmpty() {
        java.util.List<HookOptions.Option> opts = HookOptions.buildChooseFromHandOptions(null);
        assertNotNull(opts);
        assertEquals(0, opts.size());
    }
}
