package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val SPEAKER_TAG = "SpeakerManager"

data class SpeakerInfo(
    val id: Int,
    val name: String,
    val gender: String, // "女声" | "男声"
    val f0: Float = 0f,
    val desc: String = "",
    val tag: String = "" // "少女" | "御姐" | "青年" | "大叔" | "萌音" | "播音" | "其他"
)

object SpeakerManager {
    private var cachedSpeakers: List<SpeakerInfo>? = null
    private var cachedModelDir: String? = null

    fun invalidateCache() {
        cachedSpeakers = null
        cachedModelDir = null
    }

    fun getSpeakers(context: Context, modelDir: String = TtsEngine.modelDir ?: "vits-zh-aishell3"): List<SpeakerInfo> {
        if (cachedSpeakers != null && cachedModelDir == modelDir) {
            return cachedSpeakers!!
        }

        val list = mutableListOf<SpeakerInfo>()

        // 1. Try assets
        var jsonContent: String? = runCatching {
            context.assets.open("$modelDir/voices.json").bufferedReader().use { it.readText() }
        }.getOrNull()

        // 2. Try direct absolute path
        if (jsonContent.isNullOrBlank()) {
            jsonContent = runCatching {
                val f = File(modelDir, "voices.json")
                if (f.isFile && f.canRead()) f.readText() else null
            }.getOrNull()
        }

        // 3. Try app external files dir
        if (jsonContent.isNullOrBlank()) {
            jsonContent = runCatching {
                val f = File(context.getExternalFilesDir("models"), "$modelDir/voices.json")
                if (f.isFile && f.canRead()) f.readText() else null
            }.getOrNull()
        }

        // 4. Try external storage: /sdcard/sherpa-onnx/tts/
        if (jsonContent.isNullOrBlank()) {
            jsonContent = runCatching {
                val f = File("/sdcard/sherpa-onnx/tts/$modelDir/voices.json")
                if (f.isFile && f.canRead()) f.readText() else null
            }.getOrNull()
        }

        if (!jsonContent.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(jsonContent)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optInt("id", i)
                    val name = obj.optString("name", "发音人 $id")
                    val gender = obj.optString("gender", if (id % 2 == 0) "女声" else "男声")
                    val f0 = obj.optDouble("f0", 0.0).toFloat()
                    var desc = obj.optString("desc", "")
                    var tag = obj.optString("tag", "")

                    if (desc.isBlank()) {
                        desc = if (gender == "男声") {
                            if (f0 > 0 && f0 < 115) "深沉男低音 · ${f0.toInt()}Hz"
                            else if (f0 >= 115 && f0 < 135) "醇厚男中音 · ${f0.toInt()}Hz"
                            else if (f0 >= 135) "清朗青年男声 · ${f0.toInt()}Hz"
                            else "男声"
                        } else {
                            if (f0 > 0 && f0 < 200) "温和女中音 · ${f0.toInt()}Hz"
                            else if (f0 >= 200 && f0 < 245) "清丽少女音 · ${f0.toInt()}Hz"
                            else if (f0 >= 245) "明亮高音女声 · ${f0.toInt()}Hz"
                            else "女声"
                        }
                    }

                    if (tag.isBlank()) {
                        tag = when {
                            desc.contains("少女") -> "少女"
                            desc.contains("御姐") || desc.contains("女中音") -> "御姐"
                            desc.contains("萌音") || desc.contains("幼童") -> "萌音"
                            desc.contains("大叔") || desc.contains("男低音") -> "大叔"
                            desc.contains("青年") || desc.contains("男中音") -> "青年"
                            gender == "男声" -> "青年"
                            else -> "少女"
                        }
                    }

                    list.add(SpeakerInfo(id = id, name = name, gender = gender, f0 = f0, desc = desc, tag = tag))
                }
            }.onFailure {
                Log.e(SPEAKER_TAG, "Failed to parse voices.json", it)
            }
        }

        if (list.isEmpty()) {
            val total = TtsEngine.tts?.numSpeakers() ?: 174
            for (i in 0 until total) {
                list.add(SpeakerInfo(id = i, name = "发音人 $i", gender = "女声", desc = "标准音色", tag = "播音"))
            }
        }

        cachedSpeakers = list
        cachedModelDir = modelDir
        return list
    }

    fun updateSpeaker(updated: SpeakerInfo) {
        cachedSpeakers = cachedSpeakers?.map { if (it.id == updated.id) updated else it }
    }

    fun saveSpeakers(context: Context, modelDir: String = TtsEngine.modelDir ?: "vits-zh-aishell3", speakers: List<SpeakerInfo>): Boolean {
        cachedSpeakers = speakers
        cachedModelDir = modelDir

        val jsonArray = JSONArray()
        for (spk in speakers) {
            val obj = JSONObject().apply {
                put("id", spk.id)
                put("name", spk.name)
                put("gender", spk.gender)
                put("f0", spk.f0.toDouble())
                put("desc", spk.desc)
                put("tag", spk.tag)
            }
            jsonArray.put(obj)
        }
        val content = jsonArray.toString(2)

        var saved = false

        // 1. Try app internal sandbox models dir
        runCatching {
            val dir = File(context.getExternalFilesDir("models"), modelDir)
            if (!dir.exists()) dir.mkdirs()
            val targetFile = File(dir, "voices.json")
            targetFile.writeText(content)
            saved = true
            Log.i(SPEAKER_TAG, "Saved voices.json to: ${targetFile.absolutePath}")
        }

        // 2. Try direct directory path if absolute
        runCatching {
            val directDir = File(modelDir)
            if (directDir.isDirectory && directDir.canWrite()) {
                val f = File(directDir, "voices.json")
                f.writeText(content)
                saved = true
                Log.i(SPEAKER_TAG, "Saved voices.json to: ${f.absolutePath}")
            }
        }

        // 3. Try /sdcard/sherpa-onnx/tts/
        runCatching {
            val sdDir = File("/sdcard/sherpa-onnx/tts/$modelDir")
            if (sdDir.isDirectory && sdDir.canWrite()) {
                val f = File(sdDir, "voices.json")
                f.writeText(content)
                saved = true
                Log.i(SPEAKER_TAG, "Saved voices.json to: ${f.absolutePath}")
            }
        }

        return saved
    }

    fun clearCache() {
        cachedSpeakers = null
        cachedModelDir = null
    }
}
