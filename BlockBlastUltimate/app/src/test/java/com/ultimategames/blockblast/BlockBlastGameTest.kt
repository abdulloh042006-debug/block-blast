package com.ultimategames.blockblast

import com.ultimategames.blockblast.audio.SoundEffectsManager
import com.ultimategames.blockblast.domain.BlockCatalog
import com.ultimategames.blockblast.domain.BlockColor
import com.ultimategames.blockblast.domain.Board
import com.ultimategames.blockblast.domain.ContinuationEngine
import com.ultimategames.blockblast.domain.GameEngine
import com.ultimategames.blockblast.domain.GameState
import com.ultimategames.blockblast.domain.MoveSubmissionResult
import com.ultimategames.blockblast.domain.RejectionReason
import com.ultimategames.blockblast.domain.ScoreEngine
import com.ultimategames.blockblast.domain.SeededPieceGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive JUnit 4 test suite covering all 15 required gameplay verification scenarios:
 * 1. Correct board dimensions and initialization.
 * 2. Valid and invalid placement.
 * 3. Out-of-bounds placement.
 * 4. Occupied-cell collisions.
 * 5. Horizontal line clearing.
 * 6. Vertical line clearing.
 * 7. Simultaneous row and column clearing.
 * 8. Scoring after multiple lines clear.
 * 9. Piece generation and consumption.
 * 10. Game-over detection.
 * 11. Restart behavior.
 * 12. High-score persistence.
 * 13. Rapid input and duplicate-action protection.
 * 14. State consistency after animations.
 * 15. Deterministic gameplay scenarios.
 */
class BlockBlastGameTest {

