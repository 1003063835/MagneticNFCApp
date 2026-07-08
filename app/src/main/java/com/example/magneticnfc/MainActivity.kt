package com.example.magneticnfc

import android.annotation.SuppressLint
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.magneticnfc.databinding.ActivityMainBinding
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: SensorViewModel
    private var nfcAdapter: NfcAdapter? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[SensorViewModel::class.java]
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setupSensorObserver()
        setupNfc()
        viewModel.startSensor(this)
        handleNfcIntent(intent)
    }

    private fun setupSensorObserver() {
        viewModel.sensorAvailable.observe(this) { available ->
            if (!available) {
                binding.tvStatus.text = getString(R.string.sensor_unavailable)
                binding.layoutSensorData.alpha = 0.3f
            }
        }

        viewModel.magneticData.observe(this) { data ->
            val timestamp = dateFormat.format(Date())
            binding.tvTimestamp.text = getString(R.string.last_update, timestamp)
            binding.tvMagX.text = getString(R.string.mag_x_value, data.x)
            binding.tvMagY.text = getString(R.string.mag_y_value, data.y)
            binding.tvMagZ.text = getString(R.string.mag_z_value, data.z)
            binding.tvMagTotal.text = getString(R.string.mag_total_value, data.total)

            binding.tvStrength.text = when {
                data.total < 10f -> getString(R.string.strength_very_weak)
                data.total < 30f -> getString(R.string.strength_weak)
                data.total < 60f -> getString(R.string.strength_normal)
                data.total < 100f -> getString(R.string.strength_strong)
                else -> getString(R.string.strength_very_strong)
            }

            binding.progressBar.progress = data.total.coerceIn(0f, 200f).toInt()
        }

        viewModel.nfcMessage.observe(this) { message ->
            if (message.isNotEmpty()) {
                binding.tvNfcInfo.text = message
            }
        }
    }

    private fun setupNfc() {
        if (nfcAdapter == null) {
            binding.tvNfcStatus.text = getString(R.string.nfc_unavailable)
            return
        }

        if (!nfcAdapter!!.isEnabled) {
            binding.tvNfcStatus.text = getString(R.string.nfc_disabled)
        } else {
            binding.tvNfcStatus.text = getString(R.string.nfc_ready)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED
            && action != NfcAdapter.ACTION_TECH_DISCOVERED
            && action != NfcAdapter.ACTION_TAG_DISCOVERED
        ) return

        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag == null) return

        val tagId = tag.id.joinToString("") { "%02X".format(it) }
        val techList = tag.techList?.joinToString(", ") { it.substringAfterLast(".") } ?: ""

        val ndefMessage = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        val sb = StringBuilder()
        sb.appendLine(getString(R.string.nfc_tag_detected))
        sb.appendLine(getString(R.string.nfc_tag_id, tagId))
        sb.appendLine(getString(R.string.nfc_tag_tech, techList))

        if (ndefMessage != null && ndefMessage.isNotEmpty()) {
            val ndef = ndefMessage[0] as NdefMessage
            for (record in ndef.records) {
                val payload = record.payload
                val text = String(payload, 3, payload.size - 3, Charset.forName("UTF-8"))
                sb.appendLine(getString(R.string.nfc_payload, text))
            }
        } else {
            sb.appendLine(getString(R.string.nfc_no_payload))
        }

        viewModel.onNfcTagScanned(sb.toString())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(
            this,
            android.app.PendingIntent.getActivity(
                this, 0,
                Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE
            ),
            null,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopSensor()
    }
}
