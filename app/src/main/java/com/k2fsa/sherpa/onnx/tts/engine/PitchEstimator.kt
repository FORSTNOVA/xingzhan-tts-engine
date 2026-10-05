package com.k2fsa.sherpa.onnx.tts.engine

import kotlin.math.sqrt

data class AcousticProfile(
    val f0: Float,
    val gender: String,   // "女声" | "男声"
    val tag: String,      // "少女" | "御姐" | "青年" | "大叔" | "萌音" | "播音" | "其他"
    val desc: String
)

object PitchEstimator {
    /**
     * Estimates fundamental frequency (F0) in Hz from raw PCM FloatArray samples.
     * Uses normalized autocorrelation over voiced frames.
     */
    fun estimate(samples: FloatArray, sampleRate: Int): AcousticProfile {
        if (samples.isEmpty() || sampleRate <= 0) {
            return classifyByF0(0f)
        }

        val frameSize = 1024
        val hopSize = 512
        val minFreq = 65f   // Deep male bass
        val maxFreq = 420f  // High child / soprano pitch

        val minLag = (sampleRate / maxFreq).toInt().coerceAtLeast(1)
        val maxLag = (sampleRate / minFreq).toInt().coerceAtMost(frameSize - 1)

        val pitchEstimates = mutableListOf<Float>()

        var offset = 0
        while (offset + frameSize <= samples.size) {
            // Check frame energy (RMS) to filter out silence/unvoiced parts
            var sumSquare = 0.0
            for (i in 0 until frameSize) {
                val s = samples[offset + i]
                sumSquare += s * s
            }
            val rms = sqrt(sumSquare / frameSize).toFloat()

            if (rms > 0.015f) { // Voiced speech frame
                var bestLag = -1
                var maxCorr = -1.0

                // Normalized autocorrelation
                for (lag in minLag..maxLag) {
                    var corr = 0.0
                    var norm1 = 0.0
                    var norm2 = 0.0
                    for (i in 0 until (frameSize - lag)) {
                        val a = samples[offset + i].toDouble()
                        val b = samples[offset + i + lag].toDouble()
                        corr += a * b
                        norm1 += a * a
                        norm2 += b * b
                    }
                    val denom = sqrt(norm1 * norm2)
                    val normCorr = if (denom > 1e-7) corr / denom else 0.0
                    if (normCorr > maxCorr) {
                        maxCorr = normCorr
                        bestLag = lag
                    }
                }

                if (bestLag > 0 && maxCorr > 0.35) {
                    val f0 = sampleRate.toFloat() / bestLag
                    if (f0 in minFreq..maxFreq) {
                        pitchEstimates.add(f0)
                    }
                }
            }
            offset += hopSize
        }

        val medianF0 = if (pitchEstimates.isNotEmpty()) {
            pitchEstimates.sorted()[pitchEstimates.size / 2]
        } else {
            0f
        }

        return classifyByF0(medianF0)
    }

    fun classifyByF0(f0: Float): AcousticProfile {
        val f0Int = f0.toInt()
        return when {
            f0 <= 0f -> AcousticProfile(0f, "女声", "播音", "标准音色")
            f0 > 250f -> AcousticProfile(f0, "女声", "萌音", "明亮幼童/萌音 · ${f0Int}Hz")
            f0 in 205f..250f -> AcousticProfile(f0, "女声", "少女", "清脆少女音 · ${f0Int}Hz")
            f0 in 160f..205f -> AcousticProfile(f0, "女声", "御姐", "温和女中音/御姐 · ${f0Int}Hz")
            f0 in 135f..160f -> AcousticProfile(f0, "男声", "青年", "清朗青年男声 · ${f0Int}Hz")
            f0 in 115f..135f -> AcousticProfile(f0, "男声", "青年", "磁性男中音 · ${f0Int}Hz")
            else -> AcousticProfile(f0, "男声", "大叔", "深沉男低音/大叔 · ${f0Int}Hz")
        }
    }
}
