package com.castingcounter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.castingcounter.app.databinding.ActivityMainBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.util.Locale
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: CounterViewModel
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[CounterViewModel::class.java]

        setupChart()
        setupObservers()
        setupClickListeners()
        loadSavedValues()
    }

    private fun setupChart() {
        binding.lineChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return String.format("%.1fh", value)
                    }
                }
                axisMinimum = 0f
                labelCount = 6
            }

            axisLeft.apply {
                setDrawGridLines(true)
                axisMinimum = 0f
            }

            axisRight.isEnabled = false

            legend.isEnabled = false
        }
    }

    private fun setupObservers() {
        viewModel.completedCount.observe(this) { count ->
            binding.tvCompletedCount.text = count.toString()
            updateProgress()
        }

        viewModel.taskCount.observe(this) {
            updateProgress()
            updateBoxResult()
        }

        viewModel.piecesPerBox.observe(this) {
            updateBoxResult()
        }

        viewModel.totalBoxes.observe(this) {
            updateBoxResult()
        }

        viewModel.efficiency.observe(this) { eff ->
            binding.tvEfficiency.text = getString(R.string.efficiency_value, eff)
        }

        viewModel.estimatedCompletion.observe(this) {
            binding.tvEstimatedCompletion.text = it
        }

        viewModel.estimatedDuration.observe(this) {
            binding.tvEstimatedDuration.text = it
        }

        viewModel.efficiencyPoints.observe(this) { points ->
            updateChart(points)
        }

        viewModel.startHour.observe(this) { binding.etStartHour.setText(it.toString()) }
        viewModel.startMinute.observe(this) { binding.etStartMinute.setText(String.format("%02d", it)) }
        viewModel.endHour.observe(this) { binding.etEndHour.setText(it.toString()) }
        viewModel.endMinute.observe(this) { binding.etEndMinute.setText(String.format("%02d", it)) }
        viewModel.isNextDay.observe(this) { binding.cbNextDay.isChecked = it }
    }

    private fun loadSavedValues() {
        viewModel.taskCount.value?.let {
            if (it > 0) binding.etTaskCount.setText(it.toString())
        }
        viewModel.piecesPerBox.value?.let {
            if (it > 0) binding.etPiecesPerBox.setText(it.toString())
        }
        updateBoxResult()
        updateProgress()
    }

    private fun updateProgress() {
        val completed = viewModel.completedCount.value ?: 0
        val task = viewModel.taskCount.value ?: 0

        if (task > 0) {
            val progress = (completed.toFloat() / task.toFloat() * 100).toInt()
            binding.progressBar.progress = progress.coerceIn(0, 100)
            binding.tvProgress.text = getString(R.string.progress_value, completed, task, progress.toFloat())
        } else {
            binding.progressBar.progress = 0
            binding.tvProgress.text = getString(R.string.progress_value, completed, 0, 0f)
        }
    }

    private fun updateBoxResult() {
        val perBox = viewModel.piecesPerBox.value ?: 0
        val totalBoxes = viewModel.totalBoxes.value ?: 0

        if (perBox > 0 && totalBoxes > 0) {
            binding.tvBoxResult.text = getString(R.string.box_calculation_result, perBox, totalBoxes)
            binding.tvBoxResult.visibility = android.view.View.VISIBLE
        } else {
            binding.tvBoxResult.visibility = android.view.View.GONE
        }
    }

    private fun updateChart(points: List<EfficiencyPoint>) {
        if (points.isEmpty()) {
            binding.lineChart.clear()
            return
        }

        val entries = points.map { Entry(it.hour, it.piecesPerHour) }
        val dataSet = LineDataSet(entries, "效率").apply {
            color = ContextCompat.getColor(this@MainActivity, R.color.chart_line)
            setDrawFilled(true)
            fillColor = ContextCompat.getColor(this@MainActivity, R.color.chart_line)
            fillAlpha = 40
            lineWidth = 2f
            setCircleColor(ContextCompat.getColor(this@MainActivity, R.color.chart_line))
            circleRadius = 3f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        binding.lineChart.data = LineData(dataSet)
        binding.lineChart.invalidate()
    }

    private fun setupClickListeners() {
        // 计算装箱
        binding.btnCalculateBoxes.setOnClickListener {
            val taskStr = binding.etTaskCount.text.toString()
            val perBoxStr = binding.etPiecesPerBox.text.toString()

            if (taskStr.isEmpty()) {
                Toast.makeText(this, R.string.please_enter_task, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (perBoxStr.isEmpty()) {
                Toast.makeText(this, R.string.please_enter_pieces_per_box, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val taskCount = taskStr.toIntOrNull() ?: 0
            val piecesPerBox = perBoxStr.toIntOrNull() ?: 0

            viewModel.setTaskCount(taskCount)
            viewModel.setPiecesPerBox(piecesPerBox)

            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        }

        // 保存时间设置
        binding.btnSaveTime.setOnClickListener {
            val startH = binding.etStartHour.text.toString().toIntOrNull() ?: 8
            val startM = binding.etStartMinute.text.toString().toIntOrNull() ?: 0
            val endH = binding.etEndHour.text.toString().toIntOrNull() ?: 17
            val endM = binding.etEndMinute.text.toString().toIntOrNull() ?: 0
            val nextDay = binding.cbNextDay.isChecked

            // 验证时间
            if (startH !in 0..23 || endH !in 0..23 || startM !in 0..59 || endM !in 0..59) {
                Toast.makeText(this, "请输入有效的时间", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.setWorkTime(startH, startM, endH, endM, nextDay)
            Toast.makeText(this, "时间设置已保存", Toast.LENGTH_SHORT).show()
        }

        // +5 按钮
        binding.btnAdd5.setOnClickListener {
            viewModel.addCompletedPieces(5)
        }

        // +10 按钮
        binding.btnAdd10.setOnClickListener {
            viewModel.addCompletedPieces(10)
        }

        // 自定义添加
        binding.btnCustomAdd.setOnClickListener {
            val input = binding.etCustomAdd.text.toString()
            val pieces = input.toIntOrNull() ?: 0
            if (pieces > 0) {
                viewModel.addCompletedPieces(pieces)
                binding.etCustomAdd.text.clear()
            } else {
                Toast.makeText(this, "请输入有效的数量", Toast.LENGTH_SHORT).show()
            }
        }

        // 语音输入
        binding.btnVoiceInput.setOnClickListener {
            checkPermissionAndStartVoice()
        }

        // 重置
        binding.btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("重置确认")
                .setMessage(R.string.confirm_reset)
                .setPositiveButton("确定") { _, _ ->
                    viewModel.resetData()
                    Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun checkPermissionAndStartVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
            return
        }
        startVoiceRecognition()
    }

    private fun startVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.voice_not_supported, Toast.LENGTH_SHORT).show()
            return
        }

        if (isListening) {
            speechRecognizer?.stopListening()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                binding.btnVoiceInput.text = getString(R.string.listening)
                binding.tvVoiceHint.text = getString(R.string.listening)
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                binding.btnVoiceInput.text = getString(R.string.voice_input)
                binding.tvVoiceHint.text = getString(R.string.voice_hint)
            }

            override fun onError(error: Int) {
                isListening = false
                binding.btnVoiceInput.text = getString(R.string.voice_input)
                binding.tvVoiceHint.text = getString(R.string.voice_hint)
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                    else -> "未知错误"
                }
                Toast.makeText(this@MainActivity, "语音识别错误: $errorMsg", Toast.LENGTH_SHORT).show()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    for (text in matches) {
                        val pieces = parseVoiceInput(text)
                        if (pieces > 0) {
                            viewModel.addCompletedPieces(pieces)
                            Toast.makeText(this@MainActivity, "已添加 $pieces 件", Toast.LENGTH_SHORT).show()
                            return
                        }
                    }
                    Toast.makeText(this@MainActivity, "未识别到数量，请说\"加XX件\"", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    binding.tvVoiceHint.text = matches[0]
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        speechRecognizer?.startListening(intent)
    }

    private fun parseVoiceInput(text: String): Int {
        // 匹配 "加xxx件" 或 "加xxx" 的模式
        val patterns = listOf(
            "加(\\d+)件",
            "加(\\d+)",
            "增加(\\d+)件",
            "增加(\\d+)",
            "添加(\\d+)件",
            "添加(\\d+)",
            "plus(\\d+)",
            "add(\\d+)",
            "(\\d+)件"
        )

        for (patternStr in patterns) {
            val pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.toIntOrNull() ?: 0
            }
        }

        // 尝试直接提取数字（如果文本中只有数字）
        val numberPattern = Pattern.compile("\\d+")
        val matcher = numberPattern.matcher(text)
        if (matcher.find() && text.length <= 10) {
            return matcher.group().toIntOrNull() ?: 0
        }

        return 0
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceRecognition()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
