package com.ultimategames.blockblast.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.ultimategames.blockblast.domain.BlockShape
import com.ultimategames.blockblast.domain.Board
import com.ultimategames.blockblast.domain.CornerTileStyle
import com.ultimategames.blockblast.domain.GameSkin
import com.ultimategames.blockblast.domain.GameState
import com.ultimategames.blockblast.domain.SkinAnimationStyle
import com.ultimategames.blockblast.domain.SkinCatalog
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Custom hardware-accelerated Android View rendering the 8x8 Block Blast board, 3-piece tray,
 * 8 distinct corner/sticker tile geometries ([CornerTileStyle]), and 8 skin-specific particle
 * animation engines ([SkinAnimationStyle]) with zero per-frame object allocations in `onDraw()`.
 */
class BlockBlastBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface InteractionListener {
        fun onPiecePlacementRequested(slotIndex: Int, anchorRow: Int, anchorCol: Int, actionSequence: Long)
        fun onInvalidPlacementFeedback()
    }

    var interactionListener: InteractionListener? = null

    private var gameState: GameState? = null
    private var activeSkin: GameSkin = SkinCatalog.DEFAULT_SKIN

    // Pre-allocated geometry metrics & paths
    private val boardOuterRect = RectF()
    private val cellRect = RectF()
    private val innerBevelRect = RectF()
    private val stickerRect = RectF()
    private val diamondPath = Path()
    private val traySlotRects = Array(GameState.TRAY_SIZE) { RectF() }

    // Pre-allocated particle pool (zero allocation during animations)
    private class Particle {
        var active: Boolean = false
        var startX: Float = 0f
        var startY: Float = 0f
        var vx: Float = 0f
        var vy: Float = 0f
        var size: Float = 0f
        var color: Int = 0
        var phase: Float = 0f
    }

    private val particlePool = Array(96) { Particle() }

    private var cellSizePx: Float = 0f
    private var cellGapPx: Float = 0f
    private var boardPaddingPx: Float = 0f
    private var cornerRadiusPx: Float = 0f
    private var dragVerticalOffsetPx: Float = 0f

    // Pre-allocated Paints
    private val boardSurfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val boardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val emptyCellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val blockFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val blockHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val blockShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val stickerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val clearPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FCD34D")
        style = Paint.Style.FILL
    }
    private val trayCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val traySelectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val flashOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val floatingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FCD34D")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Active drag & accessible tap-selection state
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var draggingSlotIndex: Int = -1
    private var selectedTapSlotIndex: Int = -1
    private var dragTouchX: Float = 0f
    private var dragTouchY: Float = 0f
    private var dragStartX: Float = 0f
    private var dragStartY: Float = 0f
    private var isDraggingMoved: Boolean = false
    private var hoverAnchorRow: Int = -1
    private var hoverAnchorCol: Int = -1
    private var isHoverPlacementValid: Boolean = false
    private var previewClearedRows: List<Int> = emptyList()
    private var previewClearedCols: List<Int> = emptyList()

    // Animations
    private val flashingCells = HashSet<Pair<Int, Int>>()
    private var flashProgress: Float = 0f
    private var invalidShakeProgress: Float = 0f
    private var floatingBannerText: String? = null
    private var floatingBannerProgress: Float = 0f

    private var clearAnimator: ValueAnimator? = null
    private var shakeAnimator: ValueAnimator? = null
    private var bannerAnimator: ValueAnimator? = null

    val isPerformingGestureOrAnimation: Boolean
        get() = draggingSlotIndex != -1 || flashProgress > 0f

    init {
        applySkinColorsToPaints(activeSkin)
    }

    fun applySkin(skin: GameSkin) {
        this.activeSkin = skin
        applySkinColorsToPaints(skin)
        invalidate()
    }

    private fun applySkinColorsToPaints(skin: GameSkin) {
        boardSurfacePaint.color = skin.boardSurfaceHex
        boardStrokePaint.color = skin.boardBorderHex
        emptyCellPaint.color = skin.emptyCellHex
        trayCardPaint.color = skin.boardSurfaceHex
        traySelectedStrokePaint.color = skin.accentHex
        floatingTextPaint.color = skin.accentHex
    }

    fun bindState(state: GameState) {
        this.gameState = state
        if (selectedTapSlotIndex in 0 until GameState.TRAY_SIZE &&
            state.availablePieces[selectedTapSlotIndex] == null
        ) {
            selectedTapSlotIndex = -1
        }
        invalidate()
    }

    fun playLineClearAnimation(
        clearedCells: Set<Pair<Int, Int>>,
        bannerMessage: String?,
        onComplete: () -> Unit
    ) {
        clearAnimator?.cancel()
        flashingCells.clear()
        flashingCells.addAll(clearedCells)
        spawnSkinParticlesForCells(clearedCells)

        if (!bannerMessage.isNullOrBlank()) {
            triggerFloatingBanner(bannerMessage)
        }

        if (clearedCells.isEmpty()) {
            onComplete()
            return
        }

        clearAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                flashProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    flashProgress = 0f
                    flashingCells.clear()
                    for (p in particlePool) p.active = false
                    invalidate()
                    onComplete()
                }
            })
            start()
        }
    }

    private fun spawnSkinParticlesForCells(clearedCells: Set<Pair<Int, Int>>) {
        val gridStartX = boardOuterRect.left + boardPaddingPx
        val gridStartY = boardOuterRect.top + boardPaddingPx
        val step = cellSizePx + cellGapPx
        var poolIdx = 0
        for (p in particlePool) p.active = false

        for ((r, c) in clearedCells) {
            val cx = gridStartX + c * step + cellSizePx * 0.5f
            val cy = gridStartY + r * step + cellSizePx * 0.5f
            for (k in 0 until 2) {
                if (poolIdx >= particlePool.size) break
                val pt = particlePool[poolIdx++]
                pt.active = true
                pt.startX = cx
                pt.startY = cy
                val angle = ((r * 8 + c + k * 3) * 1.17f)
                val speed = cellSizePx * (1.2f + (k * 0.7f))
                pt.vx = cos(angle.toDouble()).toFloat() * speed
                pt.vy = sin(angle.toDouble()).toFloat() * speed
                pt.size = cellSizePx * 0.24f
                pt.color = activeSkin.getBlockFillHex(((r + c + k) % 7) + 1)
                pt.phase = angle
            }
        }
    }

    fun playInvalidPlacementShake() {
        shakeAnimator?.cancel()
        shakeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 260L
            addUpdateListener { anim ->
                invalidShakeProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun triggerFloatingBanner(text: String) {
        bannerAnimator?.cancel()
        floatingBannerText = text
        bannerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                floatingBannerProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    floatingBannerText = null
                    floatingBannerProgress = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateGeometry(w.toFloat(), h.toFloat())
    }

    private fun recalculateGeometry(w: Float, h: Float) {
        val horizontalMargin = w * 0.04f
        val maxBoardHeight = h * 0.68f
        val boardSide = min(w - 2f * horizontalMargin, maxBoardHeight).coerceAtLeast(120f)

        val boardLeft = (w - boardSide) / 2f
        val boardTop = h * 0.02f
        boardOuterRect.set(boardLeft, boardTop, boardLeft + boardSide, boardTop + boardSide)

        boardPaddingPx = boardSide * 0.028f
        cellGapPx = boardSide * 0.012f
        val innerSpan = boardSide - 2f * boardPaddingPx - (Board.DEFAULT_SIZE - 1) * cellGapPx
        cellSizePx = (innerSpan / Board.DEFAULT_SIZE).coerceAtLeast(8f)
        cornerRadiusPx = cellSizePx * 0.22f
        dragVerticalOffsetPx = cellSizePx * 2.1f
        floatingTextPaint.textSize = cellSizePx * 0.62f

        val trayTop = boardOuterRect.bottom + h * 0.035f
        val trayHeight = (h - trayTop - h * 0.025f).coerceAtLeast(cellSizePx * 2.4f)
        val slotGap = horizontalMargin * 0.6f
        val slotWidth = ((w - 2f * horizontalMargin - 2f * slotGap) / 3f).coerceAtLeast(40f)

        for (i in 0 until GameState.TRAY_SIZE) {
            val sLeft = horizontalMargin + i * (slotWidth + slotGap)
            traySlotRects[i].set(sLeft, trayTop, sLeft + slotWidth, trayTop + trayHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = gameState ?: return

        val shakeOffsetX = if (invalidShakeProgress > 0f) {
            (sin(invalidShakeProgress * Math.PI * 6.0) * cellSizePx * 0.18f * invalidShakeProgress).toFloat()
        } else {
            0f
        }

        canvas.save()
        canvas.translate(shakeOffsetX, 0f)

        // 1. Draw 8x8 board container
        val boardCorner = cornerRadiusPx * 1.6f
        canvas.drawRoundRect(boardOuterRect, boardCorner, boardCorner, boardSurfacePaint)
        canvas.drawRoundRect(boardOuterRect, boardCorner, boardCorner, boardStrokePaint)

        // 2. Draw 8x8 cells (empty slots + occupied blocks styled by activeSkin.cornerStyle)
        val gridStartX = boardOuterRect.left + boardPaddingPx
        val gridStartY = boardOuterRect.top + boardPaddingPx

        val emptyCellRadius = when (activeSkin.cornerStyle) {
            CornerTileStyle.SHARP_DIAMOND, CornerTileStyle.PIXEL_RETRO -> cornerRadiusPx * 0.25f
            CornerTileStyle.PILL_CUSHION -> cornerRadiusPx * 1.55f
            else -> cornerRadiusPx
        }

        for (r in 0 until Board.DEFAULT_SIZE) {
            val top = gridStartY + r * (cellSizePx + cellGapPx)
            for (c in 0 until Board.DEFAULT_SIZE) {
                val left = gridStartX + c * (cellSizePx + cellGapPx)
                cellRect.set(left, top, left + cellSizePx, top + cellSizePx)

                val colorId = state.board.getCell(r, c)
                if (colorId == 0) {
                    canvas.drawRoundRect(cellRect, emptyCellRadius, emptyCellRadius, emptyCellPaint)
                } else {
                    drawSkinnedBlockCell(canvas, cellRect, colorId, alpha = 255)
                }
            }
        }

        // 3. Draw ghost preview and imminent line-clear highlight
        val activePiece = getCurrentlyManipulatedPiece(state)
        if (activePiece != null && hoverAnchorRow >= 0 && hoverAnchorCol >= 0) {
            if (isHoverPlacementValid) {
                for (r in previewClearedRows) {
                    val top = gridStartY + r * (cellSizePx + cellGapPx)
                    cellRect.set(gridStartX, top, boardOuterRect.right - boardPaddingPx, top + cellSizePx)
                    canvas.drawRoundRect(cellRect, emptyCellRadius, emptyCellRadius, clearPreviewPaint)
                }
                for (c in previewClearedCols) {
                    val left = gridStartX + c * (cellSizePx + cellGapPx)
                    cellRect.set(left, gridStartY, left + cellSizePx, boardOuterRect.bottom - boardPaddingPx)
                    canvas.drawRoundRect(cellRect, emptyCellRadius, emptyCellRadius, clearPreviewPaint)
                }
                for (offset in activePiece.cells) {
                    val r = hoverAnchorRow + offset.dRow
                    val c = hoverAnchorCol + offset.dCol
                    val left = gridStartX + c * (cellSizePx + cellGapPx)
                    val top = gridStartY + r * (cellSizePx + cellGapPx)
                    cellRect.set(left, top, left + cellSizePx, top + cellSizePx)
                    drawSkinnedBlockCell(canvas, cellRect, activePiece.color.id, alpha = 120)
                }
            } else {
                ghostPaint.color = Color.argb(110, 239, 68, 68)
                for (offset in activePiece.cells) {
                    val r = hoverAnchorRow + offset.dRow
                    val c = hoverAnchorCol + offset.dCol
                    if (state.board.isInBounds(r, c)) {
                        val left = gridStartX + c * (cellSizePx + cellGapPx)
                        val top = gridStartY + r * (cellSizePx + cellGapPx)
                        cellRect.set(left, top, left + cellSizePx, top + cellSizePx)
                        canvas.drawRoundRect(cellRect, emptyCellRadius, emptyCellRadius, ghostPaint)
                    }
                }
            }
        }

        // 4. Draw skin-specific line-clear animation & particles
        if (flashProgress > 0f && flashingCells.isNotEmpty()) {
            val t = 1f - flashProgress
            flashOverlayPaint.color = activeSkin.accentHex
            flashOverlayPaint.alpha = (flashProgress * 220).toInt().coerceIn(0, 255)
            val inset = t * cellSizePx * 0.28f
            for ((r, c) in flashingCells) {
                val left = gridStartX + c * (cellSizePx + cellGapPx) + inset
                val top = gridStartY + r * (cellSizePx + cellGapPx) + inset
                val right = gridStartX + c * (cellSizePx + cellGapPx) + cellSizePx - inset
                val bottom = gridStartY + r * (cellSizePx + cellGapPx) + cellSizePx - inset
                cellRect.set(left, top, right, bottom)
                canvas.drawRoundRect(cellRect, emptyCellRadius, emptyCellRadius, flashOverlayPaint)
            }

            // Render skin-specific particle effects
            for (pt in particlePool) {
                if (!pt.active) continue
                particlePaint.color = pt.color
                particlePaint.alpha = (flashProgress * 255).toInt().coerceIn(0, 255)
                val px = pt.startX + pt.vx * t
                val py = when (activeSkin.animationStyle) {
                    SkinAnimationStyle.SOLAR_EMBERS, SkinAnimationStyle.OCEAN_BUBBLES ->
                        pt.startY - abs(pt.vy) * t - t * cellSizePx * 0.8f
                    SkinAnimationStyle.SAKURA_PETALS ->
                        pt.startY + abs(pt.vy) * 0.6f * t + sin((t * 6f + pt.phase).toDouble()).toFloat() * 8f
                    else -> pt.startY + pt.vy * t
                }
                val rSize = pt.size * flashProgress
                when (activeSkin.animationStyle) {
                    SkinAnimationStyle.OCEAN_BUBBLES, SkinAnimationStyle.GOLD_COINS, SkinAnimationStyle.SAKURA_PETALS -> {
                        canvas.drawCircle(px, py, rSize, particlePaint)
                    }
                    else -> {
                        stickerRect.set(px - rSize, py - rSize, px + rSize, py + rSize)
                        canvas.drawRect(stickerRect, particlePaint)
                    }
                }
            }
        }

        canvas.restore()

        // 5. Draw 3-piece tray slots
        for (i in 0 until GameState.TRAY_SIZE) {
            val slotRect = traySlotRects[i]
            canvas.drawRoundRect(slotRect, boardCorner * 0.8f, boardCorner * 0.8f, trayCardPaint)
            if (i == selectedTapSlotIndex && draggingSlotIndex == -1) {
                canvas.drawRoundRect(slotRect, boardCorner * 0.8f, boardCorner * 0.8f, traySelectedStrokePaint)
            }

            val piece = state.availablePieces[i]
            if (piece != null && i != draggingSlotIndex) {
                val canFit = state.board.canPlaceAnywhere(piece)
                val alpha = if (canFit) 255 else 95
                drawPieceCenteredInRect(canvas, piece, slotRect, alpha)
            }
        }

        // 6. Draw floating dragged piece above the player's finger
        if (draggingSlotIndex in 0 until GameState.TRAY_SIZE) {
            val draggedPiece = state.availablePieces[draggingSlotIndex]
            if (draggedPiece != null) {
                val totalW = draggedPiece.colSpan * cellSizePx + (draggedPiece.colSpan - 1) * cellGapPx
                val totalH = draggedPiece.rowSpan * cellSizePx + (draggedPiece.rowSpan - 1) * cellGapPx
                val originX = dragTouchX - totalW / 2f
                val originY = dragTouchY - dragVerticalOffsetPx - totalH / 2f

                for (offset in draggedPiece.cells) {
                    val left = originX + offset.dCol * (cellSizePx + cellGapPx)
                    val top = originY + offset.dRow * (cellSizePx + cellGapPx)
                    cellRect.set(left, top, left + cellSizePx, top + cellSizePx)
                    drawSkinnedBlockCell(canvas, cellRect, draggedPiece.color.id, alpha = 235)
                }
            }
        }

        // 7. Draw floating score/combo banner popup
        val banner = floatingBannerText
        if (banner != null && floatingBannerProgress > 0f) {
            val alpha = ((1f - floatingBannerProgress) * 255f).toInt().coerceIn(0, 255)
            floatingTextPaint.alpha = alpha
            val yPos = boardOuterRect.centerY() - floatingBannerProgress * cellSizePx * 1.8f
            canvas.drawText(banner, boardOuterRect.centerX(), yPos, floatingTextPaint)
        }
    }

    private fun drawPieceCenteredInRect(canvas: Canvas, piece: BlockShape, container: RectF, alpha: Int) {
        val maxMiniCell = min(
            (container.width() * 0.78f) / 5f,
            (container.height() * 0.78f) / 5f
        ).coerceAtMost(cellSizePx * 0.62f)
        val miniGap = maxMiniCell * 0.10f
        val totalW = piece.colSpan * maxMiniCell + (piece.colSpan - 1) * miniGap
        val totalH = piece.rowSpan * maxMiniCell + (piece.rowSpan - 1) * miniGap
        val startX = container.centerX() - totalW / 2f
        val startY = container.centerY() - totalH / 2f
        val miniRadius = maxMiniCell * 0.22f

        for (offset in piece.cells) {
            val left = startX + offset.dCol * (maxMiniCell + miniGap)
            val top = startY + offset.dRow * (maxMiniCell + miniGap)
            cellRect.set(left, top, left + maxMiniCell, top + maxMiniCell)
            drawSkinnedBlockCell(canvas, cellRect, piece.color.id, alpha, customRadius = miniRadius)
        }
    }

    /**
     * Renders a single block cell according to [activeSkin]'s color palette and [CornerTileStyle]
     * (including corner rivets, glossy cushion sticker dots, diamond facets, neon reticles, or royal double frames).
     */
    private fun drawSkinnedBlockCell(
        canvas: Canvas,
        rect: RectF,
        colorId: Int,
        alpha: Int,
        customRadius: Float = cornerRadiusPx
    ) {
        val baseFill = activeSkin.getBlockFillHex(colorId)
        val w = rect.width()
        val inset = w * 0.10f

        when (activeSkin.cornerStyle) {
            CornerTileStyle.ROUNDED_JEWEL -> {
                blockFillPaint.color = baseFill
                blockFillPaint.alpha = alpha
                canvas.drawRoundRect(rect, customRadius, customRadius, blockFillPaint)

                innerBevelRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
                blockHighlightPaint.color = Color.WHITE
                blockHighlightPaint.alpha = (alpha * 0.42f).toInt()
                canvas.drawRoundRect(innerBevelRect, customRadius * 0.75f, customRadius * 0.75f, blockHighlightPaint)
            }

            CornerTileStyle.PILL_CUSHION -> {
                val pillR = customRadius * 1.55f
                blockFillPaint.color = baseFill
                blockFillPaint.alpha = alpha
                canvas.drawRoundRect(rect, pillR, pillR, blockFillPaint)

                // Cute glossy corner sticker dot
                stickerFillPaint.color = Color.WHITE
                stickerFillPaint.alpha = (alpha * 0.65f).toInt()
                canvas.drawCircle(rect.left + w * 0.28f, rect.top + w * 0.28f, w * 0.11f, stickerFillPaint)
            }

            CornerTileStyle.NEON_WIREFRAME -> {
                blockFillPaint.color = activeSkin.bgHex
                blockFillPaint.alpha = (alpha * 0.90f).toInt()
                canvas.drawRoundRect(rect, customRadius * 0.6f, customRadius * 0.6f, blockFillPaint)

                blockHighlightPaint.color = baseFill
                blockHighlightPaint.alpha = alpha
                canvas.drawRoundRect(rect, customRadius * 0.6f, customRadius * 0.6f, blockHighlightPaint)

                innerBevelRect.set(rect.left + inset * 1.4f, rect.top + inset * 1.4f, rect.right - inset * 1.4f, rect.bottom - inset * 1.4f)
                stickerFillPaint.color = baseFill
                stickerFillPaint.alpha = (alpha * 0.35f).toInt()
                canvas.drawRoundRect(innerBevelRect, customRadius * 0.4f, customRadius * 0.4f, stickerFillPaint)
            }

            CornerTileStyle.SHARP_DIAMOND -> {
                val sharpR = customRadius * 0.25f
                blockFillPaint.color = baseFill
                blockFillPaint.alpha = alpha
                canvas.drawRoundRect(rect, sharpR, sharpR, blockFillPaint)

                // Inner diamond facet sticker
                diamondPath.reset()
                diamondPath.moveTo(rect.centerX(), rect.top + inset * 1.2f)
                diamondPath.lineTo(rect.right - inset * 1.2f, rect.centerY())
                diamondPath.lineTo(rect.centerX(), rect.bottom - inset * 1.2f)
                diamondPath.lineTo(rect.left + inset * 1.2f, rect.centerY())
                diamondPath.close()
                stickerFillPaint.color = Color.WHITE
                stickerFillPaint.alpha = (alpha * 0.30f).toInt()
                canvas.drawPath(diamondPath, stickerFillPaint)
            }

            CornerTileStyle.PIXEL_RETRO -> {
                blockFillPaint.color = baseFill
                blockFillPaint.alpha = alpha
                canvas.drawRect(rect, blockFillPaint)

                // 4 corner pixel studs
                val stud = w * 0.14f
                stickerFillPaint.color = Color.WHITE
                stickerFillPaint.alpha = (alpha * 0.55f).toInt()
                stickerRect.set(rect.left + inset * 0.6f, rect.top + inset * 0.6f, rect.left + inset * 0.6f + stud, rect.top + inset * 0.6f + stud)
                canvas.drawRect(stickerRect, stickerFillPaint)
            }

            CornerTileStyle.ROYAL_DOUBLE_FRAME -> {
                blockFillPaint.color = baseFill
                blockFillPaint.alpha = alpha
                canvas.drawRoundRect(rect, customRadius * 0.8f, customRadius * 0.8f, blockFillPaint)

                innerBevelRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
                blockHighlightPaint.color = Color.parseColor("#FDE047")
                blockHighlightPaint.alpha = (alpha * 0.78f).toInt()
                canvas.drawRoundRect(innerBevelRect, customRadius * 0.5f, customRadius * 0.5f, blockHighlightPaint)
            }

            CornerTileStyle.FROSTED_GLASS -> {
                blockFillPaint.color = baseFill
                blockFillPaint.alpha = (alpha * 0.82f).toInt()
                canvas.drawRoundRect(rect, customRadius * 1.1f, customRadius * 1.1f, blockFillPaint)

                blockHighlightPaint.color = Color.WHITE
                blockHighlightPaint.alpha = (alpha * 0.65f).toInt()
                canvas.drawRoundRect(rect, customRadius * 1.1f, customRadius * 1.1f, blockHighlightPaint)

                stickerFillPaint.color = Color.WHITE
                stickerFillPaint.alpha = (alpha * 0.45f).toInt()
                canvas.drawCircle(rect.right - w * 0.26f, rect.top + w * 0.26f, w * 0.09f, stickerFillPaint)
            }

            CornerTileStyle.CHAMFERED_MECHA -> {
                blockFillPaint.color = baseFill
                blockFillPaint.alpha = alpha
                canvas.drawRoundRect(rect, customRadius * 0.45f, customRadius * 0.45f, blockFillPaint)

                // Corner bolt stickers
                val boltR = w * 0.06f
                stickerFillPaint.color = Color.parseColor("#0F172A")
                stickerFillPaint.alpha = (alpha * 0.65f).toInt()
                canvas.drawCircle(rect.left + w * 0.20f, rect.top + w * 0.20f, boltR, stickerFillPaint)
                canvas.drawCircle(rect.right - w * 0.20f, rect.top + w * 0.20f, boltR, stickerFillPaint)
                canvas.drawCircle(rect.left + w * 0.20f, rect.bottom - w * 0.20f, boltR, stickerFillPaint)
                canvas.drawCircle(rect.right - w * 0.20f, rect.bottom - w * 0.20f, boltR, stickerFillPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val state = gameState ?: return false
        if (state.isGameOver || state.isAnimating) {
            cancelActiveGesture()
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                val x = event.x
                val y = event.y
                dragStartX = x
                dragStartY = y
                dragTouchX = x
                dragTouchY = y
                isDraggingMoved = false

                val touchedSlot = findTraySlotAt(x, y)
                if (touchedSlot != -1 && state.availablePieces[touchedSlot] != null) {
                    draggingSlotIndex = touchedSlot
                    updateHoverFromDragPosition(state)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }

                if (selectedTapSlotIndex in 0 until GameState.TRAY_SIZE &&
                    state.availablePieces[selectedTapSlotIndex] != null &&
                    boardOuterRect.contains(x, y)
                ) {
                    val tappedCell = screenToBoardCell(x, y)
                    if (tappedCell != null) {
                        val (r, c) = tappedCell
                        val piece = state.availablePieces[selectedTapSlotIndex]!!
                        if (state.board.canPlace(piece, r, c)) {
                            val slotToPlace = selectedTapSlotIndex
                            selectedTapSlotIndex = -1
                            clearHoverPreview()
                            interactionListener?.onPiecePlacementRequested(
                                slotIndex = slotToPlace,
                                anchorRow = r,
                                anchorCol = c,
                                actionSequence = state.actionSequence
                            )
                        } else {
                            playInvalidPlacementShake()
                            interactionListener?.onInvalidPlacementFeedback()
                        }
                        invalidate()
                        return true
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> return true

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false
                dragTouchX = event.getX(pointerIndex)
                dragTouchY = event.getY(pointerIndex)

                if (abs(dragTouchX - dragStartX) > 12f || abs(dragTouchY - dragStartY) > 12f) {
                    isDraggingMoved = true
                }

                if (draggingSlotIndex != -1) {
                    updateHoverFromDragPosition(state)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    dragTouchX = event.getX(pointerIndex)
                    dragTouchY = event.getY(pointerIndex)
                }

                if (draggingSlotIndex != -1) {
                    val slot = draggingSlotIndex
                    if (!isDraggingMoved && traySlotRects[slot].contains(dragTouchX, dragTouchY)) {
                        selectedTapSlotIndex = if (selectedTapSlotIndex == slot) -1 else slot
                        draggingSlotIndex = -1
                        clearHoverPreview()
                        performClick()
                        invalidate()
                        return true
                    }

                    updateHoverFromDragPosition(state)
                    val targetRow = hoverAnchorRow
                    val targetCol = hoverAnchorCol
                    val valid = isHoverPlacementValid

                    draggingSlotIndex = -1
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    clearHoverPreview()

                    if (valid && targetRow >= 0 && targetCol >= 0) {
                        selectedTapSlotIndex = -1
                        interactionListener?.onPiecePlacementRequested(
                            slotIndex = slot,
                            anchorRow = targetRow,
                            anchorCol = targetCol,
                            actionSequence = state.actionSequence
                        )
                    } else if (boardOuterRect.contains(dragTouchX, dragTouchY - dragVerticalOffsetPx)) {
                        playInvalidPlacementShake()
                        interactionListener?.onInvalidPlacementFeedback()
                    }
                    performClick()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelActiveGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun cancelActiveGesture() {
        draggingSlotIndex = -1
        activePointerId = MotionEvent.INVALID_POINTER_ID
        clearHoverPreview()
        invalidate()
    }

    private fun clearHoverPreview() {
        hoverAnchorRow = -1
        hoverAnchorCol = -1
        isHoverPlacementValid = false
        previewClearedRows = emptyList()
        previewClearedCols = emptyList()
    }

    private fun updateHoverFromDragPosition(state: GameState) {
        val piece = getCurrentlyManipulatedPiece(state) ?: run {
            clearHoverPreview()
            return
        }
        val totalW = piece.colSpan * cellSizePx + (piece.colSpan - 1) * cellGapPx
        val totalH = piece.rowSpan * cellSizePx + (piece.rowSpan - 1) * cellGapPx
        val pieceTopLeftCenterX = dragTouchX - totalW / 2f + cellSizePx / 2f
        val pieceTopLeftCenterY = dragTouchY - dragVerticalOffsetPx - totalH / 2f + cellSizePx / 2f

        val gridStartX = boardOuterRect.left + boardPaddingPx
        val gridStartY = boardOuterRect.top + boardPaddingPx
        val step = cellSizePx + cellGapPx

        val col = ((pieceTopLeftCenterX - gridStartX - cellSizePx / 2f) / step).roundToInt()
        val row = ((pieceTopLeftCenterY - gridStartY - cellSizePx / 2f) / step).roundToInt()

        if (row in -1..Board.DEFAULT_SIZE && col in -1..Board.DEFAULT_SIZE) {
            hoverAnchorRow = row
            hoverAnchorCol = col
            isHoverPlacementValid = state.board.canPlace(piece, row, col)
            if (isHoverPlacementValid) {
                val (rows, cols) = state.board.previewLineClears(piece, row, col)
                previewClearedRows = rows
                previewClearedCols = cols
            } else {
                previewClearedRows = emptyList()
                previewClearedCols = emptyList()
            }
        } else {
            clearHoverPreview()
        }
    }

    private fun getCurrentlyManipulatedPiece(state: GameState): BlockShape? {
        if (draggingSlotIndex in 0 until GameState.TRAY_SIZE) {
            return state.availablePieces[draggingSlotIndex]
        }
        if (selectedTapSlotIndex in 0 until GameState.TRAY_SIZE) {
            return state.availablePieces[selectedTapSlotIndex]
        }
        return null
    }

    private fun findTraySlotAt(x: Float, y: Float): Int {
        for (i in 0 until GameState.TRAY_SIZE) {
            if (traySlotRects[i].contains(x, y)) return i
        }
        return -1
    }

    private fun screenToBoardCell(x: Float, y: Float): Pair<Int, Int>? {
        val gridStartX = boardOuterRect.left + boardPaddingPx
        val gridStartY = boardOuterRect.top + boardPaddingPx
        val step = cellSizePx + cellGapPx
        val col = ((x - gridStartX) / step).toInt()
        val row = ((y - gridStartY) / step).toInt()
        return if (row in 0 until Board.DEFAULT_SIZE && col in 0 until Board.DEFAULT_SIZE) {
            row to col
        } else {
            null
        }
    }
}
