package com.ultimategames.blockblast.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ultimategames.blockblast.BuildConfig
import com.ultimategames.blockblast.R
import com.ultimategames.blockblast.ads.AdMobEnvironmentConfig
import com.ultimategames.blockblast.ads.AdMobManager
import com.ultimategames.blockblast.ads.ConsentManager
import com.ultimategames.blockblast.audio.SoundEffectsManager
import com.ultimategames.blockblast.data.GameRepository
import com.ultimategames.blockblast.domain.ContinuationEngine
import com.ultimategames.blockblast.domain.GameEngine
import com.ultimategames.blockblast.domain.GameSkin
import com.ultimategames.blockblast.domain.GameState
import com.ultimategames.blockblast.domain.MoveSubmissionResult
import com.ultimategames.blockblast.domain.SkinCatalog

/**
 * Main portrait Activity for Block Blast Ultimate.
 *
 * Includes:
 * - Edge-to-edge WindowInsets adaptation.
 * - Top HUD with Score, Best Record, Combo Badge, 32-Skin Wardrobe Picker ("Skins (32)"),
 *   Sound Toggle, UMP Privacy Settings, and New Game button.
 * - Rewarded Ad integration for BOTH:
 *   1. Single-use Game Over rescue continuation ("Second Chance").
 *   2. Unlocking locked cosmetic skins in the 32-Skin Wardrobe.
 */
class MainActivity : AppCompatActivity(), BlockBlastBoardView.InteractionListener {

    private lateinit var repository: GameRepository
    private lateinit var gameEngine: GameEngine
    private lateinit var soundManager: SoundEffectsManager
    private lateinit var adMobManager: AdMobManager
    private lateinit var consentManager: ConsentManager

    private lateinit var rootContainer: LinearLayout
    private lateinit var scoreValueText: TextView
    private lateinit var highScoreValueText: TextView
    private lateinit var comboBadgeText: TextView
    private lateinit var skinsButton: Button
    private lateinit var soundToggleButton: Button
    private lateinit var privacyButton: Button
    private lateinit var boardView: BlockBlastBoardView
    private lateinit var bannerContainer: FrameLayout

    private var gameOverDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        repository = GameRepository(this)
        gameEngine = GameEngine()
        soundManager = SoundEffectsManager(initialEnabled = repository.isSoundEnabled())

        val adConfig = AdMobEnvironmentConfig(
            isDebugBuild = BuildConfig.DEBUG,
            isTestAdMode = BuildConfig.IS_TEST_AD_MODE,
            appId = BuildConfig.ADMOB_APP_ID,
            rewardedAdUnitId = BuildConfig.ADMOB_REWARDED_UNIT_ID,
            interstitialAdUnitId = BuildConfig.ADMOB_INTERSTITIAL_UNIT_ID,
            bannerAdUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID,
            privacyPolicyUrl = BuildConfig.PRIVACY_POLICY_URL
        )
        adMobManager = AdMobManager(config = adConfig)
        consentManager = ConsentManager(context = this, analytics = adMobManager.analytics)

