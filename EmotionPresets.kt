package dev.ahmedmohamed.hayaitts.tts

/**
 * Maps one of nine emotions to how the voice is shaped. Kokoro has no emotion input, so the
 * emotion is made from three things we can control per request: tempo, pitch and loudness,
 * plus light punctuation changes (Kokoro reads "..." as a hesitation and "!" as energy).
 *
 * Values are multipliers around a neutral child-like base. Intensity (1..3) scales how far
 * the voice moves from neutral.
 */
object EmotionPresets {

    class Shaping(val speed: Float, val pitch: Float, val gain: Float, val text: String)

    private class Preset(val speed: Float, val pitch: Float, val gain: Float)

    /** Child-like base: a slightly raised pitch on every emotion. */
    private const val BASE_PITCH = 1.10f

    private val presets = mapOf(
        "calm" to Preset(0.95f, 1.00f, 0.95f),
        "happy" to Preset(1.08f, 1.05f, 1.00f),
        "sad" to Preset(0.85f, 0.93f, 0.85f),
        "angry" to Preset(1.10f, 0.97f, 1.15f),
        "shy" to Preset(0.88f, 1.03f, 0.75f),
        "sleepy" to Preset(0.78f, 0.92f, 0.70f),
        "curious" to Preset(1.00f, 1.06f, 1.00f),
        "excited" to Preset(1.20f, 1.10f, 1.10f),
        "sarcastic" to Preset(0.88f, 0.96f, 0.95f),
    )

    val names: Set<String> get() = presets.keys

    fun shape(emotion: String?, intensity: Int, text: String, userSpeed: Float = 1f, userPitch: Float = 1f): Shaping {
        val key = emotion?.lowercase()?.trim().orEmpty()
        val p = presets[key] ?: presets.getValue("calm")
        val k = intensity.coerceIn(1, 3) / 2f // 0.5, 1.0, 1.5
        fun scale(v: Float) = 1f + (v - 1f) * k
        val speed = (scale(p.speed) * userSpeed).coerceIn(0.6f, 1.6f)
        val pitch = (BASE_PITCH * scale(p.pitch) * userPitch).coerceIn(0.8f, 1.5f)
        val gain = scale(p.gain).coerceIn(0.4f, 1.4f)
        return Shaping(speed, pitch, gain, restyle(key, text, intensity))
    }

    private fun restyle(emotion: String, text: String, intensity: Int): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        return when (emotion) {
            "happy", "excited" -> if (intensity >= 2) t.replace(Regex("""\.(\s|$)"""), "!$1") else t
            "angry" -> t.replace(Regex("""\.(\s|$)"""), "!$1")
            "sad", "sleepy", "shy" -> {
                t = t.replace(Regex(""",\s+"""), "... ")
                if (intensity >= 2) t = t.replace(Regex("""\.(\s|$)"""), "...$1")
                t
            }
            else -> t
        }
    }
}
