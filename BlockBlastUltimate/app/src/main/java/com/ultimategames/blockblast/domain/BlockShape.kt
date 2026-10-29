package com.ultimategames.blockblast.domain

/**
 * Immutable 2D grid coordinate offset (row, col) relative to a shape's top-left origin (0, 0).
 */
data class CellOffset(val dRow: Int, val dCol: Int) {
    init {
        require(dRow >= 0 && dCol >= 0) {
            "CellOffset coordinates must be non-negative: ($dRow, $dCol)"
        }
    }
}

/**
 * Semantic color palette IDs for Block Blast polyomino shapes.
 * Each color ID maps to a high-contrast base fill, top-left highlight bevel, and bottom-right shadow bevel.
 */
enum class BlockColor(
    val id: Int,
    val fillHex: Int,
    val highlightHex: Int,
    val shadowHex: Int,
    val label: String
) {
    CYAN(1, 0xFF06B6D4.toInt(), 0xFF67E8F9.toInt(), 0xFF0E7490.toInt(), "Cyan"),
    AMBER(2, 0xFFF59E0B.toInt(), 0xFFFCD34D.toInt(), 0xFFB45309.toInt(), "Amber"),
    PURPLE(3, 0xFF8B5CF6.toInt(), 0xFFC4B5FD.toInt(), 0xFF5B21B6.toInt(), "Purple"),
    EMERALD(4, 0xFF10B981.toInt(), 0xFF6EE7B7.toInt(), 0xFF047857.toInt(), "Emerald"),
    ROSE(5, 0xFFF43F5E.toInt(), 0xFFFDA4AF.toInt(), 0xFFBE123C.toInt(), "Rose"),
    BLUE(6, 0xFF3B82F6.toInt(), 0xFF93C5FD.toInt(), 0xFF1D4ED8.toInt(), "Blue"),
    CORAL(7, 0xFFF97316.toInt(), 0xFFFDBA74.toInt(), 0xFFC2410C.toInt(), "Coral");

    companion object {
        fun fromId(id: Int): BlockColor = entries.firstOrNull { it.id == id } ?: CYAN
    }
}

/**
 * Immutable polyomino shape definition.
 * Block Blast pieces are non-rotatable once dealt; each orientation is an explicit entry in [BlockCatalog].
 */
data class BlockShape(
    val id: String,
    val cells: List<CellOffset>,
    val color: BlockColor,
    val weight: Int = 10
) {
    init {
        require(cells.isNotEmpty()) { "BlockShape '$id' must contain at least one cell." }
    }

    val cellCount: Int = cells.size
    val rowSpan: Int = (cells.maxOf { it.dRow } + 1)
    val colSpan: Int = (cells.maxOf { it.dCol } + 1)
}

/**
 * Authoritative catalog of 19 canonical Block Blast shapes across 1x1, dominoes, trominoes,
 * tetrominoes, pentominoes, squares (2x2, 3x3), L-corners, T-shapes, and diagonals.
 */
object BlockCatalog {

    val SINGLE_1X1 = BlockShape(
        id = "SINGLE_1X1",
        cells = listOf(CellOffset(0, 0)),
        color = BlockColor.AMBER,
        weight = 8
    )

    val DOMINO_H = BlockShape(
        id = "DOMINO_H",
        cells = listOf(CellOffset(0, 0), CellOffset(0, 1)),
        color = BlockColor.CYAN,
        weight = 12
    )

    val DOMINO_V = BlockShape(
        id = "DOMINO_V",
        cells = listOf(CellOffset(0, 0), CellOffset(1, 0)),
        color = BlockColor.CYAN,
        weight = 12
    )

    val TROMINO_H = BlockShape(
        id = "TROMINO_H",
        cells = listOf(CellOffset(0, 0), CellOffset(0, 1), CellOffset(0, 2)),
        color = BlockColor.BLUE,
        weight = 12
    )

    val TROMINO_V = BlockShape(
        id = "TROMINO_V",
        cells = listOf(CellOffset(0, 0), CellOffset(1, 0), CellOffset(2, 0)),
        color = BlockColor.BLUE,
        weight = 12
    )

    val TETROMINO_I_H = BlockShape(
        id = "TETROMINO_I_H",
        cells = listOf(CellOffset(0, 0), CellOffset(0, 1), CellOffset(0, 2), CellOffset(0, 3)),
        color = BlockColor.CYAN,
        weight = 9
    )

    val TETROMINO_I_V = BlockShape(
        id = "TETROMINO_I_V",
        cells = listOf(CellOffset(0, 0), CellOffset(1, 0), CellOffset(2, 0), CellOffset(3, 0)),
        color = BlockColor.CYAN,
        weight = 9
    )

    val PENTOMINO_I_H = BlockShape(
        id = "PENTOMINO_I_H",
        cells = listOf(
            CellOffset(0, 0), CellOffset(0, 1), CellOffset(0, 2), CellOffset(0, 3), CellOffset(0, 4)
        ),
        color = BlockColor.ROSE,
        weight = 6
    )

