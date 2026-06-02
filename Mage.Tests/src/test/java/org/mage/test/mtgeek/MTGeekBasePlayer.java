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
}
