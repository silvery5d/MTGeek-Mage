package org.mage.test.mtgeek;

import mage.constants.RangeOfInfluence;

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

    // priority() / selectAttackers() / selectBlockers() / chooseTarget() /
    // chooseMode() / chooseUse() override 在 Task 13-17 添加
}
