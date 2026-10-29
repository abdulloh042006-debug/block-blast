package com.ultimategames.blockblast.domain

/**
 * Outcome of placing a [BlockShape] onto the [Board].
 */
data class BoardPlacementOutcome(
    val updatedBoard: Board,
    val placedCells: List<Pair<Int, Int>>,
    val clearedRows: List<Int>,
    val clearedCols: List<Int>,
    val clearedCells: Set<Pair<Int, Int>>,
    val isBoardAllClear: Boolean
) {
    val totalLinesCleared: Int get() = clearedRows.size + clearedCols.size
}

/**
 * Immutable 8x8 puzzle board representation.
 * Each cell stores 0 for empty or a positive [BlockColor.id] (1..7) for occupied.
 */
class Board private constructor(
    val size: Int,
    private val cells: IntArray
) {
    init {
        require(size == DEFAULT_SIZE) { "Block Blast board must be ${DEFAULT_SIZE}x${DEFAULT_SIZE}, got $size" }
        require(cells.size == size * size) { "Expected ${size * size} cells, got ${cells.size}" }
    }

    /**
     * Returns the color ID at [row], [col] (0 = empty, >0 = occupied).
     */
    fun getCell(row: Int, col: Int): Int {
        require(isInBounds(row, col)) { "Coordinates out of bounds: ($row, $col)" }
        return cells[row * size + col]
    }

    fun isOccupied(row: Int, col: Int): Boolean = getCell(row, col) != 0

    fun isInBounds(row: Int, col: Int): Boolean =
        row in 0 until size && col in 0 until size

    val occupiedCellCount: Int
        get() = cells.count { it != 0 }

    val isEmpty: Boolean
        get() = cells.all { it == 0 }

    /**
     * Checks whether [shape] can be legally placed with its top-left origin at ([anchorRow], [anchorCol]).
     */
    fun canPlace(shape: BlockShape, anchorRow: Int, anchorCol: Int): Boolean {
        for (offset in shape.cells) {
            val r = anchorRow + offset.dRow
            val c = anchorCol + offset.dCol
            if (!isInBounds(r, c)) return false
            if (cells[r * size + c] != 0) return false
        }
        return true
    }

    /**
     * Checks whether [shape] has at least one valid placement anywhere on the 8x8 board.
     */
    fun canPlaceAnywhere(shape: BlockShape): Boolean {
        val maxRow = size - shape.rowSpan
        val maxCol = size - shape.colSpan
        for (r in 0..maxRow) {
            for (c in 0..maxCol) {
                if (canPlace(shape, r, c)) return true
            }
        }
        return false
    }

    /**
     * Returns all valid (anchorRow, anchorCol) positions where [shape] can be placed.
     */
    fun findAllValidPlacements(shape: BlockShape): List<Pair<Int, Int>> {
        val valid = mutableListOf<Pair<Int, Int>>()
        val maxRow = size - shape.rowSpan
        val maxCol = size - shape.colSpan
        for (r in 0..maxRow) {
            for (c in 0..maxCol) {
                if (canPlace(shape, r, c)) {
                    valid.add(r to c)
                }
            }
        }
        return valid
    }

    /**
     * Previews which full rows and full columns WOULD be cleared if [shape] were placed at ([anchorRow], [anchorCol]).
     * Used by the UI renderer to highlight imminent line clears during drag hover.
     */
    fun previewLineClears(shape: BlockShape, anchorRow: Int, anchorCol: Int): Pair<List<Int>, List<Int>> {
        if (!canPlace(shape, anchorRow, anchorCol)) return emptyList<Int>() to emptyList()
        val temp = cells.copyOf()
        for (offset in shape.cells) {
            val r = anchorRow + offset.dRow
            val c = anchorCol + offset.dCol
            temp[r * size + c] = shape.color.id
        }
        val rows = findFullRows(temp)
        val cols = findFullCols(temp)
        return rows to cols
    }

    /**
     * Places [shape] at ([anchorRow], [anchorCol]) and simultaneously evaluates & clears
     * all full horizontal rows and vertical columns.
     *
     * Crucial simultaneous clear invariant:
     * Full rows and full columns are both evaluated on the post-placement board BEFORE any cell is zeroed out,
     * so intersecting row+column clears work deterministically without order bias.
     */
    fun placeAndClear(shape: BlockShape, anchorRow: Int, anchorCol: Int): BoardPlacementOutcome {
        require(canPlace(shape, anchorRow, anchorCol)) {
            "Illegal placement of ${shape.id} at ($anchorRow, $anchorCol)"
        }

        val nextCells = cells.copyOf()
        val placedCoords = ArrayList<Pair<Int, Int>>(shape.cellCount)

        for (offset in shape.cells) {
            val r = anchorRow + offset.dRow
            val c = anchorCol + offset.dCol
            nextCells[r * size + c] = shape.color.id
            placedCoords.add(r to c)
        }

        // Detect all completed horizontal rows and vertical columns simultaneously
        val fullRows = findFullRows(nextCells)
        val fullCols = findFullCols(nextCells)
        val clearedSet = LinkedHashSet<Pair<Int, Int>>()

        for (r in fullRows) {
            for (c in 0 until size) {
                clearedSet.add(r to c)
                nextCells[r * size + c] = 0
            }
        }

        for (c in fullCols) {
            for (r in 0 until size) {
                clearedSet.add(r to c)
                nextCells[r * size + c] = 0
            }
        }

        val updatedBoard = Board(size, nextCells)
        return BoardPlacementOutcome(
            updatedBoard = updatedBoard,
            placedCells = placedCoords,
            clearedRows = fullRows,
            clearedCols = fullCols,
            clearedCells = clearedSet,
            isBoardAllClear = updatedBoard.isEmpty
        )
    }

    /**
     * Returns a copy of the board with specific coordinates set to [colorId] (used for deterministic test setup
     * and rewarded continuation board clearing).
     */
    fun withCells(modifications: Map<Pair<Int, Int>, Int>): Board {
        val next = cells.copyOf()
        for ((coord, colorId) in modifications) {
            val (r, c) = coord
            require(isInBounds(r, c)) { "Out of bounds: ($r, $c)" }
            require(colorId >= 0) { "Invalid colorId: $colorId" }
            next[r * size + c] = colorId
        }
        return Board(size, next)
    }

    fun toFlatArray(): IntArray = cells.copyOf()

    private fun findFullRows(grid: IntArray): List<Int> {
        val full = mutableListOf<Int>()
        for (r in 0 until size) {
            var rowFull = true
            val rowOffset = r * size
            for (c in 0 until size) {
                if (grid[rowOffset + c] == 0) {
                    rowFull = false
                    break
                }
            }
            if (rowFull) full.add(r)
        }
        return full
    }

    private fun findFullCols(grid: IntArray): List<Int> {
        val full = mutableListOf<Int>()
        for (c in 0 until size) {
            var colFull = true
            for (r in 0 until size) {
                if (grid[r * size + c] == 0) {
                    colFull = false
                    break
                }
            }
            if (colFull) full.add(c)
        }
        return full
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Board) return false
        return size == other.size && cells.contentEquals(other.cells)
    }

    override fun hashCode(): Int = 31 * size + cells.contentHashCode()

    companion object {
        const val DEFAULT_SIZE = 8

        fun empty(size: Int = DEFAULT_SIZE): Board =
            Board(size, IntArray(size * size))

        fun fromFlatArray(array: IntArray, size: Int = DEFAULT_SIZE): Board =
            Board(size, array.copyOf())
    }
}
