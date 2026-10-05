package com.k2fsa.sherpa.onnx.tts.engine

enum class EmotionType(
    val id: String,
    val label: String,
    val icon: String,
    val targetSpeed: Float,
    val targetSilence: Float,
    val desc: String
) {
    NEUTRAL("neutral", "平静", "😐", 1.0f, 0.20f, "标准自然语调"),
    HAPPY("happy", "欢快", "😄", 1.15f, 0.14f, "轻快语速，明朗尾音"),
    ANGRY("angry", "愤怒", "😡", 1.25f, 0.08f, "急促紧凑，短促有力"),
    SAD("sad", "悲伤", "😢", 0.85f, 0.42f, "迟疑低缓，长呼吸停顿"),
    FEAR("fear", "紧张", "😰", 1.28f, 0.06f, "慌乱急切，极短句间断句"),
    WHISPER("whisper", "低语", "🤫", 0.92f, 0.28f, "柔和舒缓，平稳微风感");

    companion object {
        fun fromTag(tag: String): EmotionType? {
            val clean = tag.trim().lowercase()
            return when {
                clean.contains("怒") || clean.contains("angry") || clean.contains("rage") -> ANGRY
                clean.contains("喜") || clean.contains("乐") || clean.contains("happy") || clean.contains("joy") || clean.contains("开心") || clean.contains("兴奋") -> HAPPY
                clean.contains("悲") || clean.contains("伤") || clean.contains("哭") || clean.contains("难过") || clean.contains("低落") || clean.contains("沮丧") || clean.contains("sad") -> SAD
                clean.contains("惧") || clean.contains("怕") || clean.contains("惊") || clean.contains("慌") || clean.contains("紧张") || clean.contains("fear") -> FEAR
                clean.contains("低语") || clean.contains("悄悄") || clean.contains("耳语") || clean.contains("轻声") || clean.contains("温") || clean.contains("柔") || clean.contains("whisper") || clean.contains("soft") || clean.contains("gentle") -> WHISPER
                clean.contains("平") || clean.contains("常") || clean.contains("自然") || clean.contains("neutral") -> NEUTRAL
                else -> null
            }
        }
    }
}

data class EmotionParams(
    val speed: Float,
    val silenceScale: Float,
    val processedText: String,
    val emotion: EmotionType
)

data class ParsedEmotionTag(
    val emotion: EmotionType?,
    val cleanText: String,
    val intensity: Float? = null
)

object EmotionEngine {
    /**
     * Calculates the universal acoustic modulation parameters based on emotion and intensity.
     * @param emotion The target emotion.
     * @param intensity Strength between 0.0f (neutral) and 1.0f (full emotion).
     * @param rawText The raw input text.
     */
    fun calculate(
        emotion: EmotionType,
        intensity: Float = 0.8f,
        rawText: String
    ): EmotionParams {
        val clampedIntensity = intensity.coerceIn(0.0f, 1.0f)

        // Linear interpolation from Neutral (speed=1.0, silence=0.20)
        val finalSpeed = 1.0f + (emotion.targetSpeed - 1.0f) * clampedIntensity
        val finalSilence = 0.20f + (emotion.targetSilence - 0.20f) * clampedIntensity

        // Prosodic punctuation shaping
        val processedText = rewriteProsody(rawText, emotion, clampedIntensity)

        return EmotionParams(
            speed = (finalSpeed * 100).toInt() / 100f,
            silenceScale = (finalSilence * 100).toInt() / 100f,
            processedText = processedText,
            emotion = emotion
        )
    }

    /**
     * Parses explicit emotion tags embedded in text, such as:
     * "[愤怒:0.8] 你怎么能这样！" -> ANGRY, "你怎么能这样！", 0.8f
     * "[微怒] 别闹了。" -> ANGRY, "别闹了。", 0.35f
     * "[暴怒] 滚！" -> ANGRY, "滚！", 1.0f
     * "<emotion type="sad" intensity="0.5">为什么...</emotion>" -> SAD, "为什么...", 0.5f
     */
    fun parseTextWithEmotionTag(rawText: String): ParsedEmotionTag {
        val bracketRegex = Regex("""^[\[\(【（]([^\]\)】）]+)[\]\)】）]\s*""")
        val matchBracket = bracketRegex.find(rawText)
        if (matchBracket != null) {
            val tag = matchBracket.groupValues[1]
            val parts = tag.split(Regex("[:：,，\\s]+")).filter { it.isNotBlank() }
            val emotionPart = parts.firstOrNull() ?: tag
            val intensityFromColon = parts.getOrNull(1)?.toFloatOrNull()
            val matchedEmotion = EmotionType.fromTag(emotionPart) ?: EmotionType.fromTag(tag)
            if (matchedEmotion != null) {
                val detectedIntensity = intensityFromColon ?: when {
                    tag.contains("微") || tag.contains("轻") || tag.contains("稍") || tag.contains("点") -> 0.35f
                    tag.contains("暴") || tag.contains("狂") || tag.contains("极其") || tag.contains("非常") || tag.contains("大怒") || tag.contains("痛哭") || tag.contains("歇斯底里") -> 1.0f
                    else -> null
                }
                val cleanText = rawText.substring(matchBracket.range.last + 1).trim()
                return ParsedEmotionTag(matchedEmotion, cleanText, detectedIntensity)
            }
        }

        val xmlRegex = Regex("""^<emotion(?:\s+type=["']?([^"'>]+)["']?)?(?:\s+intensity=["']?([^"'>]+)["']?)?>([^<]*)</emotion>\s*""", RegexOption.IGNORE_CASE)
        val matchXml = xmlRegex.find(rawText)
        if (matchXml != null) {
            val tag = matchXml.groupValues[1].ifBlank { matchXml.groupValues[3] }
            val intensityAttr = matchXml.groupValues[2].toFloatOrNull()
            val matchedEmotion = EmotionType.fromTag(tag)
            if (matchedEmotion != null) {
                val cleanText = rawText.replace(xmlRegex, "$3").trim()
                return ParsedEmotionTag(matchedEmotion, cleanText, intensityAttr)
            }
        }

        return ParsedEmotionTag(null, rawText, null)
    }

    private fun rewriteProsody(text: String, emotion: EmotionType, intensity: Float): String {
        if (text.isBlank() || intensity < 0.2f) return text

        var t = text.trim()
        return when (emotion) {
            EmotionType.ANGRY -> {
                if (!t.endsWith("！") && !t.endsWith("!") && !t.endsWith("？") && !t.endsWith("?")) {
                    t += "！"
                }
                t.replace("……", "！").replace("...", "！")
            }
            EmotionType.SAD -> {
                if (t.endsWith("。") || t.endsWith(".")) {
                    t = t.dropLast(1) + "……"
                } else if (!t.endsWith("……") && !t.endsWith("...")) {
                    t += "……"
                }
                t
            }
            EmotionType.FEAR -> {
                t.replace("，", "……").replace(",", "……")
            }
            EmotionType.HAPPY -> {
                if (t.endsWith("。") || t.endsWith(".")) {
                    t = t.dropLast(1) + "！"
                }
                t
            }
            EmotionType.WHISPER -> {
                t.replace("！", "。").replace("!", ".")
            }
            EmotionType.NEUTRAL -> t
        }
    }
}