        buildProgrammaticLayout()
        applyActiveSkin(repository.getSelectedSkin())
        restoreOrStartNewSession()
        initializeConsentAndAds()
    }

    private fun buildProgrammaticLayout() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                insets.left + dp(12),
                insets.top + dp(8),
                insets.right + dp(12),
                insets.bottom + dp(8)
            )
            WindowInsetsCompat.CONSUMED
        }

        if (BuildConfig.DEBUG || BuildConfig.IS_TEST_AD_MODE) {
            val testModeBanner = TextView(this).apply {
                text = getString(R.string.test_mode_badge)
                setTextColor(Color.parseColor("#FCD34D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(3), dp(6), dp(3))
            }
            rootContainer.addView(testModeBanner)
        }

        val topToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topToolbar.addView(titleView)

        skinsButton = createPillButton(
            text = "🎨 Skins (${SkinCatalog.ALL_SKINS.size})",
            bgColorHex = "#6D28D9"
        ).apply {
            setOnClickListener { showSkinsWardrobeDialog() }
        }
        topToolbar.addView(skinsButton)

        soundToggleButton = createPillButton(
            text = if (soundManager.isSoundEnabled) getString(R.string.sound_on) else getString(R.string.sound_off),
            bgColorHex = "#1E293B"
        ).apply {
            setOnClickListener {
                val next = !soundManager.isSoundEnabled
                soundManager.isSoundEnabled = next
                repository.setSoundEnabled(next)
                text = if (next) getString(R.string.sound_on) else getString(R.string.sound_off)
                if (next) soundManager.play(SoundEffectsManager.Cue.PLACE_BLOCK)
            }
        }
        topToolbar.addView(soundToggleButton)

        privacyButton = createPillButton(
            text = getString(R.string.privacy_settings),
            bgColorHex = "#1E293B"
        ).apply {
            setOnClickListener { onPrivacyButtonClicked() }
        }
        topToolbar.addView(privacyButton)

        val newGameButton = createPillButton(
            text = getString(R.string.new_game),
            bgColorHex = "#3B82F6"
        ).apply {
            setOnClickListener { triggerRestartFlow() }
        }
        topToolbar.addView(newGameButton)
        rootContainer.addView(topToolbar)

        val scoreRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }

        val scoreCard = createMetricCard(getString(R.string.score_label), "#38BDF8")
        scoreValueText = scoreCard.second
        scoreRow.addView(scoreCard.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(6)
        })

        comboBadgeText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scoreRow.addView(comboBadgeText)

        val bestCard = createMetricCard(getString(R.string.best_score_label), "#F59E0B")
        highScoreValueText = bestCard.second
        scoreRow.addView(bestCard.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(6)
        })

        rootContainer.addView(scoreRow)

        boardView = BlockBlastBoardView(this).apply {
            contentDescription = getString(R.string.cd_board_grid)
            interactionListener = this@MainActivity
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rootContainer.addView(boardView)

        bannerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        rootContainer.addView(bannerContainer)

        setContentView(rootContainer)
    }

    private fun applyActiveSkin(skin: GameSkin) {
        repository.setSelectedSkinId(skin.id)
        rootContainer.setBackgroundColor(skin.bgHex)
        boardView.applySkin(skin)
        skinsButton.text = "${skin.badgeIcon} ${skin.name}"
    }

    private fun showSkinsWardrobeDialog() {
        val high = repository.getHighScore()
        val currentSkin = repository.getSelectedSkin()
        val items = SkinCatalog.ALL_SKINS.map { skin ->
            val unlocked = repository.isSkinUnlocked(skin, high)
            val activeTag = if (skin.id == currentSkin.id) " [ACTIVE]" else ""
            val statusTag = if (unlocked) {
                "✓ Unlocked"
            } else {
                "🔒 Score ${skin.unlockScoreRequirement} or Watch Ad"
            }
            "${skin.badgeIcon} ${skin.name}$activeTag\n   ${skin.cornerStyle.label} • ${skin.animationStyle.label} ($statusTag)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Skin & Animation (${SkinCatalog.ALL_SKINS.size} Skins)")
            .setItems(items) { dialog, index ->
                val chosen = SkinCatalog.ALL_SKINS[index]
                if (repository.isSkinUnlocked(chosen, high)) {
                    applyActiveSkin(chosen)
                    soundManager.play(SoundEffectsManager.Cue.LINE_CLEAR)
                    dialog.dismiss()
                } else {
                    dialog.dismiss()
                    promptUnlockSkinWithRewardedAd(chosen)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun promptUnlockSkinWithRewardedAd(skin: GameSkin) {
        AlertDialog.Builder(this)
            .setTitle("Unlock ${skin.badgeIcon} ${skin.name}")
            .setMessage(
                "Style: ${skin.cornerStyle.label}\n" +
                    "Animation: ${skin.animationStyle.label}\n\n" +
                    "Reach ${skin.unlockScoreRequirement} points to unlock automatically, or watch 1 optional Rewarded Ad now to unlock permanently!"
            )
            .setPositiveButton("Watch Ad to Unlock") { d, _ ->
                d.dismiss()
                adMobManager.showRewardedAd(
                    activity = this,
                    onRewardEarnedOnce = {
                        repository.unlockSkinViaRewardedAd(skin.id)
                        applyActiveSkin(skin)
                        soundManager.play(SoundEffectsManager.Cue.NEW_RECORD)
                    },
                    onAdClosed = {}
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createPillButton(text: String, bgColorHex: String): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColorHex))
                cornerRadius = 18f * density
                setStroke((1 * density).toInt(), Color.parseColor("#334155"))
            }
            val padH = (9 * density).toInt()
            val padV = (4 * density).toInt()
            setPadding(padH, padV, padH, padV)
            minHeight = (32 * density).toInt()
            minimumHeight = (32 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (5 * density).toInt()
            }
        }
    }

    private fun createMetricCard(label: String, accentHex: String): Pair<LinearLayout, TextView> {
        val density = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 14f * density
                setStroke((1 * density).toInt(), Color.parseColor("#334155"))
            }
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val labelTv = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val valueTv = TextView(this).apply {
            text = "0"
            setTextColor(Color.parseColor(accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(labelTv)
        card.addView(valueTv)
        return card to valueTv
    }

    private fun restoreOrStartNewSession() {
        val saved = repository.loadSavedGameState()
        if (saved != null && !saved.isGameOver) {
            gameEngine.restoreState(saved)
        } else {
            gameEngine.startNewGame(highScore = repository.getHighScore())
        }
        syncUiWithState(gameEngine.currentState)
    }

    private fun initializeConsentAndAds() {
        consentManager.gatherConsent(
            activity = this,
            isDebugBuild = BuildConfig.DEBUG
        ) { canRequestAds, _ ->
            adMobManager.initializeIfPermitted(this, canRequestAds = canRequestAds)
            val screenHeightDp = resources.configuration.screenHeightDp
            bannerContainer.post {
                adMobManager.attachAdaptiveBannerIfSpacePermits(
                    activity = this,
                    bannerContainer = bannerContainer,
                    screenHeightDp = screenHeightDp
                )
            }
        }
    }

    override fun onPiecePlacementRequested(
        slotIndex: Int,
        anchorRow: Int,
        anchorCol: Int,
        actionSequence: Long
    ) {
        val result = gameEngine.submitMove(
            slotIndex = slotIndex,
            anchorRow = anchorRow,
            anchorCol = anchorCol,
            expectedActionSequence = actionSequence
        )

        when (result) {
            is MoveSubmissionResult.Rejected -> {
                soundManager.play(SoundEffectsManager.Cue.INVALID_MOVE)
                boardView.playInvalidPlacementShake()
            }

            is MoveSubmissionResult.Success -> {
                val nextState = result.updatedState
                repository.saveActiveGameState(nextState)

                val linesCleared = result.boardOutcome.totalLinesCleared
                if (linesCleared > 0) {
                    if (linesCleared >= 2 || nextState.comboStreak >= 2) {
                        soundManager.play(SoundEffectsManager.Cue.COMBO_CLEAR)
                    } else {
                        soundManager.play(SoundEffectsManager.Cue.LINE_CLEAR)
                    }
                    val bannerMsg = buildClearBannerText(
                        linesCleared = linesCleared,
                        comboStreak = nextState.comboStreak,
                        points = result.scoreBreakdown.totalPointsAwarded,
                        isAllClear = result.boardOutcome.isBoardAllClear
                    )
                    gameEngine.setAnimating(true)
                    syncUiWithState(gameEngine.currentState)
                    boardView.playLineClearAnimation(
                        clearedCells = result.boardOutcome.clearedCells,
                        bannerMessage = bannerMsg
                    ) {
                        gameEngine.setAnimating(false)
                        syncUiWithState(gameEngine.currentState)
                        checkGameOverAfterMove(gameEngine.currentState)
                    }
                } else {
                    if (nextState.score > result.previousState.highScore && result.previousState.highScore > 0) {
                        soundManager.play(SoundEffectsManager.Cue.NEW_RECORD)
                    } else {
                        soundManager.play(SoundEffectsManager.Cue.PLACE_BLOCK)
                    }
                    syncUiWithState(nextState)
                    checkGameOverAfterMove(nextState)
                }
            }
        }
    }

    override fun onInvalidPlacementFeedback() {
        soundManager.play(SoundEffectsManager.Cue.INVALID_MOVE)
    }

    private fun buildClearBannerText(
        linesCleared: Int,
        comboStreak: Int,
        points: Int,
        isAllClear: Boolean
    ): String {
        return when {
            isAllClear -> "ALL CLEAR! +$points"
            comboStreak >= 2 && linesCleared >= 2 -> "${linesCleared}x CLEAR • COMBO x$comboStreak! +$points"
            comboStreak >= 2 -> "COMBO x$comboStreak! +$points"
            linesCleared >= 2 -> "${linesCleared}x LINES! +$points"
            else -> "+$points"
        }
    }

    private fun syncUiWithState(state: GameState) {
        scoreValueText.text = state.score.toString()
        highScoreValueText.text = state.highScore.toString()
        if (state.comboStreak >= 2) {
            comboBadgeText.visibility = View.VISIBLE
            comboBadgeText.text = getString(R.string.combo_label, state.comboStreak)
        } else {
            comboBadgeText.visibility = View.INVISIBLE
        }
        boardView.bindState(state)
    }

    private fun checkGameOverAfterMove(state: GameState) {
        if (!state.isGameOver) return
        soundManager.play(SoundEffectsManager.Cue.GAME_OVER)
        adMobManager.onGameOverRecorded()
        showGameOverModal(state)
    }

    private fun showGameOverModal(state: GameState) {
        gameOverDialog?.dismiss()
        val canOfferRewarded = ContinuationEngine.canOfferContinuation(state) &&
            adMobManager.isRewardedAdReady()

        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.game_over_title))
            .setMessage(getString(R.string.game_over_subtitle, state.score, state.highScore))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.play_again)) { dialog, _ ->
                dialog.dismiss()
                triggerNewGameAfterGameOver()
            }

        if (canOfferRewarded) {
            builder.setNeutralButton(getString(R.string.watch_ad_continue)) { dialog, _ ->
                dialog.dismiss()
                adMobManager.showRewardedAd(
                    activity = this,
                    onRewardEarnedOnce = {
                        gameEngine.applyRewardedAdContinuation()
                        repository.saveActiveGameState(gameEngine.currentState)
                    },
                    onAdClosed = { rewardGranted ->
                        if (rewardGranted) {
                            syncUiWithState(gameEngine.currentState)
                        } else {
                            showGameOverModal(gameEngine.currentState)
                        }
                    }
                )
            }
        }

        gameOverDialog = builder.create()
        if (!isFinishing && !isDestroyed) {
            gameOverDialog?.show()
        }
    }

    private fun triggerNewGameAfterGameOver() {
        adMobManager.maybeShowInterstitialBeforeNewGame(
            activity = this,
            isDraggingOrAnimating = boardView.isPerformingGestureOrAnimation
        ) {
            val freshState = gameEngine.startNewGame(highScore = repository.getHighScore())
            repository.saveActiveGameState(freshState)
            syncUiWithState(freshState)
        }
    }

    private fun triggerRestartFlow() {
        boardView.cancelActiveGesture()
        val freshState = gameEngine.startNewGame(highScore = repository.getHighScore())
        repository.saveActiveGameState(freshState)
        syncUiWithState(freshState)
    }

    private fun onPrivacyButtonClicked() {
        if (consentManager.isPrivacyOptionsRequired) {
            consentManager.showPrivacyOptionsForm(this) {}
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)))
            } catch (_: Exception) {
            }
        }
    }

    override fun onPause() {
        boardView.cancelActiveGesture()
        repository.saveActiveGameState(gameEngine.currentState)
        adMobManager.pauseBanner()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        adMobManager.resumeBanner()
    }

    override fun onDestroy() {
        gameOverDialog?.dismiss()
        gameOverDialog = null
        adMobManager.releaseAll()
        soundManager.release()
        super.onDestroy()
    }
}
