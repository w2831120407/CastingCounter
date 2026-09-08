package com.castingcounter.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vosk.Model
import org.vosk.Recognizer
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
    val downloadProgress: Int = 0,
    val modelExists: Boolean = false
)

class VoiceRecognizerManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecognizerManager"
        private const val MODEL_DIR_NAME = "vosk-model-small-cn-0.22"
        private const val MODEL_ZIP_NAME = "vosk-model-small-cn-0.22.zip"
        private const val MODEL_DOWNLOAD_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
        // 模型必需文件列表，用于校验模型完整性
        private val REQUIRED_FILES = listOf(
            "am/final.mdl",
            "am/global_cmvn.stats",
            "conf/model.conf",
            "conf/mfcc.conf",
            "ivector/final.dubm",
            "ivector/final.ie",
            "ivector/final.mat",
            "ivector/global_cmvn.stats",
            "ivector/splice.conf",
            "ivector/splice_opts",
            "graph/HCLr.fst",
            "graph/Gr.fst",
            "graph/phones",
            "graph/words.txt"
        )
    }

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recognitionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize by lazy {
        try {
            AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)
        } catch (e: Exception) {
            4096
        }
    }

    private val modelDir: File
        get() = File(context.filesDir, MODEL_DIR_NAME)

    private val modelZipFile: File
        get() = File(context.cacheDir, MODEL_ZIP_NAME)

    fun isModelReady(): Boolean = model != null

    fun checkModelExists(): Boolean {
        return try {
            if (!modelDir.exists() || !modelDir.isDirectory) {
                _state.value = _state.value.copy(modelExists = false)
                return false
            }
            val valid = verifyModelIntegrity()
            _state.value = _state.value.copy(modelExists = valid)
            valid
        } catch (e: Exception) {
            Log.e(TAG, "checkModelExists error", e)
            _state.value = _state.value.copy(modelExists = false)
            false
        }
    }

    private fun verifyModelIntegrity(): Boolean {
        return try {
            for (relativePath in REQUIRED_FILES) {
                val file = File(modelDir, relativePath)
                if (!file.exists() || file.length() == 0L) {
                    Log.w(TAG, "Model file missing or empty: $relativePath")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "verifyModelIntegrity error", e)
            false
        }
    }

    suspend fun loadModel(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 先检查模型是否存在且完整
                if (!checkModelExists()) {
                    Log.w(TAG, "Model not found or corrupted")
                    clearCorruptedModel()
                    return@withContext false
                }

                // 尝试加载模型，最多重试2次
                var lastError: Exception? = null
                for (attempt in 1..2) {
                    try {
                        Log.d(TAG, "Loading model attempt $attempt...")
                        model = Model(modelDir.absolutePath)
                        _state.value = _state.value.copy(isModelReady = true, error = null)
                        Log.d(TAG, "Model loaded successfully")
                        return@withContext true
                    } catch (e: Exception) {
                        lastError = e
                        Log.e(TAG, "Model load attempt $attempt failed", e)
                        // 第一次失败后清理并重试
                        if (attempt == 1) {
                            model = null
                            System.gc()
                            delay(500)
                        }
                    }
                }

                // 加载失败，可能模型损坏
                clearCorruptedModel()
                _state.value = _state.value.copy(
                    error = "模型加载失败: ${lastError?.message}",
                    isModelReady = false
                )
                false
            } catch (e: Exception) {
                Log.e(TAG, "loadModel error", e)
                clearCorruptedModel()
                _state.value = _state.value.copy(
                    error = "模型加载异常: ${e.message}",
                    isModelReady = false
                )
                false
            }
        }
    }

    private fun clearCorruptedModel() {
        try {
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
                Log.d(TAG, "Corrupted model deleted")
            }
            if (modelZipFile.exists()) {
                modelZipFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "clearCorruptedModel error", e)
        }
    }

    suspend fun downloadModel(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                _state.value = _state.value.copy(
                    isModelDownloading = true,
                    downloadProgress = 0,
                    error = null
                )

                // 先清理旧的损坏文件
                clearCorruptedModel()

                // 下载模型zip
                Log.d(TAG, "Downloading model from $MODEL_DOWNLOAD_URL")
                val url = URL(MODEL_DOWNLOAD_URL)
                val connection = url.openConnection()
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.connect()
                val fileLength = connection.contentLength
                Log.d(TAG, "Model size: $fileLength bytes")

                if (fileLength <= 0) {
                    throw Exception("无法获取模型文件大小")
                }

                val input = connection.getInputStream()
                val output = FileOutputStream(modelZipFile)

                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int
                var lastProgress = -1

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

                Log.d(TAG, "Download complete, total: $total bytes")

                // 验证下载文件大小
                if (total < fileLength) {
                    throw Exception("下载不完整: $total / $fileLength 字节")
                }

                // 解压
                Log.d(TAG, "Extracting model...")
                unzip(modelZipFile, context.filesDir)

                // 删除zip文件
                modelZipFile.delete()

                // 验证解压后的模型完整性
                if (!verifyModelIntegrity()) {
                    throw Exception("模型文件不完整")
                }

                // 加载模型
                Log.d(TAG, "Loading model...")
                model = Model(modelDir.absolutePath)
                _state.value = _state.value.copy(
                    isModelReady = true,
                    isModelDownloading = false,
                    modelExists = true,
                    error = null
                )
                Log.d(TAG, "Model ready!")
                true
            } catch (e: Exception) {
                Log.e(TAG, "downloadModel error", e)
                clearCorruptedModel()
                _state.value = _state.value.copy(
                    isModelDownloading = false,
                    error = "下载失败: ${e.message ?: "未知错误"}"
                )
                false
            }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var fileCount = 0
            while (entry != null) {
                val file = File(targetDir, entry.name)

                // 安全检查：防止路径遍历攻击
                val canonicalPath = file.canonicalPath
                val canonicalTarget = targetDir.canonicalPath
                if (!canonicalPath.startsWith(canonicalTarget)) {
                    throw SecurityException("非法的压缩包路径: ${entry.name}")
                }

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        zis.copyTo(fos)
                    }
                    fileCount++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            Log.d(TAG, "Unzipped $fileCount files")
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

            val state = audioRecord?.state
            if (state != AudioRecord.STATE_INITIALIZED) {
                throw Exception("录音设备初始化失败")
            }

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

                try {
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
                            try {
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
                            } catch (e: Exception) {
                                Log.e(TAG, "Recognition error", e)
                            }
                        } else if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                            Log.e(TAG, "AudioRecord ERROR_INVALID_OPERATION")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recognition loop error", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startListening error", e)
            cleanupAudio()
            _state.value = _state.value.copy(
                isListening = false,
                error = "录音启动失败: ${e.message}"
            )
        }
    }

    fun stopListening(): String {
        if (!_state.value.isListening) return _state.value.finalText

        var resultText = _state.value.finalText
        try {
            recognitionJob?.cancel()
            recognitionJob = null

            // 获取最终结果
            try {
                val finalResult = recognizer?.finalResult
                val finalText = parseResultText(finalResult)
                if (finalText.isNotEmpty()) {
                    resultText = finalText
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get final result error", e)
            }

            _state.value = _state.value.copy(
                isListening = false,
                finalText = resultText,
                volume = 0
            )

            cleanupAudio()
        } catch (e: Exception) {
            Log.e(TAG, "stopListening error", e)
            cleanupAudio()
        }

        return resultText
    }

    private fun cleanupAudio() {
        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord cleanup error", e)
        }
        audioRecord = null

        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Recognizer cleanup error", e)
        }
        recognizer = null
    }

    private fun parseResultText(json: String?): String {
        if (json.isNullOrEmpty()) return ""
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
        return try {
            var sum = 0.0
            for (i in 0 until read step 4) {
                sum += (buffer[i] * buffer[i]).toDouble()
            }
            val samples = read / 4
            val rms = sqrt(sum / samples)
            if (rms > 0) {
                val db = 20 * log10(rms / 32768.0)
                ((db + 60) / 60 * 100).toInt().coerceIn(0, 100)
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    fun destroy() {
        try {
            recognitionJob?.cancel()
            recognitionJob = null
            cleanupAudio()
            model = null
            scope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "destroy error", e)
        }
    }
}
