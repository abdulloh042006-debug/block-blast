package com.ultimategames.blockblast.domain

/**
 * Immutable snapshot of the complete Block Blast game state.
 */
data class GameState(
    val board: Board,
    val availablePieces: List<BlockShape?>,
    val score: Int,
    val highScore: Int,
    val comboStreak: Int,
    val movesSinceLastClear: Int,
    val totalMovesPlayed: Int,
    val totalLinesCleared: Int,
    val isGameOver: Boolean,
    val isNewRecordAchieved: Boolean,
    val continuationsUsed: Int,
    val isAnimating: Boolean,
    val actionSequence: Long,
    val prngState: Long
) {
    init {
        require(availablePieces.size == TRAY_SIZE) {
            "Tray must always have $TRAY_SIZE slots, got ${availablePieces.size}"
        }
    }

    val remainingPiecesInTray: Int
        get() = availablePieces.count { it != null }

    companion object {
        const val TRAY_SIZE = 3
    }
}

/**
 * Sealed result of attempting to place a piece from the tray onto the 8x8 board.
 */
sealed class MoveSubmissionResult {
    data class Success(
        val previousState: GameState,
        val updatedState: GameState,
        val placedShape: BlockShape,
        val slotIndex: Int,
        val anchorRow: Int,
        val anchorCol: Int,
        val boardOutcome: BoardPlacementOutcome,
        val scoreBreakdown: ScoreBreakdown,
        val trayReplenished: Boolean
    ) : MoveSubmissionResult()

    data class Rejected(
        val reason: RejectionReason,
        val state: GameState
    ) : MoveSubmissionResult()
}

enum class RejectionReason {
    GAME_ALREADY_OVER,
    ANIMATION_IN_PROGRESS,
    STALE_OR_DUPLICATE_ACTION_TOKEN,
    INVALID_SLOT_INDEX,
    SLOT_ALREADY_CONSUMED,
    OUT_OF_BOUNDS,
    CELL_OCCUPIED
}

/**
 * Represents a recommended or automated placement move chosen by the smart auto-placer.
 */
data class AutoMoveCandidate(
    val slotIndex: Int,
    val anchorRow: Int,
    val anchorCol: Int,
    val estimatedScoreGain: Int
)

/**
 * Authoritative, unit-testable Block Blast state machine.
 * Enforces:
 * - Strict 8x8 placement validation (bounds + occupied cell collision).
 * - Distinct 3-piece tray generation and fresh entropy on live new games.
 * - Optional starting board block pre-population (`prePopulateBoard`) and occasional bonus block drops.
 * - Smart AI Auto-Move evaluation (`findBestAutoMove`) for automatic block placement.
 * - Rapid-touch / duplicate-action protection via monotonic `actionSequence` tokens and `isAnimating` guard.
 * - Simultaneous horizontal row & vertical column clearing.
 * - Immediate Game Over evaluation after every single placement.
 */
