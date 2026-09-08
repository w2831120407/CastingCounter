package com.castingcounter.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.castingcounter.app.databinding.ActivityMainBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: CounterViewModel
    private lateinit var voiceRecognizer: VoiceRecognizerManager
    private var isCancelled = false
    private var startY = 0f

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 100
        private const val CANCEL_THRESHOLD = 100f // 上滑取消阈值
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[CounterViewModel::class.java]
        voiceRecognizer = VoiceRecognizerManager(this)

        setupChart()
        setupObservers()
        setupClickListeners()
        setupVoiceListeners()
        loadSavedValues()
        checkVoiceModel()
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

    private fun setupVoiceListeners() {
        lifecycleScope.launch {
            voiceRecognizer.state.collectLatest { state ->
                // 更新UI
                binding.tvVoiceText.text = state.partialText.ifEmpty { state.finalText }
                updateVoiceVolume(state.volume)

                // 模型下载进度
                if (state.isModelDownloading) {
                    binding.layoutVoiceModelDownload.visibility = View.VISIBLE
                    binding.progressModelDownload.progress = state.downloadProgress
                    binding.tvModelStatus.text = getString(R.string.voice_model_downloading, state.downloadProgress)
                    binding.btnDownloadModel.visibility = View.GONE
                } else {
                    binding.btnDownloadModel.visibility = View.VISIBLE
                }

                // 模型就绪状态
                if (state.isModelReady) {
                    binding.layoutVoiceModelDownload.visibility = View.GONE
                    binding.btnVoiceInput.isEnabled = true
                }

                // 错误提示
                state.error?.let { error ->
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateVoiceVolume(volume: Int) {
        // 根据音量更新波形条高度
        val baseHeight = 15
        val maxHeight = 55
        val bars = listOf(
            binding.voiceBar1,
            binding.voiceBar2,
            binding.voiceBar3,
            binding.voiceBar4,
            binding.voiceBar5,
            binding.voiceBar6,
            binding.voiceBar7
        )

        // 模拟波形效果，每个条有不同的随机因子
        val factors = floatArrayOf(0.5f, 0.7f, 0.9f, 1f, 0.85f, 0.6f, 0.4f)
        for (i in bars.indices) {
            val barHeight = baseHeight + (volume * factors[i] * (maxHeight - baseHeight) / 100).toInt()
            val layoutParams = bars[i].layoutParams
            layoutParams.height = barHeight.coerceIn(baseHeight, maxHeight)
            bars[i].layoutParams = layoutParams
        }
    }

    private fun checkVoiceModel() {
        lifecycleScope.launch {
            val ready = voiceRecognizer.loadModel()
            if (!ready) {
                binding.layoutVoiceModelDownload.visibility = View.VISIBLE
                binding.btnVoiceInput.isEnabled = false
            }
        }
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
            binding.tvBoxResult.visibility = View.VISIBLE
        } else {
            binding.tvBoxResult.visibility = View.GONE
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

        // 下载语音模型
        binding.btnDownloadModel.setOnClickListener {
            lifecycleScope.launch {
                val success = voiceRecognizer.downloadModel()
                if (success) {
                    Toast.makeText(this@MainActivity, "语音模型下载完成", Toast.LENGTH_SHORT).show()
                    binding.layoutVoiceModelDownload.visibility = View.GONE
                    binding.btnVoiceInput.isEnabled = true
                }
            }
        }

        // 语音按钮 - 按住说话
        binding.btnVoiceInput.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isCancelled = false
                    startY = event.rawY
                    checkPermissionAndStartVoice()
                    binding.btnVoiceInput.text = getString(R.string.release_to_send)
                    binding.layoutVoiceVisualizer.visibility = View.VISIBLE
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = startY - event.rawY
                    if (deltaY > CANCEL_THRESHOLD && !isCancelled) {
                        isCancelled = true
                        binding.tvReleaseHint.text = getString(R.string.release_to_cancel)
                        binding.tvReleaseHint.setTextColor(ContextCompat.getColor(this, R.color.error))
                    } else if (deltaY <= CANCEL_THRESHOLD && isCancelled) {
                        isCancelled = false
                        binding.tvReleaseHint.text = getString(R.string.release_to_send)
                        binding.tvReleaseHint.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val resultText = voiceRecognizer.stopListening()
                    binding.layoutVoiceVisualizer.visibility = View.GONE
                    binding.btnVoiceInput.text = getString(R.string.hold_to_talk)
                    binding.tvReleaseHint.setTextColor(ContextCompat.getColor(this, android.R.color.white))

                    if (!isCancelled && resultText.isNotEmpty()) {
                        processVoiceResult(resultText)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    voiceRecognizer.stopListening()
                    binding.layoutVoiceVisualizer.visibility = View.GONE
                    binding.btnVoiceInput.text = getString(R.string.hold_to_talk)
                    true
                }
                else -> false
            }
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

        if (!voiceRecognizer.isModelReady()) {
            Toast.makeText(this, R.string.voice_model_needed, Toast.LENGTH_SHORT).show()
            return
        }

        voiceRecognizer.startListening()
    }

    private fun processVoiceResult(text: String) {
        val pieces = parseVoiceInput(text)
        if (pieces > 0) {
            viewModel.addCompletedPieces(pieces)
            Toast.makeText(this, "已添加 $pieces 件", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.no_number_detected, Toast.LENGTH_SHORT).show()
        }
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
            "加上(\\d+)件",
            "加上(\\d+)",
            "再加(\\d+)件",
            "再加(\\d+)",
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

        // 尝试直接提取数字（如果文本较短且包含数字）
        val numberPattern = Pattern.compile("\\d+")
        val matcher = numberPattern.matcher(text)
        if (matcher.find() && text.replace("\\D".toRegex(), "").length <= 5) {
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
                if (voiceRecognizer.isModelReady()) {
                    voiceRecognizer.startListening()
                }
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceRecognizer.destroy()
    }
}
