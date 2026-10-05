package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import java.io.File

data class ModelItem(
    val dirName: String,
    val displayName: String,
    val type: String,
    val isAsset: Boolean,
    val rootPath: String
)

object ModelManager {

    fun scanModels(context: Context): List<ModelItem> {
        val list = mutableListOf<ModelItem>()
        val seen = mutableSetOf<String>()

        // 1. Built-in AISHELL-3
        list.add(
            ModelItem(
                dirName = "vits-zh-aishell3",
                displayName = "AISHELL-3 普通话 (内置 174 音色)",
                type = "VITS",
                isAsset = true,
                rootPath = "assets://vits-zh-aishell3"
            )
        )
        seen.add("vits-zh-aishell3")

        // 2. Scan app-specific external files: getExternalFilesDir("models")
        runCatching {
            val appDir = context.getExternalFilesDir("models")
            if (appDir != null && appDir.isDirectory) {
                appDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                    val name = subDir.name
                    if (!seen.contains(name)) {
                        seen.add(name)
                        val type = detectModelType(subDir)
                        val displayName = when {
                            name.contains("kokoro") -> "Kokoro-82M 中英多音色 (103 音色 · 24kHz · Style向量)"
                            else -> "${subDir.name} (离线模型)"
                        }
                        list.add(
                            ModelItem(
                                dirName = subDir.name,
                                displayName = displayName,
                                type = type,
                                isAsset = false,
                                rootPath = subDir.absolutePath
                            )
                        )
                    }
                }
            }
        }

        // 3. Scan external storage: /sdcard/sherpa-onnx/tts/
        runCatching {
            val extDir = File("/sdcard/sherpa-onnx/tts")
            if (extDir.isDirectory && extDir.canRead()) {
                extDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                    val name = subDir.name
                    if (!seen.contains(name)) {
                        seen.add(name)
                        val type = detectModelType(subDir)
                        val displayName = when {
                            name.contains("kokoro") -> "Kokoro-82M 中英多音色 (103 音色 · 24kHz · Style向量)"
                            else -> "${subDir.name} (外部存储)"
                        }
                        list.add(
                            ModelItem(
                                dirName = name,
                                displayName = displayName,
                                type = type,
                                isAsset = false,
                                rootPath = subDir.absolutePath
                            )
                        )
                    }
                }
            }
        }

        return list
    }

    private fun detectModelType(dir: File): String {
        val files = dir.list()?.toSet() ?: emptySet()
        return when {
            files.contains("voices.bin") -> "Kokoro (支持Style向量)"
            files.any { it.contains("vocos") || it.contains("vocoder") } -> "Matcha-TTS (流匹配)"
            files.any { it.contains("vector_estimator") } -> "Supertonic (多语言风格)"
            files.any { it.endsWith(".onnx") } -> "VITS (多音色离线)"
            else -> "未知 TTS 架构"
        }
    }
}
