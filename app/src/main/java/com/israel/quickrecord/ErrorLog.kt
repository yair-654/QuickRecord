package com.israel.quickrecord

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * The problems log: the last 40 problems and notices, kept across restarts,
 * shown in Settings › Problems log and as a banner on Home.
 */
object ErrorLog {
    const val TAG = "QuickRecord"
    private const val MAX = 40

    data class Entry(val timeMs: Long, val message: String, val isProblem: Boolean)

    val entries = mutableStateListOf<Entry>()
    private lateinit var sp: SharedPreferences
    private var dismissedAt by mutableLongStateOf(0L)
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        sp = context.getSharedPreferences("log", Context.MODE_PRIVATE)
        dismissedAt = sp.getLong("dismissed", 0)
        try {
            val arr = JSONArray(sp.getString("entries", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(Entry(o.getLong("t"), o.getString("m"), o.optBoolean("p", true)))
            }
        } catch (_: Exception) {
        }
    }

    /** Something went wrong. */
    fun problem(message: String, e: Throwable? = null) {
        Log.e(TAG, message, e)
        add(Entry(System.currentTimeMillis(), message, true))
    }

    /** Worth knowing, not a failure (for example a setting the camera can't do). */
    fun notice(message: String) {
        Log.i(TAG, message)
        add(Entry(System.currentTimeMillis(), message, false))
    }

    fun clear() {
        main.post {
            entries.clear()
            save()
        }
    }

    /** The latest problem from the last 24 hours that the user hasn't dismissed. */
    fun recentProblem(): Entry? {
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        return entries.firstOrNull { it.isProblem && it.timeMs > cutoff && it.timeMs > dismissedAt }
    }

    fun dismissRecent() {
        val now = System.currentTimeMillis()
        sp.edit().putLong("dismissed", now).apply()
        main.post { dismissedAt = now }
    }

    private fun add(entry: Entry) {
        main.post {
            entries.add(0, entry)
            while (entries.size > MAX) entries.removeAt(entries.size - 1)
            save()
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (e in entries) arr.put(JSONObject().put("t", e.timeMs).put("m", e.message).put("p", e.isProblem))
        sp.edit().putString("entries", arr.toString()).apply()
    }
}
