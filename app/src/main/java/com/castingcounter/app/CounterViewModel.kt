package com.castingcounter.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

data class EfficiencyPoint(
    val hour: Float,
    val piecesPerHour: Float
)

class CounterViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences = application.getSharedPreferences("casting_counter", Context.MODE_PRIVATE)

    // 任务设置
    private val _taskCount = MutableLiveData(0)
    val taskCount: LiveData<Int> = _taskCount

    private val _piecesPerBox = MutableLiveData(0)
    val piecesPerBox: LiveData<Int> = _piecesPerBox

    private val _totalBoxes = MutableLiveData(0)
    val totalBoxes: LiveData<Int> = _totalBoxes

    // 已完成件数
    private val _completedCount = MutableLiveData(0)
    val completedCount: LiveData<Int> = _completedCount

    // 上下班时间
    private val _startHour = MutableLiveData(8)
    val startHour: LiveData<Int> = _startHour

    private val _startMinute = MutableLiveData(0)
    val startMinute: LiveData<Int> = _startMinute

    private val _endHour = MutableLiveData(17)
    val endHour: LiveData<Int> = _endHour

    private val _endMinute = MutableLiveData(0)
    val endMinute: LiveData<Int> = _endMinute

    private val _isNextDay = MutableLiveData(false)
    val isNextDay: LiveData<Boolean> = _isNextDay

    // 效率
    private val _efficiency = MutableLiveData(0f)
    val efficiency: LiveData<Float> = _efficiency

    // 预计完成时间
    private val _estimatedCompletion = MutableLiveData("")
    val estimatedCompletion: LiveData<String> = _estimatedCompletion

    private val _estimatedDuration = MutableLiveData("")
    val estimatedDuration: LiveData<String> = _estimatedDuration

    // 效率数据点（用于折线图）
    private val _efficiencyPoints = MutableLiveData<List<EfficiencyPoint>>(emptyList())
    val efficiencyPoints: LiveData<List<EfficiencyPoint>> = _efficiencyPoints

    private var updateJob: Job? = null
    private var startTimeMillis: Long = 0

    init {
        loadData()
        startEfficiencyUpdate()
    }

    fun setTaskCount(count: Int) {
        _taskCount.value = count
        calculateBoxes()
        saveData()
    }

    fun setPiecesPerBox(pieces: Int) {
        _piecesPerBox.value = pieces
        calculateBoxes()
        saveData()
    }

    private fun calculateBoxes() {
        val task = _taskCount.value ?: 0
        val perBox = _piecesPerBox.value ?: 0
        if (task > 0 && perBox > 0) {
            _totalBoxes.value = ceil(task.toDouble() / perBox.toDouble()).toInt()
        } else {
            _totalBoxes.value = 0
        }
    }

    fun addCompletedPieces(pieces: Int) {
        val current = _completedCount.value ?: 0
        val newCount = current + pieces
        _completedCount.value = newCount
        addEfficiencyDataPoint()
        updateEstimates()
        saveData()
    }

    fun setWorkTime(startH: Int, startM: Int, endH: Int, endM: Int, nextDay: Boolean) {
        _startHour.value = startH
        _startMinute.value = startM
        _endHour.value = endH
        _endMinute.value = endM
        _isNextDay.value = nextDay

        // 计算上班开始时间（今天）
        val now = Calendar.getInstance()
        val startCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startH)
            set(Calendar.MINUTE, startM)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        startTimeMillis = startCal.timeInMillis

        // 如果开始时间在当前时间之后，可能是昨天开始的夜班
        if (startCal.after(now)) {
            startCal.add(Calendar.DAY_OF_MONTH, -1)
            startTimeMillis = startCal.timeInMillis
        }

        updateEstimates()
        saveData()
    }

    private fun startEfficiencyUpdate() {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            while (true) {
                updateEfficiency()
                updateEstimates()
                delay(60000) // 每分钟更新一次
            }
        }
    }

    private fun updateEfficiency() {
        val completed = _completedCount.value ?: 0
        if (completed == 0 || startTimeMillis == 0L) {
            _efficiency.value = 0f
            return
        }

        val now = System.currentTimeMillis()
        val elapsedMillis = now - startTimeMillis
        if (elapsedMillis <= 0) {
            _efficiency.value = 0f
            return
        }

        val elapsedHours = elapsedMillis.toFloat() / TimeUnit.HOURS.toMillis(1)
        _efficiency.value = completed.toFloat() / elapsedHours
    }

    private fun addEfficiencyDataPoint() {
        val completed = _completedCount.value ?: 0
        if (completed == 0 || startTimeMillis == 0L) return

        val now = System.currentTimeMillis()
        val elapsedMillis = now - startTimeMillis
        if (elapsedMillis <= 0) return

        val elapsedHours = elapsedMillis.toFloat() / TimeUnit.HOURS.toMillis(1)
        val efficiency = completed.toFloat() / elapsedHours

        val currentList = _efficiencyPoints.value?.toMutableList() ?: mutableListOf()
        currentList.add(EfficiencyPoint(elapsedHours, efficiency))
        // 保留最近50个数据点
        if (currentList.size > 50) {
            currentList.removeAt(0)
        }
        _efficiencyPoints.value = currentList
    }

    private fun updateEstimates() {
        val task = _taskCount.value ?: 0
        val completed = _completedCount.value ?: 0
        val eff = _efficiency.value ?: 0f

        if (task <= 0 || completed <= 0 || eff <= 0f) {
            _estimatedCompletion.value = "--"
            _estimatedDuration.value = "--"
            return
        }

        val remaining = task - completed
        if (remaining <= 0) {
            _estimatedCompletion.value = "已完成！"
            _estimatedDuration.value = "已完成！"
            return
        }

        // 预计还需要的小时数
        val remainingHours = remaining.toFloat() / eff

        // 预计总耗时
        val totalHours = task.toFloat() / eff
        val totalHoursInt = totalHours.toInt()
        val totalMinutes = ((totalHours - totalHoursInt) * 60).toInt()
        _estimatedDuration.value = String.format("%d小时%d分钟", totalHoursInt, totalMinutes)

        // 预计完成时间
        val now = Calendar.getInstance()
        now.add(Calendar.MINUTE, (remainingHours * 60).toInt())
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val dayOffset = now.get(Calendar.DAY_OF_YEAR) - Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val dayStr = if (dayOffset > 0) " (+${dayOffset}天)" else ""
        _estimatedCompletion.value = String.format("%02d:%02d%s", hour, minute, dayStr)
    }

    fun resetData() {
        _completedCount.value = 0
        _efficiencyPoints.value = emptyList()
        _efficiency.value = 0f
        _estimatedCompletion.value = "--"
        _estimatedDuration.value = "--"

        // 重置开始时间为今天的上班时间
        val startCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, _startHour.value ?: 8)
            set(Calendar.MINUTE, _startMinute.value ?: 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        startTimeMillis = startCal.timeInMillis

        saveData()
    }

    private fun saveData() {
        prefs.edit().apply {
            putInt("taskCount", _taskCount.value ?: 0)
            putInt("piecesPerBox", _piecesPerBox.value ?: 0)
            putInt("completedCount", _completedCount.value ?: 0)
            putInt("startHour", _startHour.value ?: 8)
            putInt("startMinute", _startMinute.value ?: 0)
            putInt("endHour", _endHour.value ?: 17)
            putInt("endMinute", _endMinute.value ?: 0)
            putBoolean("isNextDay", _isNextDay.value ?: false)
            apply()
        }
    }

    private fun loadData() {
        _taskCount.value = prefs.getInt("taskCount", 0)
        _piecesPerBox.value = prefs.getInt("piecesPerBox", 0)
        _completedCount.value = prefs.getInt("completedCount", 0)
        _startHour.value = prefs.getInt("startHour", 8)
        _startMinute.value = prefs.getInt("startMinute", 0)
        _endHour.value = prefs.getInt("endHour", 17)
        _endMinute.value = prefs.getInt("endMinute", 0)
        _isNextDay.value = prefs.getBoolean("isNextDay", false)

        calculateBoxes()

        // 设置开始时间
        val startCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, _startHour.value ?: 8)
            set(Calendar.MINUTE, _startMinute.value ?: 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = Calendar.getInstance()
        if (startCal.after(now)) {
            startCal.add(Calendar.DAY_OF_MONTH, -1)
        }
        startTimeMillis = startCal.timeInMillis

        updateEfficiency()
        updateEstimates()
    }

    fun getWorkDurationHours(): Float {
        val startH = _startHour.value ?: 8
        val startM = _startMinute.value ?: 0
        val endH = _endHour.value ?: 17
        val endM = _endMinute.value ?: 0
        val nextDay = _isNextDay.value ?: false

        var startMinutes = startH * 60 + startM
        var endMinutes = endH * 60 + endM

        if (nextDay || endMinutes < startMinutes) {
            endMinutes += 24 * 60
        }

        return (endMinutes - startMinutes).toFloat() / 60f
    }
}
