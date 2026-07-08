package com.example.magneticnfc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlin.math.sqrt

data class MagneticData(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val total: Float = 0f
)

class SensorViewModel : ViewModel() {

    private val _magneticData = MutableLiveData(MagneticData())
    val magneticData: LiveData<MagneticData> = _magneticData

    private val _sensorAvailable = MutableLiveData(true)
    val sensorAvailable: LiveData<Boolean> = _sensorAvailable

    private val _nfcMessage = MutableLiveData("")
    val nfcMessage: LiveData<String> = _nfcMessage

    private var sensorManager: SensorManager? = null
    private var magneticSensor: Sensor? = null

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val total = sqrt(x * x + y * y + z * z)
                _magneticData.postValue(MagneticData(x, y, z, total))
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

    override fun onCleared() {
        super.onCleared()
        stopSensor()
    }
}