class GameEngine(
    private val pieceGenerator: SeededPieceGenerator = SeededPieceGenerator()
) {
    var currentState: GameState = createNewGameState(
        initialHighScore = 0,
        seed = pieceGenerator.state,
        prePopulateBoard = false
    )
        private set

    /**
     * Starts a new game session.
     * - When [seed] is null (live gameplay), reseeds from system entropy so every new game deals
     *   a fresh, varied 3-piece tray.
     * - When [prePopulateBoard] is true, places an initial pattern of 8..14 blocks on the 8x8 board.
     */
    fun startNewGame(
        highScore: Int = currentState.highScore,
        seed: Long? = null,
        prePopulateBoard: Boolean = false
    ): GameState {
        if (seed != null) {
            pieceGenerator.reset(seed)
        } else {
            pieceGenerator.reseedFromSystemEntropy()
        }

        val initialBoard = if (prePopulateBoard) {
            pieceGenerator.generatePrePopulatedBoard()
        } else {
            Board.empty()
        }
        val initialTray = pieceGenerator.nextTriad(
            board = initialBoard,
            ensureAtLeastOnePlaceable = true,
            enforceDistinctShapes = true
        )
        val initialGameOver = !hasAnyValidMove(initialBoard, initialTray)

        currentState = GameState(
            board = initialBoard,
            availablePieces = initialTray,
            score = 0,
            highScore = highScore,
            comboStreak = 0,
            movesSinceLastClear = 0,
            totalMovesPlayed = 0,
            totalLinesCleared = 0,
            isGameOver = initialGameOver,
            isNewRecordAchieved = false,
            continuationsUsed = 0,
            isAnimating = false,
            actionSequence = currentState.actionSequence + 1L,
            prngState = pieceGenerator.state
        )
        return currentState
    }

    fun restoreState(savedState: GameState) {
        pieceGenerator.reset(savedState.prngState)
        currentState = savedState.copy(isAnimating = false)
    }

    fun setAnimating(animating: Boolean): GameState {
        currentState = currentState.copy(isAnimating = animating)
        return currentState
    }

    /**
     * Evaluates all available pieces in the tray and returns the highest-value valid placement
     * (prioritizing line clears and center-balanced placement) for Smart Auto-Place mode.
     */
    fun findBestAutoMove(): AutoMoveCandidate? {
        val state = currentState
        if (state.isGameOver || state.isAnimating) return null
        var bestCandidate: AutoMoveCandidate? = null
        var bestScore = Int.MIN_VALUE

        for (slotIndex in 0 until GameState.TRAY_SIZE) {
            val shape = state.availablePieces[slotIndex] ?: continue
            val validPositions = state.board.findAllValidPlacements(shape)
            for ((r, c) in validPositions) {
                val (clearedRows, clearedCols) = state.board.previewLineClears(shape, r, c)
                val lines = clearedRows.size + clearedCols.size
                val lineBonus = ScoreEngine.calculateLineBasePoints(lines) * 4
                val centerBias = 14 - (kotlin.math.abs(r - 3) + kotlin.math.abs(c - 3))
                val heuristic = shape.cellCount * 3 + lineBonus + centerBias
                if (heuristic > bestScore) {
                    bestScore = heuristic
                    bestCandidate = AutoMoveCandidate(
                        slotIndex = slotIndex,
                        anchorRow = r,
                        anchorCol = c,
                        estimatedScoreGain = shape.cellCount + ScoreEngine.calculateLineBasePoints(lines)
                    )
                }
            }
        }
        return bestCandidate
    }

    /**
     * Submits a move from tray [slotIndex] (0..2) to board coordinates ([anchorRow], [anchorCol]).
     */
    fun submitMove(
        slotIndex: Int,
        anchorRow: Int,
        anchorCol: Int,
        expectedActionSequence: Long? = null,
        allowOccasionalBonusBlocks: Boolean = false
    ): MoveSubmissionResult {
        val state = currentState

        if (state.isGameOver) {
            return MoveSubmissionResult.Rejected(RejectionReason.GAME_ALREADY_OVER, state)
        }
        if (state.isAnimating) {
            return MoveSubmissionResult.Rejected(RejectionReason.ANIMATION_IN_PROGRESS, state)
        }
        if (expectedActionSequence != null && expectedActionSequence != state.actionSequence) {
            return MoveSubmissionResult.Rejected(RejectionReason.STALE_OR_DUPLICATE_ACTION_TOKEN, state)
        }
        if (slotIndex !in 0 until GameState.TRAY_SIZE) {
            return MoveSubmissionResult.Rejected(RejectionReason.INVALID_SLOT_INDEX, state)
        }

        val shape = state.availablePieces[slotIndex]
            ?: return MoveSubmissionResult.Rejected(RejectionReason.SLOT_ALREADY_CONSUMED, state)

        for (offset in shape.cells) {
            val r = anchorRow + offset.dRow
            val c = anchorCol + offset.dCol
            if (!state.board.isInBounds(r, c)) {
                return MoveSubmissionResult.Rejected(RejectionReason.OUT_OF_BOUNDS, state)
            }
            if (state.board.isOccupied(r, c)) {
                return MoveSubmissionResult.Rejected(RejectionReason.CELL_OCCUPIED, state)
            }
        }

        val boardOutcome = state.board.placeAndClear(shape, anchorRow, anchorCol)
        val scoreBreakdown = ScoreEngine.evaluatePlacement(
            placedCellCount = shape.cellCount,
            linesCleared = boardOutcome.totalLinesCleared,
            isBoardAllClear = boardOutcome.isBoardAllClear && boardOutcome.totalLinesCleared > 0,
            currentComboStreak = state.comboStreak,
            movesSinceLastClear = state.movesSinceLastClear
        )

        val updatedScore = state.score + scoreBreakdown.totalPointsAwarded
        val updatedHighScore = maxOf(state.highScore, updatedScore)

        val updatedTray = state.availablePieces.toMutableList()
        updatedTray[slotIndex] = null
        val trayEmpty = updatedTray.all { it == null }

        var postMoveBoard = boardOutcome.updatedBoard
        val finalTray: List<BlockShape?> = if (trayEmpty) {
            val nextTriad = pieceGenerator.nextTriad(
                board = postMoveBoard,
                ensureAtLeastOnePlaceable = true,
                enforceDistinctShapes = true
            )
            // Occasionally spawn 1-2 bonus blocks on every 2nd tray refill when enabled
            if (allowOccasionalBonusBlocks && ((state.totalMovesPlayed + 1) % 6 == 0)) {
                postMoveBoard = pieceGenerator.spawnOccasionalBonusBlocks(postMoveBoard, nextTriad, maxBlocks = 2)
            }
            nextTriad
        } else {
            updatedTray
        }

        val isNowGameOver = !hasAnyValidMove(postMoveBoard, finalTray)

        val nextState = state.copy(
            board = postMoveBoard,
            availablePieces = finalTray,
            score = updatedScore,
            highScore = updatedHighScore,
            comboStreak = scoreBreakdown.updatedComboStreak,
            movesSinceLastClear = scoreBreakdown.updatedMovesSinceLastClear,
            totalMovesPlayed = state.totalMovesPlayed + 1,
            totalLinesCleared = state.totalLinesCleared + boardOutcome.totalLinesCleared,
            isGameOver = isNowGameOver,
            isNewRecordAchieved = state.isNewRecordAchieved || (updatedScore > state.highScore),
            actionSequence = state.actionSequence + 1L,
            prngState = pieceGenerator.state
        )

        currentState = nextState
        return MoveSubmissionResult.Success(
            previousState = state,
            updatedState = nextState,
            placedShape = shape,
            slotIndex = slotIndex,
            anchorRow = anchorRow,
            anchorCol = anchorCol,
            boardOutcome = boardOutcome,
            scoreBreakdown = scoreBreakdown,
            trayReplenished = trayEmpty
        )
    }

    fun applyRewardedAdContinuation(): Boolean {
        if (!ContinuationEngine.canOfferContinuation(currentState)) {
            return false
        }
        currentState = ContinuationEngine.applyRewardedContinuation(currentState)
        return true
    }

    fun setCustomScenarioForTesting(
        board: Board,
        tray: List<BlockShape?>,
        score: Int = 0,
        highScore: Int = 0,
        comboStreak: Int = 0,
        movesSinceLastClear: Int = 0
    ): GameState {
        require(tray.size == GameState.TRAY_SIZE) { "Tray must have 3 slots" }
        val isOver = !hasAnyValidMove(board, tray)
        currentState = currentState.copy(
            board = board,
            availablePieces = tray,
            score = score,
            highScore = maxOf(highScore, score),
            comboStreak = comboStreak,
            movesSinceLastClear = movesSinceLastClear,
            isGameOver = isOver,
            isAnimating = false,
            actionSequence = currentState.actionSequence + 1L
        )
        return currentState
    }

    fun hasAnyValidMove(board: Board, tray: List<BlockShape?>): Boolean {
        for (piece in tray) {
            if (piece != null && board.canPlaceAnywhere(piece)) {
                return true
            }
        }
        return false
    }

    private fun createNewGameState(
        initialHighScore: Int,
        seed: Long,
        prePopulateBoard: Boolean
    ): GameState {
        pieceGenerator.reset(seed)
        val initialBoard = if (prePopulateBoard) {
            pieceGenerator.generatePrePopulatedBoard()
        } else {
            Board.empty()
        }
        val initialTray = pieceGenerator.nextTriad(
            board = initialBoard,
            ensureAtLeastOnePlaceable = true,
            enforceDistinctShapes = true
        )
        return GameState(
            board = initialBoard,
            availablePieces = initialTray,
            score = 0,
            highScore = initialHighScore,
            comboStreak = 0,
            movesSinceLastClear = 0,
            totalMovesPlayed = 0,
            totalLinesCleared = 0,
            isGameOver = false,
            isNewRecordAchieved = false,
            continuationsUsed = 0,
            isAnimating = false,
            actionSequence = 1L,
            prngState = pieceGenerator.state
        )
    }
}
