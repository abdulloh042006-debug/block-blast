package com.ultimategames.blockblast.data

import android.content.Context
import android.content.SharedPreferences
import com.ultimategames.blockblast.domain.BlockCatalog
import com.ultimategames.blockblast.domain.BlockShape
import com.ultimategames.blockblast.domain.Board
import com.ultimategames.blockblast.domain.GameSkin
import com.ultimategames.blockblast.domain.GameState
import com.ultimategames.blockblast.domain.SkinCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local persistence layer storing the player's high score, sound preference, active/unlocked skins,
 * and serialized active [GameState] so gameplay survives app backgrounding and process termination.
 */
class GameRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getHighScore(): Int = prefs.getInt(KEY_HIGH_SCORE, 0)

    fun saveHighScore(highScore: Int) {
        val current = getHighScore()
        if (highScore > current) {
            prefs.edit().putInt(KEY_HIGH_SCORE, highScore).apply()
        }
    }

    fun isSoundEnabled(): Boolean = prefs.getBoolean(KEY_SOUND_ENABLED, true)

    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    fun getSelectedSkin(): GameSkin {
        val id = prefs.getString(KEY_SELECTED_SKIN_ID, SkinCatalog.DEFAULT_SKIN.id)
            ?: SkinCatalog.DEFAULT_SKIN.id
        return SkinCatalog.getById(id)
    }

    fun setSelectedSkinId(skinId: String) {
        prefs.edit().putString(KEY_SELECTED_SKIN_ID, skinId).apply()
    }

    fun getRewardUnlockedSkinIds(): Set<String> {
        return prefs.getStringSet(KEY_UNLOCKED_SKIN_IDS, emptySet()) ?: emptySet()
    }

    fun unlockSkinViaRewardedAd(skinId: String) {
        val updated = HashSet(getRewardUnlockedSkinIds())
        updated.add(skinId)
        prefs.edit().putStringSet(KEY_UNLOCKED_SKIN_IDS, updated).apply()
    }

    fun isSkinUnlocked(skin: GameSkin, currentHighScore: Int = getHighScore()): Boolean {
        if (skin.unlockedByDefault) return true
        if (currentHighScore >= skin.unlockScoreRequirement) return true
        return getRewardUnlockedSkinIds().contains(skin.id)
    }

    fun saveActiveGameState(state: GameState) {
        saveHighScore(state.highScore)
        val json = serializeStateToJson(state)
        prefs.edit()
            .putString(KEY_SAVED_GAME_STATE, json)
            .putInt(KEY_HIGH_SCORE, maxOf(getHighScore(), state.highScore))
            .apply()
    }

    fun loadSavedGameState(): GameState? {
        val raw = prefs.getString(KEY_SAVED_GAME_STATE, null) ?: return null
        return try {
            deserializeStateFromJson(raw, persistedHighScore = getHighScore())
        } catch (_: Exception) {
            null
        }
    }

    fun clearSavedGameState() {
        prefs.edit().remove(KEY_SAVED_GAME_STATE).apply()
    }

    companion object {
        private const val PREFS_NAME = "block_blast_ultimate_prefs"
        private const val KEY_HIGH_SCORE = "high_score"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_SELECTED_SKIN_ID = "selected_skin_id"
        private const val KEY_UNLOCKED_SKIN_IDS = "unlocked_skin_ids"
        private const val KEY_SAVED_GAME_STATE = "saved_game_state_v1"

        fun serializeStateToJson(state: GameState): String {
            val root = JSONObject()
            val cellsArray = JSONArray()
            for (v in state.board.toFlatArray()) {
                cellsArray.put(v)
            }
            root.put("boardCells", cellsArray)

            val trayArray = JSONArray()
            for (piece in state.availablePieces) {
                trayArray.put(piece?.id ?: JSONObject.NULL)
            }
            root.put("tray", trayArray)
            root.put("score", state.score)
            root.put("highScore", state.highScore)
            root.put("comboStreak", state.comboStreak)
            root.put("movesSinceLastClear", state.movesSinceLastClear)
            root.put("totalMovesPlayed", state.totalMovesPlayed)
            root.put("totalLinesCleared", state.totalLinesCleared)
            root.put("isGameOver", state.isGameOver)
            root.put("isNewRecordAchieved", state.isNewRecordAchieved)
            root.put("continuationsUsed", state.continuationsUsed)
            root.put("actionSequence", state.actionSequence)
            root.put("prngState", state.prngState)
            return root.toString()
        }

        fun deserializeStateFromJson(jsonString: String, persistedHighScore: Int = 0): GameState {
            val root = JSONObject(jsonString)
            val cellsJson = root.getJSONArray("boardCells")
            val cells = IntArray(Board.DEFAULT_SIZE * Board.DEFAULT_SIZE)
            for (i in cells.indices) {
                cells[i] = cellsJson.optInt(i, 0)
            }
            val board = Board.fromFlatArray(cells)

            val trayJson = root.getJSONArray("tray")
            val tray = ArrayList<BlockShape?>(GameState.TRAY_SIZE)
            for (i in 0 until GameState.TRAY_SIZE) {
                if (trayJson.isNull(i)) {
                    tray.add(null)
                } else {
                    val id = trayJson.getString(i)
                    tray.add(BlockCatalog.getById(id))
                }
            }

            val savedScore = root.optInt("score", 0)
            val savedHigh = maxOf(persistedHighScore, root.optInt("highScore", 0), savedScore)

            return GameState(
                board = board,
                availablePieces = tray,
                score = savedScore,
                highScore = savedHigh,
                comboStreak = root.optInt("comboStreak", 0),
                movesSinceLastClear = root.optInt("movesSinceLastClear", 0),
                totalMovesPlayed = root.optInt("totalMovesPlayed", 0),
                totalLinesCleared = root.optInt("totalLinesCleared", 0),
                isGameOver = root.optBoolean("isGameOver", false),
                isNewRecordAchieved = root.optBoolean("isNewRecordAchieved", false),
                continuationsUsed = root.optInt("continuationsUsed", 0),
                isAnimating = false,
                actionSequence = root.optLong("actionSequence", 1L),
                prngState = root.optLong("prngState", 0x5DEECE66DL)
            )
        }
    }
}
