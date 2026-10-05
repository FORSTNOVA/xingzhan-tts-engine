package com.k2fsa.sherpa.onnx.tts.engine

import android.media.AudioFormat
import PreferenceHelper
import com.k2fsa.sherpa.onnx.GenerationConfig
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/*
https://developer.android.com/reference/java/util/Locale#getISO3Language()
https://developer.android.com/reference/java/util/Locale#getISO3Country()

eng, USA,
eng, USA, POSIX
eng,
eng, GBR
afr,
afr, NAM
afr, ZAF
agq
agq, CMR
aka,
aka, GHA
amh,
amh, ETH
ara,
ara, 001
ara, ARE
ara, BHR,
deu
deu, AUT
deu, BEL
deu, CHE
deu, ITA
deu, ITA
deu, LIE
deu, LUX
spa,
spa, 419
spa, ARG,
spa, BRA
fra,
fra, BEL,
fra, FRA,

E  Failed to check TTS data, no activity found for Intent
{ act=android.speech.tts.engine.CHECK_TTS_DATA pkg=com.k2fsa.sherpa.chapter5 })

E Failed to get default language from engine com.k2fsa.sherpa.chapter5
Engine failed voice data integrity check (null return)com.k2fsa.sherpa.chapter5
Failed to get default language from engine com.k2fsa.sherpa.chapter5

*/

