package com.k2fsa.sherpa.onnx.tts.engine

import PreferenceHelper
import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

const val MIN_TTS_SPEED = 0.1f
const val MAX_TTS_SPEED = 5.0f

object TtsEngine {
    var tts: OfflineTts? = null

    // https://en.wikipedia.org/wiki/ISO_639-3
    // Example:
    // eng for English,
    // deu for German
    // cmn for Mandarin
    var lang: String? = null

    // if a model supports two languages, set also lang2
    var lang2: String? = null

    // for Supertonic TTS: language code in ISO 639-1 format, e.g., "en", "zh", "ja"
    var supertonicLang: String = "en"


    val speedState: MutableState<Float> = mutableFloatStateOf(1.0F)
    val speakerIdState: MutableState<Int> = mutableIntStateOf(0)

    var speed: Float
        get() = speedState.value
        set(value) {
            speedState.value = value
        }

    var speakerId: Int
        get() = speakerIdState.value
        set(value) {
            speakerIdState.value = value
        }

    var modelDir: String? = null
    private var modelName: String? = null
    private var acousticModelName: String? = null // for matcha tts
    private var vocoder: String? = null // for matcha tts
    private var voices: String? = null // for kokoro
    private var ruleFsts: String? = null
    private var ruleFars: String? = null
    private var lexicon: String? = null
    private var dataDir: String? = null
    private var dictDir: String? = null
    private var assets: AssetManager? = null
    private var isKitten = false
    var isSupertonic = false
    private var durationPredictor: String? = null
    private var textEncoder: String? = null
    private var vectorEstimator: String? = null
    private var supertonicVocoder: String? = null
    private var ttsJson: String? = null
    private var unicodeIndexer: String? = null
    private var voiceStyle: String? = null

