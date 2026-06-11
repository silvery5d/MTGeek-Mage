package org.mage.test.mtgeek;

import mage.abilities.Ability;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.ComputerPlayer;
import mage.target.Target;
import mage.target.TargetCard;
import org.mage.test.player.TestPlayer;

/**
 * B 路线所有自定义 player 的共同祖先。吸收样板（构造器/copy/setTestPlayerLink/delegate），
 * 让具体子类（MTGeekTrivialPlayer / MTGeekSimplePlayer）只关心决策逻辑。
 */
public abstract class MTGeekBasePlayer extends ComputerPlayer {

    protected TestPlayer testPlayerLink;
    /** Last hand signature (sorted card names joined) — used to suppress
     *  duplicate hand snapshots when nothing changed. */
    private transient String lastHandSig = null;

    public MTGeekBasePlayer(String name, RangeOfInfluence range) {
        super(name, range);
    }

    protected MTGeekBasePlayer(final MTGeekBasePlayer player) {
        super(player);
        // testPlayerLink 故意不复制——游戏 copy 出的实例不该共享活的 TestPlayer 引用
    }

    public void setTestPlayerLink(TestPlayer tp) {
        this.testPlayerLink = tp;
    }

    @Override
    public abstract MTGeekBasePlayer copy();

    // -----------------------------------------------------------------------
    // Delegate choose/target calls through TestPlayer when not in AI mode,
    // mirroring the pattern in TestComputerPlayer.
    // -----------------------------------------------------------------------

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game) {
        if (testPlayerLink == null || testPlayerLink.canChooseByComputer()) {
            return super.choose(outcome, target, source, game);
        } else {
            return testPlayerLink.choose(outcome, target, source, game);
        }
    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (testPlayerLink == null || testPlayerLink.canChooseByComputer()) {
            return super.choose(outcome, choice, game);
        } else {
            return testPlayerLink.choose(outcome, choice, game);
        }
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (testPlayerLink == null || testPlayerLink.canChooseByComputer()) {
            return super.choose(outcome, cards, target, source, game);
        } else {
            return testPlayerLink.choose(outcome, cards, target, source, game);
        }
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        if (testPlayerLink == null || testPlayerLink.canChooseByComputer()) {
            return super.chooseTarget(outcome, target, source, game);
        } else {
            return testPlayerLink.chooseTarget(outcome, target, source, game);
        }
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (testPlayerLink == null || testPlayerLink.canChooseByComputer()) {
            return super.chooseTarget(outcome, cards, target, source, game);
        } else {
            return testPlayerLink.chooseTarget(outcome, cards, target, source, game);
        }
    }

    @Override
    public boolean flipCoinResult(Game game) {
        if (testPlayerLink == null || testPlayerLink.canChooseByComputer()) {
            return super.flipCoinResult(game);
        } else {
            return testPlayerLink.flipCoinResult(game);
        }
    }

    @Override
    public int rollDieResult(int sides, Game game) {
        if (testPlayerLink == null || testPlayerLink.canChooseByComputer()) {
            return super.rollDieResult(sides, game);
        } else {
            return testPlayerLink.rollDieResult(sides, game);
        }
    }

    @Override
    public boolean isComputer() {
        if (testPlayerLink == null || testPlayerLink.canChooseByComputer()) {
            return super.isComputer();
        } else {
            return testPlayerLink.isComputer();
        }
    }

    /** Track which ability sources we've already emitted a library-view
     *  snapshot for, so Ponder's repeated chooseTarget calls don't spam. */
    private final transient java.util.Set<java.util.UUID> libraryViewEmittedFor = new java.util.HashSet<>();

    /**
     * XMage writes NO log line for draws (turn-draw, Brainstorm's draw-3, …) —
     * the act of drawing N is public info even though card identity is private.
     * Emit "[DRAW|name] n" so MatchRecorder can keep handSize/librarySize
     * accurate (raw-log audit found the reducer drifted +1 every turn).
     * Guard turnNum >= 1: skips the manual opening-hand drawCards(7) in test
     * setup, which initialState() already accounts for.
     */
    @Override
    public int drawCards(int num, mage.abilities.Ability source, Game game,
                         mage.game.events.GameEvent event) {
        int drawn = super.drawCards(num, source, game, event);
        if (drawn > 0 && game != null && game.getTurnNum() >= 1) {
            game.informPlayers(String.format("[DRAW|%s] %d", getName(), drawn));
        }
        return drawn;
    }

    /**
     * Intercept Ponder / Brainstorm / Augur etc. — XMage calls
     * {@code controller.lookAtCards(source, titleSuffix, cards, game)} to show
     * the player the top N cards privately. We tap that to emit a [LIBVIEW|...]
     * log line so the replay UI can show what the player saw.
     */
    @Override
    public void lookAtCards(mage.abilities.Ability source, String titleSuffix,
                            mage.cards.Cards cards, Game game) {
        super.lookAtCards(source, titleSuffix, cards, game);
        if (game == null || cards == null || cards.isEmpty()) return;
        java.util.List<String> names = new java.util.ArrayList<>();
        for (mage.cards.Card c : cards.getCards(game)) {
            if (c != null) names.add(c.getName());
        }
        if (names.isEmpty()) return;
        String srcName = "?";
        if (source != null && source.getSourceId() != null) {
            mage.cards.Card srcCard = game.getCard(source.getSourceId());
            if (srcCard != null) srcName = srcCard.getName();
        } else if (titleSuffix != null && !titleSuffix.isEmpty()) {
            srcName = titleSuffix;
        }
        org.mage.test.mtgeek.simple.DecisionLogger.logLibraryView(game, getName(), srcName, names);
    }

    /**
     * Snapshot the library top cards being offered to the player when the
     * chooseTarget hook fires for a "look at top N library cards" effect
     * (Ponder, Brainstorm, Augur of Bolas …). Only fires once per ability
     * source so we capture the FULL initial view, not the progressively
     * shrinking set as the player picks one at a time.
     *
     * @param possible UUIDs the player can choose from
     * @param source   the ability triggering this choice (Ponder etc.)
     */
    protected void snapshotLibraryViewOnce(Game game, java.util.Set<java.util.UUID> possible,
                                           mage.abilities.Ability source) {
        if (game == null || possible == null || possible.isEmpty() || source == null) return;
        java.util.UUID srcId = source.getSourceId();
        if (srcId == null || libraryViewEmittedFor.contains(srcId)) return;
        // Verify these are LIBRARY cards (don't snapshot for hand/battlefield targets).
        java.util.UUID firstId = possible.iterator().next();
        if (game.getState().getZone(firstId) != mage.constants.Zone.LIBRARY) return;
        java.util.List<String> names = new java.util.ArrayList<>();
        for (java.util.UUID id : possible) {
            mage.cards.Card c = game.getCard(id);
            if (c != null) names.add(c.getName());
        }
        if (names.isEmpty()) return;
        mage.cards.Card srcCard = game.getCard(srcId);
        String srcName = srcCard != null ? srcCard.getName() : "?";
        org.mage.test.mtgeek.simple.DecisionLogger.logLibraryView(game, getName(), srcName, names);
        libraryViewEmittedFor.add(srcId);
    }

    /**
     * Emit a [HAND|...] log line via DecisionLogger.logHand, but only if the
     * hand contents have changed since the last call. Sub-classes call this
     * at the start of priority(Game) so the replay shows hand contents at
     * every meaningful decision point without flooding the log.
     */
    protected void snapshotHandIfChanged(Game game) {
        if (game == null) return;
        java.util.List<String> names = new java.util.ArrayList<>();
        for (mage.cards.Card c : getHand().getCards(game)) {
            if (c != null) names.add(c.getName());
        }
        java.util.List<String> sorted = new java.util.ArrayList<>(names);
        java.util.Collections.sort(sorted);
        String sig = String.join("|", sorted);
        if (!sig.equals(lastHandSig)) {
            lastHandSig = sig;
            org.mage.test.mtgeek.simple.DecisionLogger.logHand(game, getName(), names);
        }
        // Piggyback the mana / library snapshots on the same call sites
        // (every hook entry) — each with its own dedup signature.
        snapshotManaIfChanged(game);
        snapshotLibraryIfChanged(game);
    }

    /** Last library signature for dedup. */
    private transient String lastLibrarySig = null;

    /**
     * Emit "[LIB|name] card1|card2|…" (top first) when the library's ordered
     * contents change. Hidden info during play, but the replay is a post-game
     * artifact — spectators get to see what was coming (and judge Ponder
     * decisions). Order read passively via getCardList(); never mutates.
     */
    protected void snapshotLibraryIfChanged(Game game) {
        if (game == null || getLibrary() == null) return;
        java.util.List<String> names = new java.util.ArrayList<>();
        for (java.util.UUID id : getLibrary().getCardList()) {
            mage.cards.Card c = game.getCard(id);
            if (c != null) names.add(c.getName());
        }
        String sig = String.join("|", names);
        if (sig.equals(lastLibrarySig)) return;
        lastLibrarySig = sig;
        org.mage.test.mtgeek.simple.DecisionLogger.logLibrary(game, getName(), names);
    }

    /** Last mana signature for dedup, same idea as lastHandSig. */
    private transient String lastManaSig = null;

    /**
     * Emit "[MANA|name] W0 U2 …" when the FLOATING mana pool changes.
     * Pool semantics (not "untapped potential"): empties at each step end per
     * MTG rules, which is what spectators expect — the earlier untapped-sources
     * heuristic confusingly persisted across phases. The LLM request still
     * uses GameStateSerializer.buildMana (potential) separately.
     */
    protected void snapshotManaIfChanged(Game game) {
        if (game == null || getManaPool() == null) return;
        mage.players.ManaPool pool = getManaPool();
        int w = pool.getWhite();
        int u = pool.getBlue();
        int b = pool.getBlack();
        int r = pool.getRed();
        int g = pool.getGreen();
        int c = pool.getColorless();
        String sig = w + "," + u + "," + b + "," + r + "," + g + "," + c;
        if (sig.equals(lastManaSig)) return;
        lastManaSig = sig;
        org.mage.test.mtgeek.simple.DecisionLogger.logMana(game, getName(), w, u, b, r, g, c);
    }
}
