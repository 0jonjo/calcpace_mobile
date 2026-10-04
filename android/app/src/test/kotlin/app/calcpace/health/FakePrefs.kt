package app.calcpace.health

/** An in-memory [HcState.Prefs]. */
class FakePrefs : HcState.Prefs {
    val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun write(clear: Boolean, values: Map<String, String?>) {
        if (clear) this.values.clear()
        values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
    }
}