    init {
        // The purpose of such a design is to make the CI test easier
        // Please see
        // https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/apk/generate-tts-apk-script.py
        //
        // For VITS -- begin
        modelName = null
        // For VITS -- end

        // For Matcha -- begin
        acousticModelName = null
        vocoder = null
        // For Matcha -- end

        // For Kokoro -- begin
        voices = null
        // For Kokoro -- end

        modelDir = "vits-zh-aishell3"
        modelName = "vits-aishell3.onnx"
        ruleFars = "vits-zh-aishell3/rule.far"
        ruleFsts = "vits-zh-aishell3/phone.fst,vits-zh-aishell3/date.fst,vits-zh-aishell3/number.fst,vits-zh-aishell3/new_heteronym.fst"
        lexicon = "lexicon.txt"
        lang = "zho"
        lang2 = null

        // Please enable one and only one of the examples below

        // Example 1:
        // https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-vctk.tar.bz2
        // modelDir = "vits-vctk"
        // modelName = "vits-vctk.onnx"
        // lexicon = "lexicon.txt"
        // lang = "eng"

        // Example 2:
        // https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
        // https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low.tar.bz2
        // modelDir = "vits-piper-en_US-amy-low"
        // modelName = "en_US-amy-low.onnx"
        // dataDir = "vits-piper-en_US-amy-low/espeak-ng-data"
        // lang = "eng"

        // Example 3:
        // https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2
        // modelDir = "vits-icefall-zh-aishell3"
        // modelName = "model.onnx"
        // ruleFars = "vits-icefall-zh-aishell3/rule.far"
        // lexicon = "lexicon.txt"
        // lang = "zho"

        // Example 4:
        // https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html#csukuangfj-vits-zh-hf-fanchen-c-chinese-187-speakers
        // modelDir = "vits-zh-hf-fanchen-C"
        // modelName = "vits-zh-hf-fanchen-C.onnx"
        // lexicon = "lexicon.txt"
        // lang = "zho"

        // Example 5:
        // https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-coqui-de-css10.tar.bz2
        // This model does not need lexicon or dataDir
        // modelDir = "vits-coqui-de-css10"
        // modelName = "model.onnx"
        // lang = "deu"

        // Example 6
        // vits-melo-tts-zh_en
        // https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html#vits-melo-tts-zh-en-chinese-english-1-speaker
        // modelDir = "vits-melo-tts-zh_en"
        // modelName = "model.onnx"
        // lexicon = "lexicon.txt"
        // lang = "zho"
        // lang2 = "eng"

        // Example 7
        // matcha-icefall-zh-baker
        // https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/matcha.html#matcha-icefall-zh-baker-chinese-1-female-speaker
        // modelDir = "matcha-icefall-zh-baker"
        // acousticModelName = "model-steps-3.onnx"
        // vocoder = "vocos-22khz-univ.onnx"
        // lexicon = "lexicon.txt"
        // lang = "zho"

        // Example 8
        // matcha-icefall-en_US-ljspeech
        // https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/matcha.html#matcha-icefall-en-us-ljspeech-american-english-1-female-speaker
        // modelDir = "matcha-icefall-en_US-ljspeech"
        // acousticModelName = "model-steps-3.onnx"
        // vocoder = "vocos-22khz-univ.onnx"
        // dataDir = "matcha-icefall-en_US-ljspeech/espeak-ng-data"
        // lang = "eng"

        // Example 9
        // kokoro-en-v0_19
        // modelDir = "kokoro-en-v0_19"
        // modelName = "model.onnx"
        // voices = "voices.bin"
        // dataDir = "kokoro-en-v0_19/espeak-ng-data"
        // lang = "eng"

        // Example 10
        // kokoro-multi-lang-v1_0
        // modelDir = "kokoro-multi-lang-v1_0"
        // modelName = "model.onnx"
        // voices = "voices.bin"
        // dataDir = "kokoro-multi-lang-v1_0/espeak-ng-data"
        // lexicon = "kokoro-multi-lang-v1_0/lexicon-us-en.txt,kokoro-multi-lang-v1_0/lexicon-zh.txt"
        // lang = "eng"
        // lang2 = "zho"
        // ruleFsts = "$modelDir/phone-zh.fst,$modelDir/date-zh.fst,$modelDir/number-zh.fst"
        //
        // This model supports many languages, e.g., English, Chinese, etc.
        // We set lang to eng here.

        // Example 11
        // kitten-nano-en-v0_1-fp16
        // modelDir = "kitten-nano-en-v0_1-fp16"
        // modelName = "model.fp16.onnx"
        // voices = "voices.bin"
        // dataDir = "kitten-nano-en-v0_1-fp16/espeak-ng-data"
        // lang = "eng"
        // isKitten = true

        // Example 12
        // matcha-icefall-zh-en
        // https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese-English/matcha-icefall-zh-en.html
        // modelDir = "matcha-icefall-zh-en"
        // acousticModelName = "model-steps-3.onnx"
        // vocoder = "vocos-16khz-univ.onnx"
        // dataDir = "matcha-icefall-zh-en/espeak-ng-data"
        // lexicon = "lexicon.txt"
        // lang = "zho"

        // Example 13
        // supertonic-3-tts (supports 31 languages, default: English)
        // https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
        // modelDir = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        // isSupertonic = true
        // durationPredictor = "duration_predictor.int8.onnx"
        // textEncoder = "text_encoder.int8.onnx"
        // vectorEstimator = "vector_estimator.int8.onnx"
        // supertonicVocoder = "vocoder.int8.onnx"
        // ttsJson = "tts.json"
        // unicodeIndexer = "unicode_indexer.bin"
        // voiceStyle = "voice.bin"
        // supertonicLang = "en"  // ISO 639-1: en, zh, ja, ko, fr, de, es, etc.
    }

    fun createTts(context: Context) {
        Log.i(TAG, "Init Next-gen Kaldi TTS")
        if (tts == null) {
            val selected = PreferenceHelper(context).getSelectedModel()
            val models = ModelManager.scanModels(context)
            val target = models.find { it.dirName == selected } ?: models.first()
            loadModel(context, target)
        }
    }