class TtsService : TextToSpeechService() {
    override fun onCreate() {
        Log.i(TAG, "onCreate tts service")
        super.onCreate()

        // see https://github.com/Miserlou/Android-SDK-Samples/blob/master/TtsEngine/src/com/example/android/ttsengine/RobotSpeakTtsService.java#L68
        onLoadLanguage(TtsEngine.lang, "", "")
        if (TtsEngine.lang2 != null) {
            onLoadLanguage(TtsEngine.lang2, "", "")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy tts service")
        super.onDestroy()
    }

    // https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeechService#onislanguageavailable
    override fun onIsLanguageAvailable(_lang: String?, _country: String?, _variant: String?): Int {
        val lang = _lang ?: ""

        if (lang == TtsEngine.lang || lang == TtsEngine.lang2) {
            return TextToSpeech.LANG_AVAILABLE
        }

        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf(TtsEngine.lang!!, "", "")
    }

    // https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeechService#onLoadLanguage(kotlin.String,%20kotlin.String,%20kotlin.String)
    override fun onLoadLanguage(_lang: String?, _country: String?, _variant: String?): Int {
        Log.i(TAG, "onLoadLanguage: $_lang, $_country")
        val lang = _lang ?: ""

        return if (lang == TtsEngine.lang || lang == TtsEngine.lang2) {
            Log.i(TAG, "creating tts, lang :$lang")
            TtsEngine.createTts(application)
            TextToSpeech.LANG_AVAILABLE
        } else {
            Log.i(TAG, "lang $lang not supported, tts engine lang: ${TtsEngine.lang}, ${TtsEngine.lang2}")
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun getSpeakerMetadataMap(): Map<Int, Pair<String, String>> {
        val map = HashMap<Int, Pair<String, String>>()
        val modelDir = TtsEngine.modelDir ?: "vits-zh-aishell3"
        // 1. Prioritize application assets (bundled in APK, always accessible without storage permission)
        var jsonContent: String? = runCatching {
            application.assets.open("$modelDir/voices.json").bufferedReader().use { it.readText() }
        }.getOrNull()

        // 2. If assets was empty, fallback to external storage safely
        if (jsonContent.isNullOrBlank()) {
            jsonContent = runCatching {
                val externalFile = File("/sdcard/sherpa-onnx/tts/$modelDir/voices.json")
                if (externalFile.isFile && externalFile.canRead()) {
                    externalFile.readText()
                } else null
            }.getOrNull()
        }

        Log.i(TAG, "voices.json read result: length=${jsonContent?.length}")

        if (!jsonContent.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(jsonContent)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optInt("id", -1)
                    if (id >= 0) {
                        val name = obj.optString("name", "发音人 $id")
                        val gender = obj.optString("gender", "")
                        map[id] = Pair(name, gender)
                    }
                }
            }.onFailure {
                Log.e(TAG, "Failed to parse voices.json", it)
            }
        }
        Log.i(TAG, "getSpeakerMetadataMap: loaded ${map.size} speakers")
        return map
    }

    override fun onGetVoices(): List<Voice> {
        TtsEngine.createTts(application)
        val tts = TtsEngine.tts
        val numSpeakers = tts?.numSpeakers() ?: 1
        val locale = Locale.SIMPLIFIED_CHINESE
        val voices = ArrayList<Voice>(numSpeakers)
        if (numSpeakers <= 1) {
            voices.add(Voice("zh", locale, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, emptySet()))
        } else {
            val speakers = SpeakerManager.getSpeakers(application)
            val speakerMap = speakers.associateBy { it.id }
            for (i in 0 until numSpeakers) {
                val spk = speakerMap[i]
                val gender = spk?.gender ?: if (i % 2 == 0) "女声" else "男声"
                val displayName = spk?.name ?: "speaker-$i"
                val tag = if (!spk?.tag.isNullOrBlank()) "·${spk?.tag}" else ""
                val voiceName = "speaker-$i: $displayName ($gender$tag)"
                voices.add(Voice(
                    voiceName,
                    locale,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL,
                    false,
                    setOf("speaker-$i", gender, spk?.tag ?: "")
                ))
            }
        }
        return voices
    }

    private fun extractSpeakerId(voiceName: String?): Int? {
        if (voiceName.isNullOrEmpty()) return null
        val match = Regex("speaker-(\\d+)").find(voiceName)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        if (voiceName == "zh") return TextToSpeech.SUCCESS
        val id = extractSpeakerId(voiceName)
        if (id != null && id in 0 until (TtsEngine.tts?.numSpeakers() ?: 174)) {
            return TextToSpeech.SUCCESS
        }
        return TextToSpeech.ERROR
    }

    override fun onLoadVoice(voiceName: String?): Int {
        return onIsValidVoiceName(voiceName)
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val num = TtsEngine.tts?.numSpeakers() ?: 1
        if (num > 1) {
            val id = TtsEngine.speakerId
            val spk = SpeakerManager.getSpeakers(application).find { it.id == id }
            val gender = spk?.gender ?: if (id % 2 == 0) "女声" else "男声"
            val displayName = spk?.name ?: "speaker-$id"
            val tag = if (!spk?.tag.isNullOrBlank()) "·${spk?.tag}" else ""
            return "speaker-$id: $displayName ($gender$tag)"
        } else {
            return "zh"
        }
    }

    override fun onStop() {}

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            return
        }
        val language = request.language
        val country = request.country
        val variant = request.variant
        val text = request.charSequenceText.toString()
        // Map Android TTS speech rate (where 100 == normal) to engine speed (1.0 == normal)
        // Allow per-request override from external apps; fallback to engine default if absent.
        val rate = runCatching { request.speechRate }.getOrDefault(-1)
        val engineSpeed = if (rate > 0) {
            // Map 100 -> 1.0f
            val mapped = rate / 100.0f
            mapped.coerceIn(MIN_TTS_SPEED, MAX_TTS_SPEED)
        } else {
            // Fallback to current engine/global setting
            TtsEngine.speed
        }

        val ret = onIsLanguageAvailable(language, country, variant)
        if (ret == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.error()
            return
        }
        val tts = TtsEngine.tts ?: run {
            Log.e(TAG, "TtsEngine.tts is null in onSynthesizeText, returning error")
            callback.error()
            return
        }

        val voiceName = runCatching { request.voiceName }.getOrNull()
        val sidFromVoice = extractSpeakerId(voiceName)
        val sidFromParams = runCatching { request.params?.getString("speakerId")?.toIntOrNull() }.getOrNull()
            ?: runCatching { request.params?.getInt("speakerId", -1) }.getOrNull()?.takeIf { it >= 0 }
            ?: runCatching { request.params?.getString("sid")?.toIntOrNull() }.getOrNull()
            ?: runCatching { request.params?.getInt("sid", -1) }.getOrNull()?.takeIf { it >= 0 }