    val PENTOMINO_I_V = BlockShape(
        id = "PENTOMINO_I_V",
        cells = listOf(
            CellOffset(0, 0), CellOffset(1, 0), CellOffset(2, 0), CellOffset(3, 0), CellOffset(4, 0)
        ),
        color = BlockColor.ROSE,
        weight = 6
    )

    val SQUARE_2X2 = BlockShape(
        id = "SQUARE_2X2",
        cells = listOf(
            CellOffset(0, 0), CellOffset(0, 1),
            CellOffset(1, 0), CellOffset(1, 1)
        ),
        color = BlockColor.AMBER,
        weight = 14
    )

    val SQUARE_3X3 = BlockShape(
        id = "SQUARE_3X3",
        cells = listOf(
            CellOffset(0, 0), CellOffset(0, 1), CellOffset(0, 2),
            CellOffset(1, 0), CellOffset(1, 1), CellOffset(1, 2),
            CellOffset(2, 0), CellOffset(2, 1), CellOffset(2, 2)
        ),
        color = BlockColor.CORAL,
        weight = 6
    )

    val CORNER_2X2_TL = BlockShape(
        id = "CORNER_2X2_TL",
        cells = listOf(CellOffset(0, 0), CellOffset(0, 1), CellOffset(1, 0)),
        color = BlockColor.EMERALD,
        weight = 10
    )

    val CORNER_2X2_TR = BlockShape(
        id = "CORNER_2X2_TR",
        cells = listOf(CellOffset(0, 0), CellOffset(0, 1), CellOffset(1, 1)),
        color = BlockColor.EMERALD,
        weight = 10
    )

    val CORNER_2X2_BL = BlockShape(
        id = "CORNER_2X2_BL",
        cells = listOf(CellOffset(0, 0), CellOffset(1, 0), CellOffset(1, 1)),
        color = BlockColor.EMERALD,
        weight = 10
    )

    val CORNER_2X2_BR = BlockShape(
        id = "CORNER_2X2_BR",
        cells = listOf(CellOffset(0, 1), CellOffset(1, 0), CellOffset(1, 1)),
        color = BlockColor.EMERALD,
        weight = 10
    )

    val L_3X3_BL = BlockShape(
        id = "L_3X3_BL",
        cells = listOf(
            CellOffset(0, 0),
            CellOffset(1, 0),
            CellOffset(2, 0), CellOffset(2, 1), CellOffset(2, 2)
        ),
        color = BlockColor.CORAL,
        weight = 7
    )

    val L_3X3_TR = BlockShape(
        id = "L_3X3_TR",
        cells = listOf(
            CellOffset(0, 0), CellOffset(0, 1), CellOffset(0, 2),
            CellOffset(1, 2),
            CellOffset(2, 2)
        ),
        color = BlockColor.CORAL,
        weight = 7
    )

    val T_UP = BlockShape(
        id = "T_UP",
        cells = listOf(
            CellOffset(0, 1),
            CellOffset(1, 0), CellOffset(1, 1), CellOffset(1, 2)
        ),
        color = BlockColor.PURPLE,
        weight = 10
    )

    val T_DOWN = BlockShape(
        id = "T_DOWN",
        cells = listOf(
            CellOffset(0, 0), CellOffset(0, 1), CellOffset(0, 2),
            CellOffset(1, 1)
        ),
        color = BlockColor.PURPLE,
        weight = 10
    )

    val Z_H = BlockShape(
        id = "Z_H",
        cells = listOf(
            CellOffset(0, 0), CellOffset(0, 1),
            CellOffset(1, 1), CellOffset(1, 2)
        ),
        color = BlockColor.ROSE,
        weight = 8
    )

    val S_H = BlockShape(
        id = "S_H",
        cells = listOf(
            CellOffset(0, 1), CellOffset(0, 2),
            CellOffset(1, 0), CellOffset(1, 1)
        ),
        color = BlockColor.EMERALD,
        weight = 8
    )

    val DIAG_2 = BlockShape(
        id = "DIAG_2",
        cells = listOf(CellOffset(0, 0), CellOffset(1, 1)),
        color = BlockColor.PURPLE,
        weight = 6
    )

    val ALL_SHAPES: List<BlockShape> = listOf(
        SINGLE_1X1,
        DOMINO_H, DOMINO_V,
        TROMINO_H, TROMINO_V,
        TETROMINO_I_H, TETROMINO_I_V,
        PENTOMINO_I_H, PENTOMINO_I_V,
        SQUARE_2X2, SQUARE_3X3,
        CORNER_2X2_TL, CORNER_2X2_TR, CORNER_2X2_BL, CORNER_2X2_BR,
        L_3X3_BL, L_3X3_TR,
        T_UP, T_DOWN,
        Z_H, S_H,
        DIAG_2
    )

    private val shapeMap: Map<String, BlockShape> = ALL_SHAPES.associateBy { it.id }

    fun getById(id: String): BlockShape =
        shapeMap[id] ?: throw IllegalArgumentException("Unknown BlockShape id: $id")
}