    fun loadModel(context: Context, modelItem: ModelItem) {
        Log.i(TAG, "Loading model: ${modelItem.displayName} (${modelItem.rootPath})")
        tts = null

        modelDir = modelItem.dirName

        if (modelItem.isAsset && modelItem.dirName == "vits-zh-aishell3") {
            assets = context.assets
            modelDir = "vits-zh-aishell3"
            modelName = "vits-aishell3.onnx"
            acousticModelName = null
            vocoder = null
            voices = null
            ruleFars = "vits-zh-aishell3/rule.far"
            ruleFsts = "vits-zh-aishell3/phone.fst,vits-zh-aishell3/date.fst,vits-zh-aishell3/number.fst,vits-zh-aishell3/new_heteronym.fst"
            lexicon = "lexicon.txt"
            dataDir = null
            dictDir = ""
            lang = "zho"
            lang2 = null
            isKitten = false
            isSupertonic = false
        } else {
            assets = null
            val dir = File(modelItem.rootPath)
            val files = dir.list()?.toSet() ?: emptySet()
            modelDir = dir.absolutePath

            when {
                files.contains("voices.bin") -> {
                    // Kokoro model
                    modelName = if (files.contains("model.fp16.onnx")) "model.fp16.onnx" else "model.onnx"
                    voices = "voices.bin"
                    acousticModelName = null
                    vocoder = null

                    val lexList = mutableListOf<String>()
                    if (files.contains("lexicon-us-en.txt")) lexList.add("${dir.absolutePath}/lexicon-us-en.txt")
                    if (files.contains("lexicon-zh.txt")) lexList.add("${dir.absolutePath}/lexicon-zh.txt")
                    if (lexList.isEmpty() && files.contains("lexicon.txt")) lexList.add("${dir.absolutePath}/lexicon.txt")
                    lexicon = lexList.joinToString(",")

                    dataDir = if (File(dir, "espeak-ng-data").isDirectory) "${dir.absolutePath}/espeak-ng-data" else ""
                    dictDir = if (File(dir, "dict").isDirectory) "${dir.absolutePath}/dict" else ""
                    ruleFars = null

                    val fstList = mutableListOf<String>()
                    if (files.contains("phone-zh.fst")) fstList.add("${dir.absolutePath}/phone-zh.fst")
                    if (files.contains("date-zh.fst")) fstList.add("${dir.absolutePath}/date-zh.fst")
                    if (files.contains("number-zh.fst")) fstList.add("${dir.absolutePath}/number-zh.fst")
                    ruleFsts = if (fstList.isNotEmpty()) fstList.joinToString(",") else null

                    lang = "zho"
                    lang2 = "eng"
                    isKitten = false
                    isSupertonic = false
                }
                files.any { it.contains("acoustic") || it.contains("matcha") } && files.any { it.contains("vocoder") || it.contains("hifigan") } -> {
                    // Matcha-TTS
                    modelName = null
                    voices = null
                    acousticModelName = files.first { it.contains("acoustic") || it.contains("matcha") }
                    vocoder = files.first { it.contains("vocoder") || it.contains("hifigan") }
                    lexicon = if (files.contains("lexicon.txt")) "${dir.absolutePath}/lexicon.txt" else ""
                    dataDir = if (File(dir, "espeak-ng-data").isDirectory) "${dir.absolutePath}/espeak-ng-data" else ""
                    dictDir = if (File(dir, "dict").isDirectory) "${dir.absolutePath}/dict" else ""
                    ruleFars = if (files.contains("rule.far")) "${dir.absolutePath}/rule.far" else null
                    ruleFsts = null
                    lang = "zho"
                    lang2 = null
                    isKitten = false
                    isSupertonic = false
                }
                files.any { it.endsWith(".onnx") } -> {
                    // Generic VITS (AISHELL-3, Piper, Baker, VCTK, etc.)
                    modelName = files.first { it.endsWith(".onnx") }
                    voices = null
                    acousticModelName = null
                    vocoder = null
                    lexicon = if (files.contains("lexicon.txt")) "${dir.absolutePath}/lexicon.txt" else ""
                    dataDir = if (File(dir, "espeak-ng-data").isDirectory) "${dir.absolutePath}/espeak-ng-data" else ""
                    dictDir = if (File(dir, "dict").isDirectory) "${dir.absolutePath}/dict" else ""
                    ruleFars = if (files.contains("rule.far")) "${dir.absolutePath}/rule.far" else null

                    val fstList = mutableListOf<String>()
                    if (files.contains("phone.fst")) fstList.add("${dir.absolutePath}/phone.fst")
                    if (files.contains("date.fst")) fstList.add("${dir.absolutePath}/date.fst")
                    if (files.contains("number.fst")) fstList.add("${dir.absolutePath}/number.fst")
                    if (files.contains("new_heteronym.fst")) fstList.add("${dir.absolutePath}/new_heteronym.fst")
                    ruleFsts = if (fstList.isNotEmpty()) fstList.joinToString(",") else null

                    lang = "zho"
                    lang2 = null
                    isKitten = false
                    isSupertonic = false
                }
            }
        }

        val config = getOfflineTtsConfig(
            modelDir = modelDir!!,
            modelName = modelName ?: "",
            acousticModelName = acousticModelName ?: "",
            vocoder = vocoder ?: "",
            voices = voices ?: "",
            lexicon = lexicon ?: "",
            dataDir = dataDir ?: "",
            dictDir = dictDir ?: "",
            ruleFsts = ruleFsts ?: "",
            ruleFars = ruleFars ?: "",
            isKitten = isKitten,
            isSupertonic = isSupertonic,
            durationPredictor = durationPredictor ?: "",
            textEncoder = textEncoder ?: "",
            vectorEstimator = vectorEstimator ?: "",
            supertonicVocoder = supertonicVocoder ?: "",
            ttsJson = ttsJson ?: "",
            unicodeIndexer = unicodeIndexer ?: "",
            voiceStyle = voiceStyle ?: "",
        )

        speed = PreferenceHelper(context).getSpeed()
        speakerId = PreferenceHelper(context).getSid()

        tts = OfflineTts(assetManager = assets, config = config)
        SpeakerManager.clearCache()

        val maxSid = (tts?.numSpeakers() ?: 1) - 1
        if (speakerId > maxSid || speakerId < 0) {
            speakerId = 0
            PreferenceHelper(context).setSid(0)
        }
        Log.i(TAG, "Model loaded: ${modelItem.displayName}, speakers=${tts?.numSpeakers()}, sampleRate=${tts?.sampleRate()}")
    }


