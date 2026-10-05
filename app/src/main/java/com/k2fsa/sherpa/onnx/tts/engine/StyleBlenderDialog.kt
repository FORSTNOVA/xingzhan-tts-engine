package com.k2fsa.sherpa.onnx.tts.engine

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 风格向量融炼器对话框 (Style Vector Blender)
 * 在 510×256 的风格潜空间内执行双音色插值计算，将全新融合后的特征追加进 voices.bin 与 voices.json。
 */
@Composable
fun StyleBlenderDialog(
    speakers: List<SpeakerInfo>,
    currentSid: Int,
    currentModelDir: String,
    onBlended: (SpeakerInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var sidA by remember { mutableIntStateOf(currentSid) }
    var sidB by remember {
        val other = speakers.firstOrNull { it.id != currentSid }?.id ?: 7
        mutableIntStateOf(other)
    }
    var blendRatio by remember { mutableFloatStateOf(0.35f) }
    val spkA = speakers.find { it.id == sidA } ?: speakers.first()
    val spkB = speakers.find { it.id == sidB } ?: speakers.first()

    var customName by remember {
        val cleanA = spkA.name.replace(" ", "").take(6)
        val cleanB = spkB.name.replace(" ", "").take(6)
        mutableStateOf("${cleanA}_融合_${cleanB}")
    }
    var isBlending by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🧪 风格向量融炼器", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "Kokoro 专属",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "在 510×256 风格潜空间执行线性插值，融炼兼具两者特征的全新音色：",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Speaker A Selection Card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("主基准音色 A (${((1f - blendRatio) * 100).toInt()}%)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("#${spkA.id} ${spkA.name} · ${spkA.gender}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                }

                // Speaker B Selection Card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("注入风格音色 B (${(blendRatio * 100).toInt()}%)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("#${spkB.id} ${spkB.name} · ${spkB.gender}", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium)
                    }
                }

                // Ratio Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("风格音色 B 混合比重", fontSize = 11.sp)
                        Text("${(blendRatio * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = blendRatio,
                        onValueChange = { blendRatio = it },
                        valueRange = 0.05f..0.95f
                    )
                }

                // Name field
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("新音色名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isBlending = true
                    val appDir = File(context.getExternalFilesDir("models"), currentModelDir)
                    val primaryDir = File("/sdcard/sherpa-onnx/tts/$currentModelDir")
                    val targetDir = if (File(appDir, "voices.bin").exists()) {
                        appDir
                    } else if (File(primaryDir, "voices.bin").canWrite()) {
                        primaryDir
                    } else {
                        appDir
                    }
                    val newSpk = KokoroStyleEngine.blendVoices(
                        modelDirFile = targetDir,
                        sidA = sidA,
                        sidB = sidB,
                        weightB = blendRatio,
                        blendedName = customName.ifBlank { "blended_${System.currentTimeMillis() % 1000}" },
                        gender = spkA.gender
                    )
                    isBlending = false
                    if (newSpk != null) {
                        Toast.makeText(context, "✨ 成功融炼新音色: ${newSpk.name} (#${newSpk.id})", Toast.LENGTH_LONG).show()
                        onBlended(newSpk)
                        onDismiss()
                    } else {
                        Toast.makeText(context, "融炼失败，请检查存储读写权限", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isBlending
            ) {
                Text(if (isBlending) "融炼中..." else "融炼至定制槽位 (#102)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
