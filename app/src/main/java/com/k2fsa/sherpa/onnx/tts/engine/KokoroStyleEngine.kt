package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kokoro 高阶特异层策略引擎 (仅在当前激活模型为 Kokoro 时开启)
 * 1. 伴生情绪风格路由 (Emotion Style Companion Routing)
 * 2. 风格向量插值融炼 (Style Vector Interpolation & Blending)
 */
object KokoroStyleEngine {
    private const val TAG = "KokoroStyleEngine"

    // Kokoro 架构中每个发音人对应的风格向量大小 (510 * 256 * 4 bytes = 522,240 bytes)
    const val FLOATS_PER_SPEAKER = 510 * 256
    const val BYTES_PER_SPEAKER = FLOATS_PER_SPEAKER * 4

    /**
     * 判断当前模型是否为 Kokoro 架构
     */
    fun isKokoro(modelDir: String?): Boolean {
        if (modelDir == null) return false
        return modelDir.contains("kokoro", ignoreCase = true)
    }

    /**
     * 针对女性发音人的最佳情感伴生发音人推荐表 (基于 Kokoro 103 音色库声学标定)
     */
    val FEMALE_COMPANIONS = mapOf(
        EmotionType.NEUTRAL to 10,   // zf_008 (清脆少女)
        EmotionType.HAPPY to 10,     // zf_008 (轻快明朗少女)
        EmotionType.ANGRY to 7,      // zf_005 (冷冽御姐，张力高，爆发力强)
        EmotionType.SAD to 5,        // zf_003 (微声凄楚，气声丰富，颤抖感)
        EmotionType.FEAR to 4,       // zf_002 (战栗紧绷，呼吸急促)
        EmotionType.WHISPER to 3     // zf_001 (温柔近场气声私语)
    )

    /**
     * 针对男性发音人的最佳情感伴生发音人推荐表
     */
    val MALE_COMPANIONS = mapOf(
        EmotionType.NEUTRAL to 64,   // zm_015 (清朗青年)
        EmotionType.HAPPY to 64,     // zm_015 (阳光活泼)
        EmotionType.ANGRY to 58,     // zm_009 (沉稳霸气低音，气势强悍)
        EmotionType.SAD to 67,       // zm_025 (苍凉落寞，沉重哀伤)
        EmotionType.FEAR to 60,      // zm_011 (慌乱短促，心跳加速)
        EmotionType.WHISPER to 65    // zm_016 (磁性近场耳语)
    )

    /**
     * 获取指定发音人在目标情绪下的伴生发音人 ID
     * @param baseSid 当前基础发音人 ID
     * @param emotion 目标情绪
     * @param isFemale 是否为女性发音人
     * @param intensity 情绪强度 (0.0 ~ 1.0)
     */
    fun getEmotionalCompanionSid(
        baseSid: Int,
        emotion: EmotionType,
        isFemale: Boolean,
        intensity: Float = 0.85f
    ): Int {
        if (emotion == EmotionType.NEUTRAL || intensity < 0.3f) {
            return baseSid
        }
        val companionMap = if (isFemale) FEMALE_COMPANIONS else MALE_COMPANIONS
        return companionMap[emotion] ?: baseSid
    }

    /**
     * 获取伴生发音人的特征描述
     */
    fun getCompanionDesc(emotion: EmotionType, isFemale: Boolean): String {
        return when (emotion) {
            EmotionType.NEUTRAL -> "基础基准风格"
            EmotionType.HAPPY -> if (isFemale) "注入 zf_008 轻快明朗少女风格" else "注入 zm_015 阳光清朗青年风格"
            EmotionType.ANGRY -> if (isFemale) "注入 zf_005 冷冽御姐爆发风格" else "注入 zm_009 沉稳霸气重低音风格"
            EmotionType.SAD -> if (isFemale) "注入 zf_003 凄婉气声风格" else "注入 zm_025 苍凉落寞哀怨风格"
            EmotionType.FEAR -> if (isFemale) "注入 zf_002 战栗紧绷风格" else "注入 zm_011 慌乱急促喘息风格"
            EmotionType.WHISPER -> if (isFemale) "注入 zf_001 温柔近场私语风格" else "注入 zm_016 磁性近场耳语风格"
        }
    }

