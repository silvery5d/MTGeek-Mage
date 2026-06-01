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
 * 最简自定义 player。目标是让 game 能正常推进到结束。
 * <p>
 * 决策策略（在 Task 13 中逐步 override）：
 * <ul>
 *   <li>能下地就下（每回合最多一次）</li>
 *   <li>主一施法选最便宜可付得起的咒语</li>
 *   <li>攻击阶段全部能攻击的角都派出去</li>
 *   <li>其它优先权窗口一律 PASS</li>
 *   <li>阻挡保留默认（ComputerPlayer 空实现，不阻挡）</li>
 * </ul>
 * <p>
 * 架构说明（Task 5 research）：
 * <ul>
 *   <li>TestPlayer 只接受三种内部 player 类型（TestComputerPlayer, TestComputerPlayer7,
 *       TestComputerPlayerMonteCarlo），且均为 final，无法继承。</li>
 *   <li>本类直接 extends ComputerPlayer，镜像 TestComputerPlayer 的代理模式，
 *       并在 TestPlayer 里增加第四个构造器重载来接受本类。</li>
 *   <li>ComputerPlayer.priority() 是空 pass；真实 AI 在 ComputerPlayer7。
 *       Task 13 会在此 override priority() 提供真实推进逻辑。</li>
 *   <li>copy() 必须 override 否则游戏复制会崩。</li>
 * </ul>
 *
 * @see org.mage.test.player.TestPlayer
 * @see mage.player.ai.ComputerPlayer
 */
public class MTGeekTrivialPlayer extends ComputerPlayer {

    private TestPlayer testPlayerLink;

    public MTGeekTrivialPlayer(String name, RangeOfInfluence range) {
        super(name, range);
    }

    /** Copy constructor required by XMage game-copy mechanism. */
    public MTGeekTrivialPlayer(final MTGeekTrivialPlayer player) {
        super(player);
    }

    public void setTestPlayerLink(TestPlayer testPlayerLink) {
        this.testPlayerLink = testPlayerLink;
    }

    @Override
    public MTGeekTrivialPlayer copy() {
        return new MTGeekTrivialPlayer(this);
    }

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

    // -----------------------------------------------------------------------
    // Task 13 will override priority() / selectAttackers() here.
    // -----------------------------------------------------------------------
}
