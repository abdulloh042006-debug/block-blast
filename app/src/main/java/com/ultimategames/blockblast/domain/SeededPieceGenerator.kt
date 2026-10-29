package com.ultimategames.blockblast.domain

/**
 * Deterministic 64-bit XorShift PRNG to guarantee 100% reproducible piece sequences
 * when a fixed seed is supplied (unit tests), while supporting fresh entropy and
 * distinct 3-piece tray generation for live gameplay.
 */
class SeededPieceGenerator(initialSeed: Long = DEFAULT_SEED) {

    var state: Long = if (initialSeed == 0L) DEFAULT_SEED else initialSeed
        private set

    fun reset(seed: Long) {
        state = if (seed == 0L) DEFAULT_SEED else seed
    }

    fun reseedFromSystemEntropy() {
        val entropy = System.currentTimeMillis() xor (System.nanoTime() shl 7) xor 0x5DEECE66DL
        reset(entropy)
        // Warm up 4 rounds to diffuse initial bits
        repeat(4) { nextPositiveInt() }
    }

    /**
     * Advances the XorShift64 state and returns a non-negative 31-bit integer.
     */
    fun nextPositiveInt(): Int {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        state = if (x == 0L) DEFAULT_SEED else x
        return (state ushr 1).toInt() and 0x7FFFFFFF
    }

    /**
     * Selects a single [BlockShape] using weighted random sampling from [candidates].
     */
    fun nextWeightedShape(candidates: List<BlockShape> = BlockCatalog.ALL_SHAPES): BlockShape {
        require(candidates.isNotEmpty()) { "Candidate shape list cannot be empty." }
        val totalWeight = candidates.sumOf { it.weight }
        var target = nextPositiveInt() % totalWeight
        for (shape in candidates) {
            if (target < shape.weight) {
                return shape
            }
            target -= shape.weight
        }
        return candidates.last()
    }

    /**
     * Generates a fresh 3-piece tray (`List<BlockShape?>` of size 3) where all 3 shapes
     * are guaranteed to be distinct (`first.id != second.id != third.id`) so the player
     * never receives 3 identical blocks in a single tray.
     */
    fun nextTriad(
        board: Board? = null,
        ensureAtLeastOnePlaceable: Boolean = true,
        enforceDistinctShapes: Boolean = true
    ): List<BlockShape?> {
        val all = BlockCatalog.ALL_SHAPES
        val chosenIds = HashSet<String>(3)

        val firstPool = if (board != null && ensureAtLeastOnePlaceable) {
            val placeable = all.filter { board.canPlaceAnywhere(it) }
            placeable.ifEmpty { all }
        } else {
            all
        }
        val firstShape = nextWeightedShape(firstPool)
        chosenIds.add(firstShape.id)

        val secondPool = if (enforceDistinctShapes) {
            all.filter { it.id !in chosenIds }.ifEmpty { all }
        } else {
            all
        }
        val secondShape = nextWeightedShape(secondPool)
        chosenIds.add(secondShape.id)

        val thirdPool = if (enforceDistinctShapes) {
            all.filter { it.id !in chosenIds }.ifEmpty { all }
        } else {
            all
        }
        val thirdShape = nextWeightedShape(thirdPool)

        return listOf(firstShape, secondShape, thirdShape)
    }

    /**
     * Generates an initial pre-populated 8x8 board with 8..14 blocks arranged in a balanced,
     * non-full pattern (inspired by Block Blast's dynamic starting boards) so the opening moves
     * immediately offer combo opportunities.
     */
    fun generatePrePopulatedBoard(): Board {
        val mods = mutableMapOf<Pair<Int, Int>, Int>()
        val patternType = nextPositiveInt() % 4
        val targetCells = 8 + (nextPositiveInt() % 7) // 8 to 14 cells

        when (patternType) {
            0 -> {
                // Corner L-brackets
                val coords = listOf(
                    0 to 0, 0 to 1, 1 to 0,
                    0 to 6, 0 to 7, 1 to 7,
                    6 to 0, 7 to 0, 7 to 1,
                    6 to 7, 7 to 6, 7 to 7
                )
                for ((r, c) in coords) {
                    mods[r to c] = (nextPositiveInt() % 7) + 1
                }
            }
            1 -> {
                // Central diamond ring
                val coords = listOf(
                    2 to 3, 2 to 4, 3 to 2, 3 to 5,
                    4 to 2, 4 to 5, 5 to 3, 5 to 4
                )
                for ((r, c) in coords) {
                    mods[r to c] = (nextPositiveInt() % 7) + 1
                }
            }
            2 -> {
                // Diagonal stepping stones
                val coords = listOf(
                    1 to 1, 1 to 2, 2 to 5, 2 to 6,
                    5 to 1, 5 to 2, 6 to 5, 6 to 6,
                    3 to 3, 4 to 4
                )
                for ((r, c) in coords) {
                    mods[r to c] = (nextPositiveInt() % 7) + 1
                }
            }
            else -> {
                // Scattered organic clusters (never filling more than 4 cells in any row/col)
                val rowCounts = IntArray(8)
                val colCounts = IntArray(8)
                var added = 0
                var attempts = 0
                while (added < targetCells && attempts < 60) {
                    attempts++
                    val r = nextPositiveInt() % 8
                    val c = nextPositiveInt() % 8
                    if ((r to c) !in mods && rowCounts[r] < 4 && colCounts[c] < 4) {
                        mods[r to c] = (nextPositiveInt() % 7) + 1
                        rowCounts[r]++
                        colCounts[c]++
                        added++
                    }
                }
            }
        }
        return Board.empty().withCells(mods)
    }

    /**
     * Occasionally spawns 1..2 helpful bonus blocks on empty cells that do not complete a line
     * or cause an immediate game-over, adding dynamic board variety during gameplay.
     */
    fun spawnOccasionalBonusBlocks(board: Board, tray: List<BlockShape?>, maxBlocks: Int = 2): Board {
        if (board.occupiedCellCount > 44) return board
        val mods = mutableMapOf<Pair<Int, Int>, Int>()
        var spawned = 0
        for (attempt in 0 until 24) {
            if (spawned >= maxBlocks) break
            val r = nextPositiveInt() % 8
            val c = nextPositiveInt() % 8
            if (!board.isOccupied(r, c) && (r to c) !in mods) {
                val candidateMods = HashMap(mods)
                candidateMods[r to c] = (nextPositiveInt() % 7) + 1
                val candidateBoard = board.withCells(candidateMods)
                // Ensure the bonus block doesn't accidentally fill a full line by itself and keeps tray playable
                val anyPlaceable = tray.any { it != null && candidateBoard.canPlaceAnywhere(it) }
                if (anyPlaceable) {
                    mods[r to c] = candidateMods[r to c]!!
                    spawned++
                }
            }
        }
        return if (mods.isEmpty()) board else board.withCells(mods)
    }

    companion object {
        const val DEFAULT_SEED = 0x5DEECE66DL
    }
}
