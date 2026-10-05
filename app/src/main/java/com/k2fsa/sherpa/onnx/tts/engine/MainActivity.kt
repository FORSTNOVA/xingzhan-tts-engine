@file:OptIn(ExperimentalMaterial3Api::class)

package com.k2fsa.sherpa.onnx.tts.engine

import PreferenceHelper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.tts.engine.ui.theme.SherpaOnnxTtsEngineTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.TimeSource

const val TAG = "sherpa-onnx-tts-engine"

class MainActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var track: AudioTrack
    private val trackLock = Any()
    private var stopped: Boolean = false
    private var samplesChannel = Channel<FloatArray>(capacity = 128)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var synthesisPlaybackJob: Job? = null
    private var synthesisGenJob: Job? = null

    fun safeStopAndFlushTrack() {
        synchronized(trackLock) {
            runCatching {
                if (::track.isInitialized && track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                }
            }
        }
    }

    fun safePlayTrack() {
        synchronized(trackLock) {
            runCatching {
                if (::track.isInitialized && track.state == AudioTrack.STATE_INITIALIZED) {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }
                }
            }
        }
    }

    fun safeWriteTrack(samples: FloatArray, offset: Int, count: Int): Int {
        return synchronized(trackLock) {
            runCatching {
                if (::track.isInitialized && track.state == AudioTrack.STATE_INITIALIZED) {
                    track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                } else 0
            }.getOrDefault(0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "Initializing TTS Engine...")
        TtsEngine.createTts(this)

        Log.i(TAG, "Initializing AudioTrack...")
        initAudioTrack()

        val preferenceHelper = PreferenceHelper(this)

        setContent {
            SherpaOnnxTtsEngineTheme {
                MainScreen(preferenceHelper)
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun MainScreen(preferenceHelper: PreferenceHelper) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        var speed by remember { mutableFloatStateOf(preferenceHelper.getSpeed()) }
        var currentSid by remember { mutableIntStateOf(preferenceHelper.getSid()) }
        var favorites by remember { mutableStateOf(preferenceHelper.getFavorites()) }
        val models = remember { ModelManager.scanModels(context) }
        var currentModel by remember { mutableStateOf(models.firstOrNull { it.dirName == preferenceHelper.getSelectedModel() } ?: models.first()) }
        var speakers by remember { mutableStateOf(SpeakerManager.getSpeakers(context, currentModel.dirName)) }

        val presetPrompts = listOf(
            "日常对话" to "你好，欢迎来到酒馆，我是发音人。",
            "霸气宣言" to "世界的法则由胜利者书写，而我绝不认输！",
            "温柔低语" to "夜深了，早点休息吧，明天也是充满希望的一天。",
            "故事悬疑" to "迷雾笼罩的森林深处，传来了一阵若有若无的钟声。",
            "诗词朗诵" to "白日依山尽，黄河入海流。欲穷千里目，更上一层楼。"
        )

        var testText by remember { mutableStateOf(presetPrompts[0].second) }
        var isGenerating by remember { mutableStateOf(false) }
        var isPlaying by remember { mutableStateOf(false) }
        var rtfStats by remember { mutableStateOf<RtfResult?>(null) }
        var showSpeakerSheet by remember { mutableStateOf(false) }
        var showModelDialog by remember { mutableStateOf(false) }
        var showGuideSheet by remember { mutableStateOf(false) }
        var showClassificationWorkbench by remember { mutableStateOf(false) }
        var currentEmotion by remember { mutableStateOf(EmotionType.NEUTRAL) }
        var emotionIntensity by remember { mutableFloatStateOf(0.85f) }
        var kokoroCompanionEnabled by remember { mutableStateOf(preferenceHelper.getKokoroCompanionEnabled()) }
        var showStyleBlenderDialog by remember { mutableStateOf(false) }

        val currentSpeaker = speakers.find { it.id == currentSid } ?: speakers.firstOrNull() ?: SpeakerInfo(0, "发音人 0", "女声", 220f, "标准音色")

        val saveLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("audio/wav")
        ) { uri ->
            if (uri != null) {
                try {
                    val srcFile = File(application.filesDir.absolutePath + "/generated.wav")
                    contentResolver.openOutputStream(uri)?.use { output ->
                        srcFile.inputStream().use { input -> input.copyTo(output) }
                    }
                    Toast.makeText(applicationContext, "音频已成功保存", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save audio: $e")
                    Toast.makeText(applicationContext, "保存音频失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

        fun runSynthesis(text: String, sid: Int, onDone: () -> Unit = {}) {
            if (text.isBlank()) {
                Toast.makeText(context, "请输入合成文本", Toast.LENGTH_SHORT).show()
                return
            }
            val tts = TtsEngine.tts
            if (tts == null) {
                Toast.makeText(context, "TTS引擎尚未初始化，请稍候", Toast.LENGTH_SHORT).show()
                return
            }

            // Cancel any in-flight playback or generation
            stopped = true
            synthesisPlaybackJob?.cancel()
            synthesisGenJob?.cancel()

            // Drain old samples
            while (!samplesChannel.isEmpty) {
                samplesChannel.tryReceive().getOrNull()
            }

            safeStopAndFlushTrack()
            safePlayTrack()

            stopped = false
            isGenerating = true

            synthesisPlaybackJob = scope.launch {
                for (samples in samplesChannel) {
                    if (samples.isEmpty() || stopped || !isActive) break
                    val chunkSize = 2048
                    var offset = 0
                    while (isActive && !stopped && offset < samples.size) {
                        val length = minOf(chunkSize, samples.size - offset)
                        val written = safeWriteTrack(samples, offset, length)
                        if (written <= 0) break
                        offset += length
                    }
                    if (stopped || !isActive) break
                }
                while (!samplesChannel.isEmpty) {
                    samplesChannel.tryReceive().getOrNull()
                }
            }

            synthesisGenJob = CoroutineScope(Dispatchers.Default).launch {
                val timeSource = TimeSource.Monotonic
                val startTime = timeSource.markNow()
                val (parsedEmotion, cleanText, parsedIntensity) = EmotionEngine.parseTextWithEmotionTag(text)
                val activeEmotion = parsedEmotion ?: currentEmotion
                val activeIntensity = parsedIntensity ?: emotionIntensity
                val emotionParams = EmotionEngine.calculate(activeEmotion, activeIntensity, cleanText.ifBlank { text })
                val effectiveSpeed = speed * emotionParams.speed

                val isKokoro = currentModel.dirName.contains("kokoro")
                val isFemale = currentSpeaker.gender.contains("女")
                val effectiveSid = if (isKokoro && kokoroCompanionEnabled && activeEmotion != EmotionType.NEUTRAL) {
                    KokoroStyleEngine.getEmotionalCompanionSid(sid, activeEmotion, isFemale, activeIntensity)
                } else {
                    sid
                }

                val genConfig = GenerationConfig(
                    sid = effectiveSid,
                    speed = effectiveSpeed,
                    silenceScale = emotionParams.silenceScale
                )
                if (TtsEngine.isSupertonic) {
                    genConfig.extra = mapOf("lang" to TtsEngine.supertonicLang)
                }

                try {
                    val audio = tts.generateWithConfigAndCallback(
                        text = emotionParams.processedText,
                        config = genConfig,
                        callback = ::callback
                    )

                    if (!isActive) return@launch

                    val elapsed = startTime.elapsedNow().inWholeMilliseconds.toFloat() / 1000f
                    val audioDuration = audio.samples.size / tts.sampleRate().toFloat()
                    val rtf = if (audioDuration > 0) elapsed / audioDuration else 0f

                    scope.launch {
                        samplesChannel.send(FloatArray(0))
                    }

                    val filename = application.filesDir.absolutePath + "/generated.wav"
                    if (audio.samples.isNotEmpty()) {
                        audio.save(filename)
                    }

                    withContext(Dispatchers.Main) {
                        isGenerating = false
                        isPlaying = true
                        rtfStats = RtfResult(
                            threads = tts.config.model.numThreads,
                            elapsedSec = elapsed,
                            durationSec = audioDuration,
                            rtf = rtf
                        )
                        onDone()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Synthesis failed", e)
                    withContext(Dispatchers.Main) {
                        isGenerating = false
                        Toast.makeText(context, "语音合成失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Sherpa 声学控制中心", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            val rateStr = if (currentModel.dirName.contains("kokoro")) "24kHz" else "16kHz"
                            Text("离线高保真 · $rateStr · 低延迟", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showGuideSheet = true }) {
                            Icon(Icons.Default.HelpOutline, contentDescription = "操作说明与模型导入", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = {
                            runCatching {
                                context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                            }.onFailure {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                }.onFailure {
                                    Toast.makeText(context, "请在系统设置中找到文字转语音(TTS)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "系统设置")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Engine & Model Status Card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(currentModel.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("架构: ${currentModel.type} · 已校准真实声学性别", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = { showModelDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("切换", fontSize = 12.sp)
                            }
                        }
                    }
                }

                // 2. Active Speaker Card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("当前选定音色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            IconButton(
                                onClick = {
                                    val isFav = preferenceHelper.toggleFavorite(currentSpeaker.id)
                                    favorites = preferenceHelper.getFavorites()
                                    Toast.makeText(context, if (isFav) "已添加到收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    if (favorites.contains(currentSpeaker.id.toString())) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "收藏",
                                    tint = if (favorites.contains(currentSpeaker.id.toString())) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isFemale = currentSpeaker.gender == "女声"
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(if (isFemale) Color(0xFFFF4081).copy(alpha = 0.15f) else Color(0xFF2196F3).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (isFemale) "♀" else "♂",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isFemale) Color(0xFFD81B60) else Color(0xFF1976D2)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(currentSpeaker.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        color = if (isFemale) Color(0xFFFCE4EC) else Color(0xFFE3F2FD),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            currentSpeaker.gender,
                                            color = if (isFemale) Color(0xFFC2185B) else Color(0xFF1565C0),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(currentSpeaker.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showSpeakerSheet = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("音色库 (${speakers.size})", fontSize = 13.sp)
                            }

                            OutlinedButton(
                                onClick = { showClassificationWorkbench = true },
                                modifier = Modifier.weight(1.1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("连播分类台", fontSize = 13.sp)
                            }
                        }
                    }
                }

                // 3. Audition Workbench (Text + Presets + Speed)
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("试听与演播台", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                        // Preset chips
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            presetPrompts.forEach { (label, text) ->
                                AssistChip(
                                    onClick = { testText = text },
                                    label = { Text(label, fontSize = 12.sp) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }

                        // 🎭 Universal Emotion Selector
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🎭 通用情绪表现策略", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                if (currentEmotion != EmotionType.NEUTRAL) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "强度 ${(emotionIntensity * 100).toInt()}%",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                EmotionType.values().forEach { emotion ->
                                    val isSelected = currentEmotion == emotion
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { currentEmotion = emotion },
                                        label = { Text("${emotion.icon} ${emotion.label}", fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors()
                                    )
                                }
                            }

                            if (currentEmotion != EmotionType.NEUTRAL) {
                                val currentParams = EmotionEngine.calculate(currentEmotion, emotionIntensity, testText)
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "⚡ 动态声学调制: 语速 ${String.format("%.2f", currentParams.speed)}x · 停顿 ${String.format("%.2f", currentParams.silenceScale)}s",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = emotionIntensity,
                                            onValueChange = { emotionIntensity = it },
                                            valueRange = 0.1f..1.0f,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        // ✨ Kokoro 高阶特异层 (仅在当前激活模型为 Kokoro 时开启)
                        val isKokoro = currentModel.dirName.contains("kokoro")
                        if (isKokoro) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text("✨ Kokoro 高阶特异层", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        "Style 协同",
                                                        fontSize = 9.sp,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            Text("双轨情绪联动 · 物理时域与声色特征协同演变", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(
                                            checked = kokoroCompanionEnabled,
                                            onCheckedChange = {
                                                kokoroCompanionEnabled = it
                                                preferenceHelper.setKokoroCompanionEnabled(it)
                                            }
                                        )
                                    }

                                    if (kokoroCompanionEnabled && currentEmotion != EmotionType.NEUTRAL) {
                                        val isFemale = currentSpeaker.gender.contains("女")
                                        val companionSid = KokoroStyleEngine.getEmotionalCompanionSid(
                                            baseSid = currentSid,
                                            emotion = currentEmotion,
                                            isFemale = isFemale,
                                            intensity = emotionIntensity
                                        )
                                        val companionSpeaker = speakers.find { it.id == companionSid }
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("🧬", fontSize = 16.sp)
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        "伴生声色: #${companionSpeaker?.id} ${companionSpeaker?.name ?: ""}",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        KokoroStyleEngine.getCompanionDesc(currentEmotion, isFemale),
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = { showStyleBlenderDialog = true },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("🧪 风格向量融炼器 (双音色融合)", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = testText,
                            onValueChange = { testText = it },
                            label = { Text("试听演播文本") },
                            trailingIcon = {
                                if (testText.isNotEmpty()) {
                                    IconButton(onClick = { testText = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "清空")
                                    }
                                }
                            },
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )

                        // Speed slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("演播语速: ${String.format("%.2f", speed)}x", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    when {
                                        speed < 0.9f -> "低缓沉思"
                                        speed > 1.15f -> "紧凑激昂"
                                        else -> "标准自然"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = speed,
                                onValueChange = {
                                    speed = it
                                    TtsEngine.speed = it
                                    preferenceHelper.setSpeed(it)
                                },
                                valueRange = 0.5f..2.0f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Action Buttons
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (isGenerating || isPlaying) {
                                        onClickStop()
                                        isGenerating = false
                                        isPlaying = false
                                    } else {
                                        runSynthesis(testText, currentSid)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isGenerating || isPlaying) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    if (isGenerating || isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isGenerating) "正在生成音频…" else if (isPlaying) "停止演播" else "合成并演播",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { saveLauncher.launch("speech_${currentSid}.wav") },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Upload, contentDescription = "导出", modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("导出 WAV")
                                }

                                OutlinedButton(
                                    onClick = {
                                        val file = File(application.filesDir.absolutePath + "/generated.wav")
                                        if (!file.exists()) {
                                            Toast.makeText(context, "请先合成一段音频", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "com.k2fsa.sherpa.onnx.tts.engine.fileprovider",
                                                file
                                            )
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "audio/wav"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            startActivity(Intent.createChooser(intent, "分享音频"))
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "分享", modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("分享音频")
                                }
                            }
                        }

                        // RTF Diagnostics
                        AnimatedVisibility(visible = rtfStats != null) {
                            rtfStats?.let { stats ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("RTF: ${String.format("%.3f", stats.rtf)} (极速)", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        Text("耗时: ${String.format("%.0f", stats.elapsedSec * 1000)}ms", fontSize = 12.sp)
                                        Text("时长: ${String.format("%.1f", stats.durationSec)}s", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Sheet: Speaker Picker
            if (showSpeakerSheet) {
                SpeakerPickerBottomSheet(
                    speakers = speakers,
                    currentSid = currentSid,
                    favorites = favorites,
                    onSelect = { selectedId ->
                        currentSid = selectedId
                        TtsEngine.speakerId = selectedId
                        preferenceHelper.setSid(selectedId)
                        showSpeakerSheet = false
                    },
                    onToggleFavorite = { id ->
                        preferenceHelper.toggleFavorite(id)
                        favorites = preferenceHelper.getFavorites()
                    },
                    onQuickPreview = { sid ->
                        runSynthesis(testText.take(15).ifBlank { "你好，这是试听。" }, sid)
                    },
                    onOpenClassificationWorkbench = {
                        showSpeakerSheet = false
                        showClassificationWorkbench = true
                    },
                    onDismiss = { showSpeakerSheet = false }
                )
            }

            // Bottom Sheet: Classification Workbench
            if (showClassificationWorkbench) {
                VoiceClassificationWorkbench(
                    speakers = speakers,
                    currentModelDir = currentModel.dirName,
                    track = track,
                    onSpeakerUpdated = { updatedSpk ->
                        SpeakerManager.updateSpeaker(updatedSpk)
                        speakers = SpeakerManager.getSpeakers(context, currentModel.dirName)
                    },
                    onSaveAll = { updatedList ->
                        SpeakerManager.saveSpeakers(context, currentModel.dirName, updatedList)
                        speakers = SpeakerManager.getSpeakers(context, currentModel.dirName)
                    },
                    onDismiss = { showClassificationWorkbench = false }
                )
            }

            // Dialog: Model Switcher
            if (showStyleBlenderDialog) {
                StyleBlenderDialog(
                    speakers = speakers,
                    currentSid = currentSid,
                    currentModelDir = currentModel.dirName,
                    onBlended = { newSpk ->
                        SpeakerManager.invalidateCache()
                        speakers = SpeakerManager.getSpeakers(context, currentModel.dirName)
                        currentSid = newSpk.id
                        TtsEngine.speakerId = newSpk.id
                        preferenceHelper.setSid(newSpk.id)
                    },
                    onDismiss = { showStyleBlenderDialog = false }
                )
            }

            if (showModelDialog) {
                AlertDialog(
                    onDismissRequest = { showModelDialog = false },
                    title = { Text("切换 TTS 模型") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("扫描存储中可用的模型包：", style = MaterialTheme.typography.bodySmall)
                            models.forEach { m ->
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (m.dirName == currentModel.dirName) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (m.dirName != currentModel.dirName) {
                                                currentModel = m
                                                preferenceHelper.setSelectedModel(m.dirName)
                                                try {
                                                    TtsEngine.loadModel(context, m)
                                                    initAudioTrack()
                                                    speakers = SpeakerManager.getSpeakers(context, m.dirName)
                                                    currentSid = TtsEngine.speakerId
                                                    Toast.makeText(context, "已切换为: ${m.displayName}", Toast.LENGTH_SHORT).show()
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Failed to switch model", e)
                                                    Toast.makeText(context, "模型加载失败: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                            showModelDialog = false
                                        }
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(m.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("${m.type} · ${if (m.isAsset) "内置" else "外部存储"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    showModelDialog = false
                                    showGuideSheet = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("查看模型导入与扩展说明", fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showModelDialog = false }) { Text("完成") }
                    }
                )
            }

            // Bottom Sheet: Model Guide & Instructions
            if (showGuideSheet) {
                ModelGuideBottomSheet(onDismiss = { showGuideSheet = false })
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SpeakerPickerBottomSheet(
        speakers: List<SpeakerInfo>,
        currentSid: Int,
        favorites: Set<String>,
        onSelect: (Int) -> Unit,
        onToggleFavorite: (Int) -> Unit,
        onQuickPreview: (Int) -> Unit,
        onOpenClassificationWorkbench: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var searchQuery by remember { mutableStateOf("") }
        var filterCategory by remember { mutableStateOf("全部") }

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text("选择发音人音色", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onOpenClassificationWorkbench,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("🎙️ 启动音色连播打标与智能分析工作台", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Spacer(Modifier.height(10.dp))

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索发音人编号或关键词 (如: 10, 男声, 少女)") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = null) }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(8.dp))

                // Filter tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tabs = listOf(
                        "全部" to "全部 (${speakers.size})",
                        "女声" to "女声 (${speakers.count { it.gender == "女声" }})",
                        "男声" to "男声 (${speakers.count { it.gender == "男声" }})",
                        "少女" to "少女 (${speakers.count { it.tag == "少女" || it.desc.contains("少女") }})",
                        "御姐" to "御姐 (${speakers.count { it.tag == "御姐" || it.desc.contains("御姐") || it.desc.contains("女中音") }})",
                        "青年" to "青年 (${speakers.count { it.tag == "青年" || it.desc.contains("青年") }})",
                        "大叔" to "大叔 (${speakers.count { it.tag == "大叔" || it.desc.contains("大叔") || it.desc.contains("低音") }})",
                        "萌音" to "萌音 (${speakers.count { it.tag == "萌音" || it.desc.contains("萌音") || it.desc.contains("幼童") }})",
                        "收藏" to "★ 收藏 (${favorites.size})"
                    )

                    tabs.forEach { (cat, label) ->
                        FilterChip(
                            selected = filterCategory == cat,
                            onClick = { filterCategory = cat },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors()
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                val filtered = speakers.filter { spk ->
                    val matchesCategory = when (filterCategory) {
                        "女声" -> spk.gender == "女声"
                        "男声" -> spk.gender == "男声"
                        "少女" -> spk.tag == "少女" || spk.desc.contains("少女")
                        "御姐" -> spk.tag == "御姐" || spk.desc.contains("御姐") || spk.desc.contains("女中音")
                        "青年" -> spk.tag == "青年" || spk.desc.contains("青年")
                        "大叔" -> spk.tag == "大叔" || spk.desc.contains("大叔") || spk.desc.contains("低音")
                        "萌音" -> spk.tag == "萌音" || spk.desc.contains("萌音") || spk.desc.contains("幼童")
                        "收藏" -> favorites.contains(spk.id.toString())
                        else -> true
                    }
                    val matchesQuery = searchQuery.isBlank() ||
                            spk.name.contains(searchQuery, ignoreCase = true) ||
                            spk.id.toString() == searchQuery.trim() ||
                            spk.gender.contains(searchQuery, ignoreCase = true) ||
                            spk.tag.contains(searchQuery, ignoreCase = true) ||
                            spk.desc.contains(searchQuery, ignoreCase = true)

                    matchesCategory && matchesQuery
                }

                Text("共检索到 ${filtered.size} 位发音人", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { spk ->
                        val isSelected = spk.id == currentSid
                        val isFav = favorites.contains(spk.id.toString())
                        val isFemale = spk.gender == "女声"

                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(spk.id) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(if (isFemale) Color(0xFFFF4081).copy(alpha = 0.15f) else Color(0xFF2196F3).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (isFemale) "♀" else "♂",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = if (isFemale) Color(0xFFD81B60) else Color(0xFF1976D2)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(spk.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            color = if (isFemale) Color(0xFFFCE4EC) else Color(0xFFE3F2FD),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                spk.gender,
                                                color = if (isFemale) Color(0xFFC2185B) else Color(0xFF1565C0),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                        if (spk.tag.isNotBlank()) {
                                            Spacer(Modifier.width(4.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    spk.tag,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(spk.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                IconButton(onClick = { onToggleFavorite(spk.id) }) {
                                    Icon(
                                        if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = null,
                                        tint = if (isFav) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                OutlinedButton(
                                    onClick = { onQuickPreview(spk.id) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("试听", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun VoiceClassificationWorkbench(
        speakers: List<SpeakerInfo>,
        currentModelDir: String,
        track: AudioTrack,
        onSpeakerUpdated: (SpeakerInfo) -> Unit,
        onSaveAll: (List<SpeakerInfo>) -> Unit,
        onDismiss: () -> Unit
    ) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        var currentIndex by remember { mutableIntStateOf(0) }
        val currentSpk = speakers.getOrNull(currentIndex) ?: speakers.firstOrNull()

        var isAnalyzingCurrent by remember { mutableStateOf(false) }
        var currentAcousticProfile by remember { mutableStateOf<AcousticProfile?>(null) }
        var isBatchRunning by remember { mutableStateOf(false) }
        var batchProgress by remember { mutableStateOf(0 to speakers.size) }
        var autoAdvance by remember { mutableStateOf(true) }
        var currentPlayJob by remember { mutableStateOf<Job?>(null) }

        DisposableEffect(Unit) {
            onDispose {
                currentPlayJob?.cancel()
                safeStopAndFlushTrack()
            }
        }

        fun playAndAnalyze(spk: SpeakerInfo) {
            val tts = TtsEngine.tts ?: return
            currentPlayJob?.cancel()
            isAnalyzingCurrent = true
            currentAcousticProfile = null

            currentPlayJob = coroutineScope.launch(Dispatchers.Default) {
                val testText = "您好，正在测试当前音色特征。"
                val genConfig = GenerationConfig(sid = spk.id, speed = 1.0f)
                if (TtsEngine.isSupertonic) {
                    genConfig.extra = mapOf("lang" to TtsEngine.supertonicLang)
                }
                val audio = try {
                    tts.generateWithConfig(text = testText, config = genConfig)
                } catch (e: Exception) {
                    Log.e(TAG, "playAndAnalyze audio generation failed", e)
                    withContext(Dispatchers.Main) { isAnalyzingCurrent = false }
                    return@launch
                }

                if (!isActive) return@launch

                if (audio.samples.isNotEmpty()) {
                    safeStopAndFlushTrack()
                    safePlayTrack()

                    val chunkSize = 2048
                    var offset = 0
                    while (isActive && offset < audio.samples.size) {
                        val length = minOf(chunkSize, audio.samples.size - offset)
                        val written = safeWriteTrack(audio.samples, offset, length)
                        if (written <= 0) break
                        offset += length
                    }
                    if (isActive) {
                        val profile = PitchEstimator.estimate(audio.samples, tts.sampleRate())
                        withContext(Dispatchers.Main) {
                            currentAcousticProfile = profile
                            isAnalyzingCurrent = false
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isAnalyzingCurrent = false
                    }
                }
            }
        }

        // Trigger first preview on enter
        LaunchedEffect(Unit) {
            if (currentSpk != null) {
                playAndAnalyze(currentSpk)
            }
        }

        fun applyTagAndNext(gender: String, tag: String, desc: String) {
            if (currentSpk == null) return
            val f0 = currentAcousticProfile?.f0 ?: currentSpk.f0
            val fullDesc = if (f0 > 0) "$desc · ${f0.toInt()}Hz" else desc
            val updated = currentSpk.copy(
                gender = gender,
                tag = tag,
                f0 = f0,
                desc = fullDesc
            )
            onSpeakerUpdated(updated)
            SpeakerManager.updateSpeaker(updated)

            if (autoAdvance && currentIndex < speakers.size - 1) {
                currentIndex++
                playAndAnalyze(speakers[currentIndex])
            }
        }

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("🎙️ 音色连播与智能分类工作台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "当前发音人: ${currentIndex + 1} / ${speakers.size}  (ID: ${currentSpk?.id ?: 0})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Clear, contentDescription = "关闭")
                    }
                }

                // Progress Bar
                LinearProgressIndicator(
                    progress = if (speakers.isNotEmpty()) (currentIndex + 1).toFloat() / speakers.size else 0f,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                if (currentSpk != null) {
                    val isFemale = currentSpk.gender == "女声"

                    // Current Voice Card
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isFemale) Color(0xFFFCE4EC).copy(alpha = 0.5f) else Color(0xFFE3F2FD).copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(if (isFemale) Color(0xFFFF4081).copy(alpha = 0.2f) else Color(0xFF2196F3).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (isFemale) "♀" else "♂",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp,
                                    color = if (isFemale) Color(0xFFD81B60) else Color(0xFF1976D2)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(currentSpk.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = if (isFemale) Color(0xFFF8BBD0) else Color(0xFFBBDEFB),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            currentSpk.gender,
                                            color = if (isFemale) Color(0xFF880E4F) else Color(0xFF0D47A1),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                    if (currentSpk.tag.isNotBlank()) {
                                        Spacer(Modifier.width(4.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                currentSpk.tag,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    currentSpk.desc.ifBlank { "未分类音色" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 🤖 Algorithm Acoustic Profiling Result
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🤖 声学算法测定 (F0 基频分析)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                if (isAnalyzingCurrent) {
                                    Text("测定中...", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            if (currentAcousticProfile != null) {
                                val prof = currentAcousticProfile!!
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            "测定结果: ${prof.gender} · 【${prof.tag}】",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(prof.desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Button(
                                        onClick = {
                                            applyTagAndNext(prof.gender, prof.tag, prof.desc)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("采纳推荐", fontSize = 12.sp)
                                    }
                                }
                            } else {
                                Text(
                                    if (isAnalyzingCurrent) "正在实时合成音频并计算基频自相关..." else "点击下方重播即可触发算法测定",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Quick Tag Buttons
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🏷️ 人耳打标快速分类 (点击即标定并跳至下一位):", fontSize = 12.sp, fontWeight = FontWeight.Bold)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { applyTagAndNext("女声", "少女", "清脆少女音") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF48FB1)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("👧 少女音", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { applyTagAndNext("女声", "御姐", "温和御姐音") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCE93D8)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("👩 御姐音", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { applyTagAndNext("女声", "萌音", "明亮幼童/萌音") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFAB91)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("👶 萌音", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { applyTagAndNext("男声", "青年", "清朗青年男声") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF90CAF9)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("👦 青年男声", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { applyTagAndNext("男声", "大叔", "深沉大叔/男低音") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF80CBC4)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("👨 沉稳大叔", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { applyTagAndNext(currentSpk.gender, "播音", "标准播音正剧") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB0BEC5)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("🎙️ 播音正剧", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Playback & Navigation Controls
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        if (currentIndex > 0) {
                                            currentIndex--
                                            playAndAnalyze(speakers[currentIndex])
                                        }
                                    },
                                    enabled = currentIndex > 0
                                ) {
                                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一个")
                                }

                                Button(
                                    onClick = { playAndAnalyze(currentSpk) },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("重播试听", fontSize = 13.sp)
                                }

                                IconButton(
                                    onClick = {
                                        if (currentIndex < speakers.size - 1) {
                                            currentIndex++
                                            playAndAnalyze(speakers[currentIndex])
                                        }
                                    },
                                    enabled = currentIndex < speakers.size - 1
                                ) {
                                    Icon(Icons.Default.SkipNext, contentDescription = "下一个")
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("连播模式 (打标后自动播放下一位)", fontSize = 12.sp)
                                Switch(
                                    checked = autoAdvance,
                                    onCheckedChange = { autoAdvance = it }
                                )
                            }
                        }
                    }
                }

                // Batch Actions
                if (isBatchRunning) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "⚡ 正在全自动进行声学分析: ${batchProgress.first} / ${batchProgress.second}...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            LinearProgressIndicator(
                                progress = if (batchProgress.second > 0) batchProgress.first.toFloat() / batchProgress.second else 0f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val tts = TtsEngine.tts ?: return@Button
                                isBatchRunning = true
                                coroutineScope.launch(Dispatchers.Default) {
                                    val updated = speakers.toMutableList()
                                    val sampleRate = tts.sampleRate()
                                    val testText = "您好，音色分析。"

                                    for (i in updated.indices) {
                                        val sid = updated[i].id
                                        withContext(Dispatchers.Main) {
                                            batchProgress = (i + 1) to updated.size
                                        }
                                        val genConfig = GenerationConfig(sid = sid, speed = 1.0f)
                                        if (TtsEngine.isSupertonic) {
                                            genConfig.extra = mapOf("lang" to TtsEngine.supertonicLang)
                                        }
                                        val audio = tts.generateWithConfig(text = testText, config = genConfig)
                                        if (audio.samples.isNotEmpty()) {
                                            val profile = PitchEstimator.estimate(audio.samples, sampleRate)
                                            updated[i] = updated[i].copy(
                                                gender = profile.gender,
                                                f0 = profile.f0,
                                                tag = profile.tag,
                                                desc = profile.desc
                                            )
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        isBatchRunning = false
                                        onSaveAll(updated)
                                        Toast.makeText(context, "全自动声学分析完成！共分析 ${updated.size} 位发音人并已保存", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("⚡ 全自动批量分析", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                val ok = SpeakerManager.saveSpeakers(context, currentModelDir, speakers)
                                if (ok) {
                                    Toast.makeText(context, "音色分类配置已成功保存！", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "已在内存中生效", Toast.LENGTH_SHORT).show()
                                }
                                onSaveAll(speakers)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("💾 保存至配置文件", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ModelGuideBottomSheet(onDismiss: () -> Unit) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val context = LocalContext.current
        val recommendPath = context.getExternalFilesDir("models")?.absolutePath ?: "/sdcard/Android/data/com.k2fsa.sherpa.onnx.tts.engine/files/models"
        val extPath = "/sdcard/sherpa-onnx/tts"

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("模型导入与操作指引", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Clear, contentDescription = "关闭")
                    }
                }

                // Card 1: 3-step import
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🚀 免重构 · 3 步直接导入新模型", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        Text("本软件具备架构智能指纹识别，支持将离线模型文件夹直接拷入手机，即拷即用，无需重新编译 APK！", fontSize = 12.sp, lineHeight = 18.sp)

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        Text("📁 推荐存放目录（沙盒免权限·读取极快）：", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    recommendPath,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("ModelPath", recommendPath)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "推荐路径已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("复制", fontSize = 11.sp)
                                }
                            }
                        }

                        Text("或公开路径：$extPath", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Card 2: Supported Architectures
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🧩 支持直接导入的 4 大离线架构", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                        Text("1. Kokoro 家族 (推荐 · 24kHz)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text("• 特征：含 model.onnx, voices.bin, tokens.txt, espeak-ng-data\n• 音质极高，支持风格向量控制与中英多音色。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Text("2. VITS 家族 (多音色 / 角色配音)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text("• 特征：含 *.onnx, lexicon.txt, tokens.txt, rule.far / *.fst\n• 适用：AISHELL-3、凡晨小说播报、二次元音色等。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Text("3. Piper 离线语音库 (极轻量)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text("• 特征：30M~60M 轻量模型，超低延迟推理，适合省电后台听书。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Text("4. Matcha-TTS (流匹配)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text("• 特征：含 acoustic-model.onnx 与 vocoder.onnx 声码器。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Card 3: voices.json explanation
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🏷️ 发音人信息与音色定制 (voices.json)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("• 自动识别：若模型文件夹内未提供 voices.json，软件会自动探测发音人数并生成标准音色卡片，不会崩溃。\n• 定制展示：在模型根目录下放置 voices.json 可自定义姓名与性别：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "[\n  {\"id\": 0, \"name\": \"角色A\", \"gender\": \"女声\", \"desc\": \"清丽女高音\"},\n  {\"id\": 1, \"name\": \"角色B\", \"gender\": \"男声\", \"desc\": \"醇厚男中音\"}\n]",
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Card 4: System TTS Binding
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("⚙️ 绑定为 Android 全局系统朗读引擎", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("将本引擎设为系统首选后，微信读书、各种小说阅读器、SillyTavern(酒馆)与系统随选朗读均可全局调用离线高保真音色。", fontSize = 12.sp, lineHeight = 17.sp)

                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                                }.onFailure {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                    }.onFailure {
                                        Toast.makeText(context, "请在系统设置中搜索「文字转语音」", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("立即前往系统「文字转语音 (TTS)」设置")
                        }
                    }
                }
            }
        }
    }

    private data class RtfResult(
        val threads: Int,
        val elapsedSec: Float,
        val durationSec: Float,
        val rtf: Float
    )

    override fun onDestroy() {
        stopMediaPlayer()
        super.onDestroy()
    }

    private fun stopMediaPlayer() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun onClickStop() {
        stopped = true
        synthesisPlaybackJob?.cancel()
        synthesisGenJob?.cancel()
        while (!samplesChannel.isEmpty) {
            samplesChannel.tryReceive().getOrNull()
        }
        safeStopAndFlushTrack()
        stopMediaPlayer()
    }

    private fun callback(samples: FloatArray): Int {
        if (!stopped) {
            val samplesCopy = samples.copyOf()
            scope.launch {
                samplesChannel.trySend(samplesCopy)
            }
            return 1
        } else {
            safeStopAndFlushTrack()
            return 0
        }
    }

    private fun initAudioTrack() {
        synchronized(trackLock) {
            val tts = TtsEngine.tts ?: return
            val sampleRate = tts.sampleRate()
            runCatching {
                if (::track.isInitialized) {
                    track.pause()
                    track.flush()
                    track.release()
                }
            }

            val bufLength = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )

            val attr = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setSampleRate(sampleRate)
                .build()

            track = AudioTrack(
                attr, format, bufLength, AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            track.play()
        }
    }
}
