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
        // 多个下载源，按优先级尝试
        private val MODEL_DOWNLOAD_URLS = listOf(
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
            "https://github.com/alphacep/vosk-api/releases/download/v0.3.45/vosk-model-small-cn-0.22.zip"
        )
        // 模型必需的核心文件/目录（小模型结构）
        private val REQUIRED_ITEMS = listOf(
            "am",
            "conf",
            "graph",
            "ivector",
            "words.txt",
            "final.mdl"
        )
        // 核心文件（非目录）
        private val REQUIRED_FILES = listOf(
            "am/final.mdl",
            "conf/mfcc.conf",
            "graph/HCLr.fst",
            "graph/Gr.fst",
            "ivector/final.mat",
            "words.txt"
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
            // 宽松校验：只检查核心目录是否存在
            var dirCount = 0
            for (item in REQUIRED_ITEMS) {
                val file = File(modelDir, item)
                if (file.exists()) {
                    dirCount++
                }
            }
            // 至少要有一半以上的必需项存在
            val valid = dirCount >= REQUIRED_ITEMS.size / 2
            if (!valid) {
                Log.w(TAG, "模型完整性校验失败: $dirCount/${REQUIRED_ITEMS.size} 项存在")
                // 打印目录结构用于调试
                logModelDirStructure()
            }
            valid
        } catch (e: Exception) {
            Log.e(TAG, "verifyModelIntegrity error", e)
            false
        }
    }

    private fun logModelDirStructure() {
        try {
            Log.d(TAG, "模型目录结构:")
            modelDir.listFiles()?.forEach { file ->
                Log.d(TAG, "  ${file.name} (${if (file.isDirectory) "dir" else "file: ${file.length()} bytes"})")
                if (file.isDirectory) {
                    file.listFiles()?.forEach { subFile ->
                        Log.d(TAG, "    ${subFile.name} (${if (subFile.isDirectory) "dir" else "${subFile.length()} bytes"})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "logModelDirStructure error", e)
        }
    }

    suspend fun loadModel(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 先检查模型是否存在
                if (!modelDir.exists() || !modelDir.isDirectory) {
                    Log.w(TAG, "模型目录不存在")
                    return@withContext false
                }

                // 检查完整性（宽松校验，仅作参考）
                val integrityOk = verifyModelIntegrity()
                if (!integrityOk) {
                    Log.w(TAG, "模型完整性校验未通过，但仍尝试加载")
                }

                // 尝试加载模型，最多重试2次
                var lastError: Exception? = null
                for (attempt in 1..2) {
                    try {
                        Log.d(TAG, "Loading model attempt $attempt...")
                        model = Model(modelDir.absolutePath)
                        _state.value = _state.value.copy(isModelReady = true, error = null, modelExists = true)
                        Log.d(TAG, "Model loaded successfully")
                        return@withContext true
                    } catch (e: Exception) {
                        lastError = e
                        Log.e(TAG, "Model load attempt $attempt failed", e)
                        if (attempt == 1) {
                            model = null
                            System.gc()
                            delay(500)
                        }
                    }
                }

                // 加载失败，清理损坏模型
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

                var lastError: Exception? = null
                // 尝试多个下载源
                for ((index, downloadUrl) in MODEL_DOWNLOAD_URLS.withIndex()) {
                    try {
                        Log.d(TAG, "尝试下载源 ${index + 1}/${MODEL_DOWNLOAD_URLS.size}: $downloadUrl")
                        val success = downloadAndExtractModel(downloadUrl)
                        if (success) {
                            return@withContext true
                        }
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(TAG, "下载源 ${index + 1} 失败: ${e.message}")
                        // 继续尝试下一个源
                    }
                }

                // 所有源都失败了
                throw lastError ?: Exception("所有下载源均失败")
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

    private suspend fun downloadAndExtractModel(downloadUrl: String): Boolean {
        // 下载模型zip
        Log.d(TAG, "Downloading model from $downloadUrl")
        val url = URL(downloadUrl)
        val connection = url.openConnection()
        connection.connectTimeout = 30000
        connection.readTimeout = 120000
        connection.connect()
        val fileLength = connection.contentLength
        Log.d(TAG, "Model size: $fileLength bytes")

        if (fileLength <= 0) {
            // 有些服务器不返回content-length，放宽校验
            Log.w(TAG, "无法获取文件大小，继续下载")
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
            if (fileLength > 0) {
                val progress = ((total * 100) / fileLength).toInt()
                if (progress != lastProgress) {
                    lastProgress = progress
                    _state.value = _state.value.copy(downloadProgress = progress)
                }
            } else {
                // 未知大小时显示已下载的MB数
                val mb = total / (1024 * 1024)
                _state.value = _state.value.copy(downloadProgress = (mb.toInt() * 2).coerceAtMost(99))
            }
        }

        output.flush()
        output.close()
        input.close()

        Log.d(TAG, "Download complete, total: $total bytes")

        // 基本验证：至少要大于10MB才合理
        if (total < 10 * 1024 * 1024) {
            throw Exception("下载文件太小(${total / 1024 / 1024}MB)，可能下载失败")
        }

        // 解压
        Log.d(TAG, "Extracting model...")
        unzip(modelZipFile, context.filesDir)

        // 删除zip文件
        modelZipFile.delete()

        // 检查是否有嵌套目录，如果有则扁平化
        fixNestedModelDir()

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
        return true
    }

    private fun fixNestedModelDir() {
        // 检查 modelDir 是否存在
        if (!modelDir.exists() || !modelDir.isDirectory) {
            // 查找是否有嵌套的同名目录
            val parentDir = context.filesDir
            val files = parentDir.listFiles() ?: return
            for (file in files) {
                if (file.isDirectory && file.name != MODEL_DIR_NAME) {
                    // 检查这个目录里是否有模型文件
                    val nestedModel = File(file, MODEL_DIR_NAME)
                    if (nestedModel.exists() && nestedModel.isDirectory) {
                        Log.d(TAG, "发现嵌套目录，正在扁平化: ${file.name}/$MODEL_DIR_NAME")
                        // 移动到正确位置
                        nestedModel.renameTo(modelDir)
                        // 删除空的外层目录
                        file.delete()
                        return
                    }
                    // 也可能直接就是模型目录但名字不同
                    val amDir = File(file, "am")
                    val graphDir = File(file, "graph")
                    if (amDir.exists() && graphDir.exists()) {
                        Log.d(TAG, "发现模型目录但名称不对: ${file.name}，重命名为 $MODEL_DIR_NAME")
                        file.renameTo(modelDir)
                        return
                    }
                }
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
