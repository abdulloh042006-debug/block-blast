package com.ultimategames.blockblast.domain

/**
 * Handles the optional Rewarded Ad continuation ("Second Chance") mechanic when the player
 * reaches Game Over.
 *
 * Rules:
 * 1. Maximum 1 rewarded continuation per game session (`continuationsUsed < MAX_CONTINUATIONS_PER_GAME`).
 * 2. Granted ONLY after the official AdMob `OnUserEarnedRewardListener` callback fires.
 * 3. Clears the central 4x4 rescue zone (rows 2..5, cols 2..5) so the board has immediate breathing room,
 *    and deals 3 guaranteed-placeable compact shapes (`SINGLE_1X1`, `DOMINO_H`, `SQUARE_2X2`).
 */
object ContinuationEngine {

    const val MAX_CONTINUATIONS_PER_GAME = 1

    fun canOfferContinuation(state: GameState): Boolean {
        return state.isGameOver && state.continuationsUsed < MAX_CONTINUATIONS_PER_GAME
    }

    fun applyRewardedContinuation(state: GameState): GameState {
        require(state.isGameOver) { "Rewarded continuation is only valid when the game is over." }
        require(state.continuationsUsed < MAX_CONTINUATIONS_PER_GAME) {
            "Rewarded continuation limit ($MAX_CONTINUATIONS_PER_GAME) already reached for this game."
        }

        val clearedCenterMap = mutableMapOf<Pair<Int, Int>, Int>()
        for (r in 2..5) {
            for (c in 2..5) {
                clearedCenterMap[r to c] = 0
            }
        }
        val rescuedBoard = state.board.withCells(clearedCenterMap)
        val rescueTray: List<BlockShape?> = listOf(
            BlockCatalog.SINGLE_1X1,
            BlockCatalog.DOMINO_H,
            BlockCatalog.SQUARE_2X2
        )

        return state.copy(
            board = rescuedBoard,
            availablePieces = rescueTray,
            isGameOver = false,
            continuationsUsed = state.continuationsUsed + 1,
            isAnimating = false,
            actionSequence = state.actionSequence + 1L
        )
    }
}