    /**
     * 物理级风格向量插值融炼 (Style Vector Blending)
     * 从 voices.bin 中提取发音人 A 与发音人 B 的风格向量，按照权重 weightB 进行线性融合，
     * 并追加为全新的发音人存入 voices.bin，同时在 voices.json 中登记。
     */
    fun blendVoices(
        modelDirFile: File,
        sidA: Int,
        sidB: Int,
        weightB: Float,
        blendedName: String,
        gender: String = "女声"
    ): SpeakerInfo? {
        val voicesBin = File(modelDirFile, "voices.bin")
        val voicesJson = File(modelDirFile, "voices.json")
        if (!voicesBin.exists() || !voicesBin.canRead() || !voicesBin.canWrite()) {
            Log.e(TAG, "voices.bin does not exist or is not writable: ${voicesBin.absolutePath}")
            return null
        }

        try {
            val clampedWeightB = weightB.coerceIn(0.0f, 1.0f)
            val weightA = 1.0f - clampedWeightB

            val bytesA = ByteArray(BYTES_PER_SPEAKER)
            val bytesB = ByteArray(BYTES_PER_SPEAKER)

            RandomAccessFile(voicesBin, "r").use { raf ->
                raf.seek(sidA.toLong() * BYTES_PER_SPEAKER)
                raf.readFully(bytesA)
                raf.seek(sidB.toLong() * BYTES_PER_SPEAKER)
                raf.readFully(bytesB)
            }

            val bufA = ByteBuffer.wrap(bytesA).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val bufB = ByteBuffer.wrap(bytesB).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

            val blendedFloats = FloatArray(FLOATS_PER_SPEAKER)
            for (i in 0 until FLOATS_PER_SPEAKER) {
                blendedFloats[i] = bufA.get(i) * weightA + bufB.get(i) * clampedWeightB
            }

            val outBytes = ByteArray(BYTES_PER_SPEAKER)
            ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(blendedFloats)

            // Overwrite custom blender slot (Slot 102) to keep voices.bin tensor size strictly unchanged
            val targetSid = 102
            RandomAccessFile(voicesBin, "rw").use { raf ->
                raf.seek(targetSid.toLong() * BYTES_PER_SPEAKER)
                raf.write(outBytes)
            }

            // Update voices.json entry for targetSid
            val jsonArr = if (voicesJson.exists()) {
                JSONArray(voicesJson.readText())
            } else {
                JSONArray()
            }

            var updated = false
            for (i in 0 until jsonArr.length()) {
                val item = jsonArr.optJSONObject(i)
                if (item != null && item.optInt("id", -1) == targetSid) {
                    item.put("name", "🧪 $blendedName")
                    item.put("gender", gender)
                    item.put("f0", if (gender == "女声") 220.0 else 135.0)
                    item.put("desc", "Kokoro 融炼音色 ($blendedName · 融合权重 ${(clampedWeightB * 100).toInt()}%)")
                    item.put("tag", "融炼定制")
                    updated = true
                    break
                }
            }

            if (!updated) {
                val newObj = JSONObject().apply {
                    put("id", targetSid)
                    put("name", "🧪 $blendedName")
                    put("gender", gender)
                    put("f0", if (gender == "女声") 220.0 else 135.0)
                    put("desc", "Kokoro 融炼音色 ($blendedName · 融合权重 ${(clampedWeightB * 100).toInt()}%)")
                    put("tag", "融炼定制")
                }
                jsonArr.put(newObj)
            }
            voicesJson.writeText(jsonArr.toString(2))

            Log.i(TAG, "Successfully blended voices! Custom Slot Sid: $targetSid, Name: $blendedName")
            return SpeakerInfo(
                id = targetSid,
                name = "🧪 $blendedName",
                gender = gender,
                f0 = if (gender == "女声") 220f else 135f,
                desc = "Kokoro 融炼音色 ($blendedName · 融合权重 ${(clampedWeightB * 100).toInt()}%)",
                tag = "融炼定制"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to blend voices", e)
            return null
        }
    }
}
