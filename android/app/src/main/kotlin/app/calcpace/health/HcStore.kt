package app.calcpace.health

import android.content.Context
import androidx.core.content.edit

/**
 * [HcState] on the "health_connect" shared preferences, which stay out of
 * backups: the link token belongs to the WebView session on this phone, the
 * changes token to this phone's Health Connect.
 */
object HcStore {
    private const val PREFS = "health_connect"

    fun open(context: Context): HcState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HcState(object : HcState.Prefs {
            override fun get(key: String): String? = prefs.getString(key, null)

            override fun write(clear: Boolean, values: Map<String, String?>) = prefs.edit {
                if (clear) clear()
                values.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
            }
        })
    }
}