        val targetSpeakerId = sidFromVoice ?: sidFromParams ?: TtsEngine.speakerId
        Log.i(TAG, "text: $text, voiceName: $voiceName, sid: $targetSpeakerId, engineSpeed: $engineSpeed")

        // Note that AudioFormat.ENCODING_PCM_FLOAT requires API level >= 24
        // callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_FLOAT, 1)

        callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1)

        if (text.isBlank() || text.isEmpty()) {
            callback.done()
            return
        }

        // Universal Emotion Parsing: e.g. [愤怒:0.8] 你在做什么！ or <emotion type="sad" intensity="0.5">为什么...</emotion>
        val (parsedEmotion, cleanText, parsedIntensity) = EmotionEngine.parseTextWithEmotionTag(text)
        val textToSpeak = cleanText.ifBlank { text }
        val effectiveIntensity = parsedIntensity ?: 0.85f
        val emotionParams = if (parsedEmotion != null) {
            EmotionEngine.calculate(parsedEmotion, effectiveIntensity, textToSpeak)
        } else {
            EmotionEngine.calculate(EmotionType.NEUTRAL, 0.0f, textToSpeak)
        }

        val effectiveSpeed = if (parsedEmotion != null) {
            engineSpeed * emotionParams.speed
        } else {
            engineSpeed
        }

        // 🌟 Kokoro 高阶特异层: 伴生情绪声色协同路由 (仅在当前激活模型为 Kokoro 时开启)
        val isKokoroActive = KokoroStyleEngine.isKokoro(TtsEngine.modelDir)
        val isCompanionEnabled = PreferenceHelper(application).getKokoroCompanionEnabled()
        var effectiveSid = targetSpeakerId

        if (isKokoroActive && isCompanionEnabled && parsedEmotion != null && parsedEmotion != EmotionType.NEUTRAL) {
            val metaMap = getSpeakerMetadataMap()
            val baseGender = metaMap[targetSpeakerId]?.second ?: if (targetSpeakerId in 3..57) "女声" else "男声"
            val isFemale = baseGender.contains("女")
            effectiveSid = KokoroStyleEngine.getEmotionalCompanionSid(
                baseSid = targetSpeakerId,
                emotion = parsedEmotion,
                isFemale = isFemale,
                intensity = effectiveIntensity
            )
            Log.i(TAG, "Kokoro Specific Layer: routed sid $targetSpeakerId -> $effectiveSid for emotion $parsedEmotion ($baseGender) with intensity $effectiveIntensity")
        }

        val ttsCallback: (FloatArray) -> Int = fun(floatSamples): Int {
            // convert FloatArray to ByteArray
            val samples = floatArrayToByteArray(floatSamples)
            val maxBufferSize: Int = callback.maxBufferSize
            var offset = 0
            while (offset < samples.size) {
                val bytesToWrite = Math.min(maxBufferSize, samples.size - offset)
                callback.audioAvailable(samples, offset, bytesToWrite)
                offset += bytesToWrite
            }

            // 1 means to continue
            // 0 means to stop
            return 1
        }

        try {
            tts.generateWithConfigAndCallback(
                text = emotionParams.processedText,
                config = GenerationConfig(
                    sid = effectiveSid,
                    speed = effectiveSpeed,
                    silenceScale = emotionParams.silenceScale
                ),
                callback = ttsCallback,
            )
            callback.done()
        } catch (e: Exception) {
            Log.e(TAG, "TTS synthesis failed in service", e)
            callback.error()
        }
    }

    private fun floatArrayToByteArray(audio: FloatArray): ByteArray {
        // byteArray is actually a ShortArray
        val byteArray = ByteArray(audio.size * 2)
        for (i in audio.indices) {
            val sample = (audio[i] * 32767).toInt()
            byteArray[2 * i] = sample.toByte()
            byteArray[2 * i + 1] = (sample shr 8).toByte()
        }
        return byteArray
    }
}
