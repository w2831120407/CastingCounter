package com.castingcounter.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.log10
import kotlin.math.sqrt

data class VoiceState(
    val isListening: Boolean = false,
    val partialText: String = "",
    val finalText: String = "",
    val volume: Int = 0,
    val error: String? = null,
    val isModelReady: Boolean = false,
    val isModelDownloading: Boolean = false,
    val downloadProgress: Int = 0
)

class VoiceRecognizerManager(private val context: Context) {

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var audioRecord: AudioRecord? = null
    private var recognitionJob: Job? = null
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private val modelDir: File
        get() = File(context.filesDir, "vosk-model-small-cn-0.22")

    private val modelZipFile: File
        get() = File(context.cacheDir, "vosk-model-small-cn-0.22.zip")

    // 模型下载地址（使用Vosk官方的中文小模型）
    private val modelDownloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"

    fun isModelReady(): Boolean = model != null

    suspend fun loadModel(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
                    model = Model(modelDir.absolutePath)
                    _state.value = _state.value.copy(isModelReady = true)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _state.value = _state.value.copy(error = "模型加载失败: ${e.message}")
                false
            }
        }
    }

    suspend fun downloadModel(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                _state.value = _state.value.copy(isModelDownloading = true, downloadProgress = 0, error = null)

                // 下载模型zip
                val url = URL(modelDownloadUrl)
                val connection = url.openConnection()
                connection.connect()
                val fileLength = connection.contentLength

                val input = connection.getInputStream()
                val output = FileOutputStream(modelZipFile)

                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                var lastProgress = 0

                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    output.write(data, 0, count)
                    val progress = ((total * 100) / fileLength).toInt()
                    if (progress != lastProgress) {
                        lastProgress = progress
                        _state.value = _state.value.copy(downloadProgress = progress)
                    }
                }

                output.flush()
                output.close()
                input.close()

                // 解压
                unzip(modelZipFile, context.filesDir)

                // 删除zip文件
                modelZipFile.delete()

                // 加载模型
                model = Model(modelDir.absolutePath)
                _state.value = _state.value.copy(isModelReady = true, isModelDownloading = false)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                _state.value = _state.value.copy(
                    isModelDownloading = false,
                    error = "模型下载失败: ${e.message}"
                )
                false
            }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun startListening() {
        if (_state.value.isListening) return

        val currentModel = model
        if (currentModel == null) {
            _state.value = _state.value.copy(error = "语音模型未加载")
            return
        }

        try {
            recognizer = Recognizer(currentModel, sampleRate.toFloat())
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 4
            )

            audioRecord?.startRecording()
            _state.value = _state.value.copy(
                isListening = true,
                partialText = "",
                finalText = "",
                error = null,
                volume = 0
            )

            recognitionJob = scope.launch {
                val buffer = ShortArray(bufferSize)
                val byteBuffer = ByteArray(bufferSize * 2)

                while (isActive && _state.value.isListening) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        // 计算音量
                        val volume = calculateVolume(buffer, read)
                        _state.value = _state.value.copy(volume = volume)

                        // 转换为字节
                        for (i in 0 until read) {
                            byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
                        }

                        // 识别
                        if (recognizer?.acceptWaveForm(byteBuffer, read * 2) == true) {
                            val result = recognizer?.result
                            val text = parseResultText(result)
                            if (text.isNotEmpty()) {
                                _state.value = _state.value.copy(
                                    finalText = text,
                                    partialText = ""
                                )
                            }
                        } else {
                            val partial = recognizer?.partialResult
                            val partialText = parsePartialText(partial)
                            _state.value = _state.value.copy(partialText = partialText)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _state.value = _state.value.copy(
                isListening = false,
                error = "启动录音失败: ${e.message}"
            )
        }
    }

    fun stopListening(): String {
        if (!_state.value.isListening) return ""

        try {
            recognitionJob?.cancel()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            // 获取最终结果
            val finalResult = recognizer?.finalResult
            val finalText = parseResultText(finalResult)

            _state.value = _state.value.copy(
                isListening = false,
                finalText = finalText,
                volume = 0
            )

            recognizer?.close()
            recognizer = null

            return finalText
        } catch (e: Exception) {
            e.printStackTrace()
            return _state.value.finalText
        }
    }

    private fun parseResultText(json: String?): String {
        if (json.isNullOrEmpty()) return ""
        // 简单的JSON解析，提取text字段
        return try {
            val regex = """"text"\s*:\s*"([^"]+)"""".toRegex()
            regex.find(json)?.groupValues?.get(1)?.replace(" ", "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun parsePartialText(json: String?): String {
        if (json.isNullOrEmpty()) return ""
        return try {
            val regex = """"partial"\s*:\s*"([^"]+)"""".toRegex()
            regex.find(json)?.groupValues?.get(1)?.replace(" ", "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun calculateVolume(buffer: ShortArray, read: Int): Int {
        if (read <= 0) return 0
        var sum = 0.0
        for (i in 0 until read) {
            sum += (buffer[i] * buffer[i]).toDouble()
        }
        val rms = sqrt(sum / read)
        return if (rms > 0) {
            val db = 20 * log10(rms / 32768.0)
            // 将dB转换为0-100的范围
            ((db + 60) / 60 * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
    }

    fun destroy() {
        recognitionJob?.cancel()
        audioRecord?.release()
        audioRecord = null
        recognizer?.close()
        recognizer = null
        speechService?.shutdown()
        speechService = null
        scope.cancel()
    }
}
