package com.ultimategames.blockblast.domain

/**
 * Detailed breakdown of points awarded for a single block placement.
 */
data class ScoreBreakdown(
    val placementPoints: Int,
    val lineClearBasePoints: Int,
    val comboBonusPoints: Int,
    val allClearBonusPoints: Int,
    val updatedComboStreak: Int,
    val updatedMovesSinceLastClear: Int
) {
    val totalPointsAwarded: Int
        get() = placementPoints + lineClearBasePoints + comboBonusPoints + allClearBonusPoints
}

/**
 * Deterministic scoring and combo engine modeled on Block Blast (`com.block.juggle`) mechanics:
 *
 * 1. Placement Points: +1 point per cell in the placed polyomino (`shape.cellCount`).
 * 2. Simultaneous Line Clear Base Points:
 *    - 1 line:  10 pts
 *    - 2 lines: 30 pts
 *    - 3 lines: 60 pts
 *    - 4 lines: 100 pts
 *    - 5 lines: 150 pts
 *    - N lines: 10 * N * (N + 1) / 2 (triangular scaling rewarding simultaneous row+col clears)
 * 3. Combo Streak Multiplier:
 *    - Clearing at least 1 line within [COMBO_GRACE_MOVES] (3 placements, i.e., one full tray triad)
 *      increments `comboStreak` by 1 (starting at 1 on the first clear, 2 on the second consecutive clear, etc.).
 *    - When `comboStreak >= 2`, `comboBonusPoints = (comboStreak - 1) * 10 * linesCleared`.
 *    - If [COMBO_GRACE_MOVES] placements occur without clearing a line, `comboStreak` resets to 0.
 * 4. All-Clear Board Bonus:
 *    - Leaving the entire 8x8 grid completely empty after a line clear awards an extra +300 points.
 */
object ScoreEngine {

    const val COMBO_GRACE_MOVES = 3
    const val ALL_CLEAR_BONUS = 300

    fun calculateLineBasePoints(linesCleared: Int): Int {
        if (linesCleared <= 0) return 0
        return 10 * linesCleared * (linesCleared + 1) / 2
    }

    fun evaluatePlacement(
        placedCellCount: Int,
        linesCleared: Int,
        isBoardAllClear: Boolean,
        currentComboStreak: Int,
        movesSinceLastClear: Int
    ): ScoreBreakdown {
        require(placedCellCount > 0) { "placedCellCount must be positive" }
        require(linesCleared >= 0) { "linesCleared cannot be negative" }

        val placementPoints = placedCellCount

        if (linesCleared == 0) {
            val nextMovesWithoutClear = movesSinceLastClear + 1
            val survivingStreak = if (nextMovesWithoutClear >= COMBO_GRACE_MOVES) 0 else currentComboStreak
            return ScoreBreakdown(
                placementPoints = placementPoints,
                lineClearBasePoints = 0,
                comboBonusPoints = 0,
                allClearBonusPoints = 0,
                updatedComboStreak = survivingStreak,
                updatedMovesSinceLastClear = nextMovesWithoutClear
            )
        }

        val nextComboStreak = currentComboStreak + 1
        val lineBase = calculateLineBasePoints(linesCleared)
        val comboBonus = if (nextComboStreak >= 2) {
            (nextComboStreak - 1) * 10 * linesCleared
        } else {
            0
        }
        val allClearBonus = if (isBoardAllClear) ALL_CLEAR_BONUS else 0

        return ScoreBreakdown(
            placementPoints = placementPoints,
            lineClearBasePoints = lineBase,
            comboBonusPoints = comboBonus,
            allClearBonusPoints = allClearBonus,
            updatedComboStreak = nextComboStreak,
            updatedMovesSinceLastClear = 0
        )
    }
}