    private fun copyDataDir(context: Context, dataDir: String): String {
        Log.i(TAG, "data dir is $dataDir")
        copyAssets(context, dataDir)

        val newDataDir = context.getExternalFilesDir(null)!!.absolutePath
        Log.i(TAG, "newDataDir: $newDataDir")
        return newDataDir
    }

    private fun copyAssets(context: Context, path: String) {
        val assets: Array<String>?
        try {
            assets = context.assets.list(path)
            if (assets!!.isEmpty()) {
                copyFile(context, path)
            } else {
                val fullPath = "${context.getExternalFilesDir(null)}/$path"
                val dir = File(fullPath)
                dir.mkdirs()
                for (asset in assets.iterator()) {
                    val p: String = if (path == "") "" else "$path/"
                    copyAssets(context, p + asset)
                }
            }
        } catch (ex: IOException) {
            Log.e(TAG, "Failed to copy $path. $ex")
        }
    }

    private fun copyFile(context: Context, filename: String) {
        try {
            val istream = context.assets.open(filename)
            val newFilename = context.getExternalFilesDir(null).toString() + "/" + filename
            val ostream = FileOutputStream(newFilename)
            // Log.i(TAG, "Copying $filename to $newFilename")
            val buffer = ByteArray(1024)
            var read = 0
            while (read != -1) {
                ostream.write(buffer, 0, read)
                read = istream.read(buffer)
            }
            istream.close()
            ostream.flush()
            ostream.close()
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to copy $filename, $ex")
        }
    }
}
