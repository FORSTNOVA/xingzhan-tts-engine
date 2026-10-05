import android.content.Context
import android.content.SharedPreferences

class PreferenceHelper(context: Context) {

    private val PREFS_NAME = "com.k2fsa.sherpa.onnx.tts.engine"
    private val SPEED_KEY = "speed"
    private val SID_KEY = "speaker_id"
    private val FAVORITES_KEY = "favorite_speakers"
    private val MODEL_KEY = "selected_model"

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setSpeed(value: Float) {
        sharedPreferences.edit().putFloat(SPEED_KEY, value).apply()
    }

    fun getSpeed(): Float {
        return sharedPreferences.getFloat(SPEED_KEY, 1.0f)
    }

    fun setSid(value: Int) {
        sharedPreferences.edit().putInt(SID_KEY, value).apply()
    }

    fun getSid(): Int {
        return sharedPreferences.getInt(SID_KEY, 0)
    }

    fun getFavorites(): Set<String> {
        return sharedPreferences.getStringSet(FAVORITES_KEY, emptySet()) ?: emptySet()
    }

    fun isFavorite(id: Int): Boolean {
        return getFavorites().contains(id.toString())
    }

    fun toggleFavorite(id: Int): Boolean {
        val current = getFavorites().toMutableSet()
        val key = id.toString()
        val willBeFavorite = !current.contains(key)
        if (willBeFavorite) {
            current.add(key)
        } else {
            current.remove(key)
        }
        sharedPreferences.edit().putStringSet(FAVORITES_KEY, current).apply()
        return willBeFavorite
    }

    fun getSelectedModel(): String {
        return sharedPreferences.getString(MODEL_KEY, "vits-zh-aishell3") ?: "vits-zh-aishell3"
    }

    fun setSelectedModel(modelDir: String) {
        sharedPreferences.edit().putString(MODEL_KEY, modelDir).apply()
    }

    private val KOKORO_COMPANION_KEY = "kokoro_companion_routing"

    fun getKokoroCompanionEnabled(): Boolean {
        return sharedPreferences.getBoolean(KOKORO_COMPANION_KEY, true)
    }

    fun setKokoroCompanionEnabled(value: Boolean) {
        sharedPreferences.edit().putBoolean(KOKORO_COMPANION_KEY, value).apply()
    }
}