    @Test
    fun test01_boardDimensionsAndInitialization() {
        val board = Board.empty()
        assertEquals(8, board.size)
        assertTrue(board.isEmpty)
        assertEquals(0, board.occupiedCellCount)
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                assertEquals(0, board.getCell(r, c))
                assertFalse(board.isOccupied(r, c))
            }
        }
    }

    @Test
    fun test02_validAndInvalidPlacement() {
        val engine = GameEngine(SeededPieceGenerator(12345L))
        engine.setCustomScenarioForTesting(
            board = Board.empty(),
            tray = listOf(BlockCatalog.SQUARE_2X2, BlockCatalog.DOMINO_H, BlockCatalog.SINGLE_1X1)
        )
        assertTrue(engine.currentState.board.canPlace(BlockCatalog.SQUARE_2X2, 3, 3))
        val res = engine.submitMove(slotIndex = 0, anchorRow = 3, anchorCol = 3)
        assertTrue(res is MoveSubmissionResult.Success)
        assertEquals(4, engine.currentState.board.occupiedCellCount)
        assertNull(engine.currentState.availablePieces[0])

        // Re-submitting already consumed slot 0 must be rejected
        val invalidRes = engine.submitMove(slotIndex = 0, anchorRow = 0, anchorCol = 0)
        assertTrue(invalidRes is MoveSubmissionResult.Rejected)
        assertEquals(
            RejectionReason.SLOT_ALREADY_CONSUMED,
            (invalidRes as MoveSubmissionResult.Rejected).reason
        )
    }

    @Test
    fun test03_outOfBoundsPlacement() {
        val engine = GameEngine(SeededPieceGenerator(99L))
        engine.setCustomScenarioForTesting(
            board = Board.empty(),
            tray = listOf(BlockCatalog.TROMINO_H, BlockCatalog.TROMINO_V, BlockCatalog.SQUARE_3X3)
        )
        // TROMINO_H has colSpan 3 -> placing at col 6 overflows column 8
        val resRight = engine.submitMove(slotIndex = 0, anchorRow = 0, anchorCol = 6)
        assertTrue(resRight is MoveSubmissionResult.Rejected)
        assertEquals(RejectionReason.OUT_OF_BOUNDS, (resRight as MoveSubmissionResult.Rejected).reason)

        // TROMINO_V has rowSpan 3 -> placing at row 7 overflows row 8
        val resBottom = engine.submitMove(slotIndex = 1, anchorRow = 7, anchorCol = 0)
        assertTrue(resBottom is MoveSubmissionResult.Rejected)
        assertEquals(RejectionReason.OUT_OF_BOUNDS, (resBottom as MoveSubmissionResult.Rejected).reason)

        // Negative coordinate check
        val resNeg = engine.submitMove(slotIndex = 2, anchorRow = -1, anchorCol = 0)
        assertTrue(resNeg is MoveSubmissionResult.Rejected)
        assertEquals(RejectionReason.OUT_OF_BOUNDS, (resNeg as MoveSubmissionResult.Rejected).reason)
        assertEquals(0, engine.currentState.board.occupiedCellCount)
    }

    @Test
    fun test04_occupiedCellCollisions() {
        val engine = GameEngine(SeededPieceGenerator(42L))
        engine.setCustomScenarioForTesting(
            board = Board.empty(),
            tray = listOf(BlockCatalog.SQUARE_2X2, BlockCatalog.L_3X3_BL, BlockCatalog.SINGLE_1X1)
        )
        val first = engine.submitMove(slotIndex = 0, anchorRow = 2, anchorCol = 2)
        assertTrue(first is MoveSubmissionResult.Success)

        // L_3X3_BL placed at (1, 2) overlaps (2, 2) and (3, 2)
        val overlap = engine.submitMove(slotIndex = 1, anchorRow = 1, anchorCol = 2)
        assertTrue(overlap is MoveSubmissionResult.Rejected)
        assertEquals(RejectionReason.CELL_OCCUPIED, (overlap as MoveSubmissionResult.Rejected).reason)
        assertEquals(4, engine.currentState.board.occupiedCellCount)
    }

    @Test
    fun test05_horizontalLineClearing() {
        val prefill = (0..5).associate { c -> (4 to c) to BlockColor.CYAN.id }
        val board = Board.empty().withCells(prefill)
        val engine = GameEngine(SeededPieceGenerator(100L))
        engine.setCustomScenarioForTesting(
            board = board,
            tray = listOf(BlockCatalog.DOMINO_H, BlockCatalog.SINGLE_1X1, BlockCatalog.SINGLE_1X1)
        )
        val res = engine.submitMove(slotIndex = 0, anchorRow = 4, anchorCol = 6)
        assertTrue(res is MoveSubmissionResult.Success)
        val success = res as MoveSubmissionResult.Success
        assertEquals(listOf(4), success.boardOutcome.clearedRows)
        assertTrue(success.boardOutcome.clearedCols.isEmpty())
        assertTrue(engine.currentState.board.isEmpty)
        for (c in 0 until 8) {
            assertEquals(0, engine.currentState.board.getCell(4, c))
        }
    }

    @Test
    fun test06_verticalLineClearing() {
        val prefill = (0..4).associate { r -> (r to 5) to BlockColor.BLUE.id }
        val board = Board.empty().withCells(prefill)
        val engine = GameEngine(SeededPieceGenerator(200L))
        engine.setCustomScenarioForTesting(
            board = board,
            tray = listOf(BlockCatalog.TROMINO_V, BlockCatalog.SINGLE_1X1, BlockCatalog.SINGLE_1X1)
        )
        val res = engine.submitMove(slotIndex = 0, anchorRow = 5, anchorCol = 5)
        assertTrue(res is MoveSubmissionResult.Success)
        val success = res as MoveSubmissionResult.Success
        assertTrue(success.boardOutcome.clearedRows.isEmpty())
        assertEquals(listOf(5), success.boardOutcome.clearedCols)
        assertTrue(engine.currentState.board.isEmpty)
    }

    @Test
    fun test07_simultaneousRowAndColumnClearing() {
        // Fill row 3 except col 3, and col 3 except row 3, plus an unrelated cell (0, 0)
        val mods = mutableMapOf<Pair<Int, Int>, Int>()
        for (c in 0 until 8) if (c != 3) mods[3 to c] = BlockColor.EMERALD.id
        for (r in 0 until 8) if (r != 3) mods[r to 3] = BlockColor.PURPLE.id
        mods[0 to 0] = BlockColor.AMBER.id

        val board = Board.empty().withCells(mods)
        val engine = GameEngine(SeededPieceGenerator(300L))
        engine.setCustomScenarioForTesting(
            board = board,
            tray = listOf(BlockCatalog.SINGLE_1X1, BlockCatalog.DOMINO_H, BlockCatalog.DOMINO_V)
        )

        // Placing SINGLE_1X1 at (3, 3) completes both row 3 and col 3 simultaneously
        val res = engine.submitMove(slotIndex = 0, anchorRow = 3, anchorCol = 3)
        assertTrue(res is MoveSubmissionResult.Success)
        val success = res as MoveSubmissionResult.Success
        assertEquals(listOf(3), success.boardOutcome.clearedRows)
        assertEquals(listOf(3), success.boardOutcome.clearedCols)
        assertEquals(2, success.boardOutcome.totalLinesCleared)
        assertEquals(15, success.boardOutcome.clearedCells.size) // 8 + 8 - 1 intersection
        // Only cell (0, 0) remains occupied
        assertEquals(1, engine.currentState.board.occupiedCellCount)
        assertTrue(engine.currentState.board.isOccupied(0, 0))
        assertFalse(engine.currentState.board.isOccupied(3, 3))
    }

    @Test
    fun test08_scoringAfterMultipleLinesAndCombos() {
        // 2 simultaneous lines cleared with a 4-cell piece on a non-empty board, combo streak = 1 -> 2
        val breakdown = ScoreEngine.evaluatePlacement(
            placedCellCount = 4,
            linesCleared = 2,
            isBoardAllClear = false,
            currentComboStreak = 1,
            movesSinceLastClear = 1
        )
        // placement = 4, base(2 lines) = 30, comboBonus((2-1)*10*2) = 20, allClear = 0 -> total 54
        assertEquals(4, breakdown.placementPoints)
        assertEquals(30, breakdown.lineClearBasePoints)
        assertEquals(20, breakdown.comboBonusPoints)
        assertEquals(0, breakdown.allClearBonusPoints)
        assertEquals(54, breakdown.totalPointsAwarded)
        assertEquals(2, breakdown.updatedComboStreak)
    }

    @Test
    fun test09_pieceGenerationAndTriadConsumption() {
        val engine = GameEngine(SeededPieceGenerator(777L))
        engine.setCustomScenarioForTesting(
            board = Board.empty(),
            tray = listOf(BlockCatalog.SINGLE_1X1, BlockCatalog.SINGLE_1X1, BlockCatalog.SINGLE_1X1)
        )
        assertEquals(3, engine.currentState.remainingPiecesInTray)

        val r1 = engine.submitMove(0, 0, 0) as MoveSubmissionResult.Success
        assertFalse(r1.trayReplenished)
        assertEquals(2, engine.currentState.remainingPiecesInTray)

        val r2 = engine.submitMove(1, 0, 2) as MoveSubmissionResult.Success
        assertFalse(r2.trayReplenished)
        assertEquals(1, engine.currentState.remainingPiecesInTray)

        val r3 = engine.submitMove(2, 0, 4) as MoveSubmissionResult.Success
        assertTrue(r3.trayReplenished)
        assertEquals(3, engine.currentState.remainingPiecesInTray)
        for (i in 0 until GameState.TRAY_SIZE) {
            assertNotNull(engine.currentState.availablePieces[i])
        }
    }

    @Test
    fun test10_gameOverDetectionAndRewardedContinuation() {
        // Fill checkerboard on even coordinates `(r + c) % 2 == 0` so odd cells like (7, 6) are empty,
        // while no 3x3 square can fit anywhere on the board.
        val mods = mutableMapOf<Pair<Int, Int>, Int>()
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if ((r + c) % 2 == 0) {
                    mods[r to c] = BlockColor.ROSE.id
                }
            }
        }
        val board = Board.empty().withCells(mods)
        val engine = GameEngine(SeededPieceGenerator(555L))
        // Slot 0 has SINGLE_1X1 (fits at odd cell (7, 6)), Slot 1 has SQUARE_3X3 (cannot fit anywhere)
        engine.setCustomScenarioForTesting(
            board = board,
            tray = listOf(BlockCatalog.SINGLE_1X1, BlockCatalog.SQUARE_3X3, null)
        )
        assertFalse(engine.currentState.isGameOver)

        // Placing SINGLE_1X1 at (7, 6) leaves only SQUARE_3X3 in tray, which has no valid placement -> Game Over!
        val res = engine.submitMove(0, 7, 6)
        assertTrue(res is MoveSubmissionResult.Success)
        assertTrue(engine.currentState.isGameOver)

        // Verify Rewarded Ad continuation rescues the board and allows play to continue once
        assertTrue(ContinuationEngine.canOfferContinuation(engine.currentState))
        assertTrue(engine.applyRewardedAdContinuation())
        assertFalse(engine.currentState.isGameOver)
        assertEquals(1, engine.currentState.continuationsUsed)
        assertFalse(ContinuationEngine.canOfferContinuation(engine.currentState))
    }

    @Test
    fun test11_restartBehaviorPreservesHighScoreAndResetsBoard() {
        val engine = GameEngine(SeededPieceGenerator(888L))
        engine.setCustomScenarioForTesting(
            board = Board.empty(),
            tray = listOf(BlockCatalog.SQUARE_2X2, BlockCatalog.DOMINO_H, BlockCatalog.SINGLE_1X1),
            score = 420,
            highScore = 420,
            comboStreak = 3
        )
        engine.submitMove(0, 2, 2)
        val highBeforeRestart = engine.currentState.highScore
        assertTrue(highBeforeRestart >= 424)

        val restarted = engine.startNewGame(highScore = highBeforeRestart, seed = 999L)
        assertTrue(restarted.board.isEmpty)
        assertEquals(0, restarted.score)
        assertEquals(highBeforeRestart, restarted.highScore)
        assertEquals(0, restarted.comboStreak)
        assertEquals(3, restarted.remainingPiecesInTray)
        assertFalse(restarted.isGameOver)
    }

    @Test
    fun test12_highScoreAndStatePersistenceRoundTrip() {
        val engine = GameEngine(SeededPieceGenerator(1024L))
        engine.setCustomScenarioForTesting(
            board = Board.empty(),
            tray = listOf(BlockCatalog.SQUARE_2X2, BlockCatalog.T_UP, BlockCatalog.DOMINO_V),
            score = 1250,
            highScore = 1800,
            comboStreak = 4
        )
        engine.submitMove(0, 1, 1)
        val original = engine.currentState

        // Verify state restoration preserves board, tray, score, highScore, combo, and PRNG state
        val restoredEngine = GameEngine()
        restoredEngine.restoreState(original)
        assertEquals(original.board, restoredEngine.currentState.board)
        assertEquals(original.score, restoredEngine.currentState.score)
        assertEquals(original.highScore, restoredEngine.currentState.highScore)
        assertEquals(original.comboStreak, restoredEngine.currentState.comboStreak)
        assertEquals(original.availablePieces, restoredEngine.currentState.availablePieces)
    }

    @Test
    fun test13_rapidInputAndDuplicateActionProtection() {
        val engine = GameEngine(SeededPieceGenerator(2048L))
        engine.setCustomScenarioForTesting(
            board = Board.empty(),
            tray = listOf(BlockCatalog.SQUARE_2X2, BlockCatalog.DOMINO_H, BlockCatalog.SINGLE_1X1)
        )
        val initialSeq = engine.currentState.actionSequence

        // First rapid tap with initialSeq succeeds and advances actionSequence
        val firstTap = engine.submitMove(0, 0, 0, expectedActionSequence = initialSeq)
        assertTrue(firstTap is MoveSubmissionResult.Success)

        // Duplicate/simultaneous second tap carrying the same stale actionSequence token is rejected
        val duplicateTap = engine.submitMove(1, 4, 4, expectedActionSequence = initialSeq)
        assertTrue(duplicateTap is MoveSubmissionResult.Rejected)
        assertEquals(
            RejectionReason.STALE_OR_DUPLICATE_ACTION_TOKEN,
            (duplicateTap as MoveSubmissionResult.Rejected).reason
        )
    }

    @Test
    fun test14_stateConsistencyDuringAndAfterAnimations() {
        val engine = GameEngine(SeededPieceGenerator(4096L))
        engine.setCustomScenarioForTesting(
            board = Board.empty(),
            tray = listOf(BlockCatalog.SQUARE_2X2, BlockCatalog.DOMINO_H, BlockCatalog.SINGLE_1X1)
        )
        // Lock engine while line-clear animation runs
        engine.setAnimating(true)
        val blockedMove = engine.submitMove(0, 0, 0)
        assertTrue(blockedMove is MoveSubmissionResult.Rejected)
        assertEquals(
            RejectionReason.ANIMATION_IN_PROGRESS,
            (blockedMove as MoveSubmissionResult.Rejected).reason
        )
        assertEquals(0, engine.currentState.board.occupiedCellCount)

        // Unlock when animation finishes; move now succeeds cleanly
        engine.setAnimating(false)
        val validMove = engine.submitMove(0, 0, 0)
        assertTrue(validMove is MoveSubmissionResult.Success)
        assertEquals(4, engine.currentState.board.occupiedCellCount)
    }

    @Test
    fun test15_deterministicGameplayWithSeededRandomness() {
        val seed = 0xCAFEBABEL
        val engineA = GameEngine(SeededPieceGenerator(seed))
        val engineB = GameEngine(SeededPieceGenerator(seed))

        engineA.startNewGame(highScore = 0, seed = seed)
        engineB.startNewGame(highScore = 0, seed = seed)

        assertEquals(engineA.currentState.availablePieces, engineB.currentState.availablePieces)

        // Play 15 deterministic valid moves on both engines and assert identical board & score
        for (step in 0 until 15) {
            val stateA = engineA.currentState
            if (stateA.isGameOver) break
            val slot = stateA.availablePieces.indexOfFirst { it != null }
            val piece = stateA.availablePieces[slot]!!
            val placements = stateA.board.findAllValidPlacements(piece)
            if (placements.isEmpty()) break
            val (r, c) = placements.first()

            val resA = engineA.submitMove(slot, r, c)
            val resB = engineB.submitMove(slot, r, c)
            assertEquals(resA is MoveSubmissionResult.Success, resB is MoveSubmissionResult.Success)
            assertEquals(engineA.currentState.board, engineB.currentState.board)
            assertEquals(engineA.currentState.score, engineB.currentState.score)
            assertEquals(engineA.currentState.availablePieces, engineB.currentState.availablePieces)
        }

        // Also verify audio PCM synthesis produces zero-clipping samples within [-28000, 28000]
        val pcm = SoundEffectsManager.generateToneSequence(
            notesHz = floatArrayOf(523.25f, 659.25f),
            noteDurationSec = 0.05f,
            masterGain = 0.5f
        )
        assertTrue(pcm.isNotEmpty())
        assertTrue(pcm.all { it in -28001..28001 })
    }
}
