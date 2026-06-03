package org.mage.test.mtgeek.simple;

/**
 * B1' SimpleAI 的数值权重表。MTG-tuned，参 Tians SimpleAI v2 WEIGHTS dict（同名常量优先保持值一致）。
 * 修改这些常量直接影响 SimpleAI 决策——慎重，应通过 Tournament 跑场对照变化。
 */
public final class Weights {
    // 1) 致命路径
    public static final double FACE_DAMAGE_PER_POINT      = 12.0;  // B1.1: 10.0 → 12.0
    public static final double LETHAL_BONUS               = 1000.0;

    // 2) 移除对方
    public static final double REMOVE_CREATURE_BASE       = 5.0;
    public static final double REMOVE_CREATURE_POWER      = 1.0;
    public static final double REMOVE_PLANESWALKER_BASE   = 8.0;
    public static final double REMOVE_PLANESWALKER_LOYALTY = 1.5;

    // 3) 自损
    public static final double SELF_KILL_BASE             = -3.0;
    public static final double SELF_KILL_POWER            = -1.0;

    // 4) 牌差 / 卡位面
    public static final double OWN_BOARD_POWER_PER        = 3.0;
    public static final double OWN_HAND_PER_CARD          = 1.5;
    public static final double OPP_HAND_DISCARD_PER_CARD  = 2.0;

    // 5) 资源
    public static final double UNSPENT_MANA_PENALTY       = -1.5;
    public static final double LIFE_LOSS_PER_POINT        = -0.5;  // B1.1: -1.0 → -0.5
    public static final double LIBRARY_SIZE_DANGER        = -5.0;

    // 6) MTG 特殊
    public static final double EXTRA_TURN_BASE            = 50.0;
    public static final double COUNTERSPELL_OPPORTUNISM   = 4.0;
    public static final double TUTOR_VALUE                = 5.0;  // B1.1: 3.0 → 5.0
    public static final double PUT_FROM_HAND_PAYOFF       = 8.0;  // B1.1 新增：Show and Tell 类 effect

    // 7) 调度
    public static final double CURVE_PLAY_HIGHEST_FIRST   = 0.5;
    public static final double COMBO_SYNERGY              = 2.0;

    private Weights() {}
}
