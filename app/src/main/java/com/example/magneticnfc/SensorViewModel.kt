package com.example.magneticnfc

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONObject
import kotlin.math.sqrt

data class MagneticData(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val total: Float = 0f
)

data class RecognitionResult(
    val position: Int,
    val distance: Float
)

class SensorViewModel(application: Application) : AndroidViewModel(application) {

    data class CalibrationPoint(
        val x: Float,
        val y: Float,
        val z: Float,
        val count: Int
    )

    private val prefs = application.getSharedPreferences("wheel_calib", Context.MODE_PRIVATE)

    private val _magneticData = MutableLiveData(MagneticData())
    val magneticData: LiveData<MagneticData> = _magneticData

    private val _sensorAvailable = MutableLiveData(true)
    val sensorAvailable: LiveData<Boolean> = _sensorAvailable

    private val _nfcMessage = MutableLiveData("")
    val nfcMessage: LiveData<String> = _nfcMessage

    private val calibrationData = mutableMapOf<Int, CalibrationPoint>()

    private val _calibratedPositions = MutableLiveData<Set<Int>>(emptySet())
    val calibratedPositions: LiveData<Set<Int>> = _calibratedPositions

    private val _recognizedPosition = MutableLiveData<RecognitionResult?>()
    val recognizedPosition: LiveData<RecognitionResult?> = _recognizedPosition

    init {
        loadCalibration()
    }

    private var sensorManager: SensorManager? = null
    private var magneticSensor: Sensor? = null

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val total = sqrt(x * x + y * y + z * z)
                val data = MagneticData(x, y, z, total)
                _magneticData.postValue(data)
                recognize(data)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun startSensor(context: Context) {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magneticSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (magneticSensor == null) {
            _sensorAvailable.postValue(false)
            return
        }

        sensorManager?.registerListener(
            sensorEventListener,
            magneticSensor,
            SensorManager.SENSOR_DELAY_UI
        )
    }

    fun stopSensor() {
        sensorManager?.unregisterListener(sensorEventListener)
    }

    fun onNfcTagScanned(message: String) {
        _nfcMessage.postValue(message)
    }

    fun clearNfcMessage() {
        _nfcMessage.postValue("")
    }

    fun calibrate(position: Int, data: MagneticData) {
        val existing = calibrationData[position]
        if (existing != null) {
            val newCount = existing.count + 1
            val newX = (existing.x * existing.count + data.x) / newCount
            val newY = (existing.y * existing.count + data.y) / newCount
            val newZ = (existing.z * existing.count + data.z) / newCount
            calibrationData[position] = CalibrationPoint(newX, newY, newZ, newCount)
        } else {
            calibrationData[position] = CalibrationPoint(data.x, data.y, data.z, 1)
        }
        _calibratedPositions.postValue(calibrationData.keys.toSet())
        saveCalibration()
        recognize(data)
    }

    private fun recognize(data: MagneticData) {
        if (calibrationData.isEmpty()) {
            _recognizedPosition.postValue(null)
            return
        }
        var bestPosition = -1
        var bestDistance = Float.MAX_VALUE
        for ((pos, cp) in calibrationData) {
            val dx = data.x - cp.x
            val dy = data.y - cp.y
            val dz = data.z - cp.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist < bestDistance) {
                bestDistance = dist
                bestPosition = pos
            }
        }
        if (bestPosition >= 0) {
            _recognizedPosition.postValue(RecognitionResult(bestPosition, bestDistance))
        }
    }

    fun clearCalibration() {
        calibrationData.clear()
        _calibratedPositions.postValue(emptySet())
        _recognizedPosition.postValue(null)
        prefs.edit().remove("data").apply()
    }

    private fun loadCalibration() {
        try {
            val json = prefs.getString("data", null) ?: return
            val root = JSONObject(json)
            for (key in root.keys()) {
                val pos = key.toInt()
                val obj = root.getJSONObject(key)
                calibrationData[pos] = CalibrationPoint(
                    obj.getDouble("x").toFloat(),
                    obj.getDouble("y").toFloat(),
                    obj.getDouble("z").toFloat(),
                    obj.getInt("count")
                )
            }
            _calibratedPositions.postValue(calibrationData.keys.toSet())
        } catch (_: Exception) {}
    }

    private fun saveCalibration() {
        val root = JSONObject()
        for ((pos, cp) in calibrationData) {
            val obj = JSONObject()
            obj.put("x", cp.x.toDouble())
            obj.put("y", cp.y.toDouble())
            obj.put("z", cp.z.toDouble())
            obj.put("count", cp.count)
            root.put(pos.toString(), obj)
        }
        prefs.edit().putString("data", root.toString()).apply()
    }

    override fun onCleared() {
        super.onCleared()
        stopSensor()
    }
}
