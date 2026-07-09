package com.example.magneticnfc

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.magneticnfc.databinding.ActivityMainBinding
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: SensorViewModel
    private var nfcAdapter: NfcAdapter? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var isWritingMode = false
    private val writeTimeoutHandler = Handler(Looper.getMainLooper())
    private val writeTimeoutRunnable = Runnable {
        isWritingMode = false
        runOnUiThread {
            binding.btnWriteAar.text = getString(R.string.write_aar_btn)
            binding.btnWriteAar.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#03A9F4"))
            binding.tvWriteStatus.text = getString(R.string.write_aar_timeout)
            binding.tvWriteStatus.setTextColor(Color.parseColor("#F44336"))
        }
    }

    companion object {
        private val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[SensorViewModel::class.java]
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setupSensorObserver()
        setupNfcStatus()
        setupWriteButton()
        viewModel.startSensor(this)
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

    @SuppressLint("SetTextI18n")
    private fun setupNfcStatus() {
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

    // ---- Reader Mode: 前台直接读标签，无系统弹窗 ----

    override fun onResume() {
        super.onResume()
        setupNfcStatus()
        nfcAdapter?.let { adapter ->
            if (adapter.isEnabled) {
                val options = Bundle()
                options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000)
                adapter.enableReaderMode(this, this, READER_FLAGS, options)
                binding.tvNfcStatus.text = getString(R.string.nfc_ready)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        if (isWritingMode) {
            writeAarToTag(tag)
            return
        }

        val sb = StringBuilder()
        sb.appendLine("==== NFC TAG DETECTED ====")

        val tagId = tag.id.joinToString("") { "%02X".format(it) }
        sb.appendLine("ID:   $tagId")

        val techList = tag.techList?.joinToString(", ") {
            it.substringAfterLast(".")
        } ?: ""
        sb.appendLine("Tech: $techList")

        readNdef(tag, sb)
        readMifareClassic(tag, sb)
        readMifareUltralight(tag, sb)
        readGenericInfo(tag, sb)

        sb.appendLine("==========================")
        runOnUiThread {
            viewModel.onNfcTagScanned(sb.toString())
        }
    }

    private fun readNdef(tag: Tag, sb: StringBuilder) {
        try {
            val ndef = Ndef.get(tag) ?: return
            ndef.connect()
            sb.appendLine("NDEF: ${if (ndef.isWritable) "Writable" else "Read-only"}")
            sb.appendLine("MaxSize: ${ndef.maxSize} bytes")

            val msg = ndef.ndefMessage ?: ndef.cachedNdefMessage
            if (msg != null) {
                for ((idx, record) in msg.records.withIndex()) {
                    parseRecord(idx, record, sb)
                }
            } else {
                sb.appendLine("(no NDEF message)")
            }
            ndef.close()
        } catch (_: Exception) {}
    }

    private fun parseRecord(idx: Int, record: NdefRecord, sb: StringBuilder) {
        val tnf = record.tnf
        val type = String(record.type, Charsets.UTF_8)
        val payload = record.payload

        sb.append("  Record#$idx TNF=")
        sb.appendLine(tnfName(tnf))

        when {
            tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_TEXT) &&
                payload.size > 3 -> {
                val langLen = payload[0].toInt() and 0x3F
                val text = String(payload, langLen + 1, payload.size - langLen - 1, Charsets.UTF_8)
                sb.appendLine("  Text: $text")
            }

            tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_URI) &&
                payload.size > 1 -> {
                val prefix = uriPrefix(payload[0].toInt() and 0xFF)
                val uri = String(payload, 1, payload.size - 1, Charsets.UTF_8)
                sb.appendLine("  URI:  $prefix$uri")
            }

            tnf == NdefRecord.TNF_EXTERNAL_TYPE &&
                type.contentEquals("android.com:pkg") -> {
                val pkg = String(payload, Charsets.UTF_8)
                sb.appendLine("  AAR:  $pkg" +
                    if (pkg == packageName) " (this app)" else " (other)")
            }

            tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_SMART_POSTER) -> {
                sb.appendLine("  Smart Poster (nested)")
                try {
                    val nestedMsg = NdefMessage(payload)
                    for ((ni, nr) in nestedMsg.records.withIndex()) {
                        parseRecord(ni, nr, sb)
                    }
                } catch (_: Exception) {
                    sb.appendLine("    [parse failed]")
                }
            }

            else -> {
                sb.append("  Type: $type")
                if (payload.isNotEmpty()) {
                    sb.appendLine("")
                    sb.appendLine("  Payload (${payload.size}B): ${payload.toHex(64)}")
                } else {
                    sb.appendLine("")
                }
            }
        }
    }

    private fun readMifareClassic(tag: Tag, sb: StringBuilder) {
        try {
            val mfc = MifareClassic.get(tag) ?: return
            mfc.connect()
            sb.appendLine("MifareClassic:")
            sb.appendLine("  Type:   ${if (mfc.type == MifareClassic.TYPE_CLASSIC) "Classic" else "Plus"}")
            sb.appendLine("  Size:   ${mfc.size} bytes")
            sb.appendLine("  Sectors: ${mfc.sectorCount}")
            sb.appendLine("  Blocks:  ${mfc.blockCount}")
            mfc.close()
        } catch (_: Exception) {}
    }

    private fun readMifareUltralight(tag: Tag, sb: StringBuilder) {
        try {
            val mfu = MifareUltralight.get(tag) ?: return
            mfu.connect()
            sb.appendLine("MifareUltralight:")
            sb.appendLine("  Type:  ${mfu.type}")
            mfu.close()
        } catch (_: Exception) {}
    }

    private fun readGenericInfo(tag: Tag, sb: StringBuilder) {
        for (techName in tag.techList.orEmpty()) {
            try {
                when (techName) {
                    "android.nfc.tech.NfcA" -> {
                        val nfca = NfcA.get(tag)
                        nfca?.connect()
                        val sak = nfca?.sak?.let { "0x%02X".format(it) } ?: "?"
                        val atqa = nfca?.atqa?.let {
                            it.joinToString("") { b -> "%02X".format(b) }
                        } ?: "?"
                        sb.appendLine("NfcA:   SAK=$sak  ATQA=$atqa")
                        nfca?.close()
                    }
                    "android.nfc.tech.NfcB" -> {
                        NfcB.get(tag)?.let { nfcb ->
                            nfcb.connect()
                            sb.appendLine("NfcB:   detected")
                            nfcb.close()
                        }
                    }
                    "android.nfc.tech.NfcF" -> {
                        NfcF.get(tag)?.let { nfcf ->
                            nfcf.connect()
                            sb.appendLine("NfcF:   detected")
                            nfcf.close()
                        }
                    }
                    "android.nfc.tech.NfcV" -> {
                        NfcV.get(tag)?.let { nfcv ->
                            nfcv.connect()
                            sb.appendLine("NfcV:   detected")
                            nfcv.close()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun tnfName(tnf: Short): String = when (tnf) {
        NdefRecord.TNF_EMPTY -> "EMPTY"
        NdefRecord.TNF_WELL_KNOWN -> "RTD"
        NdefRecord.TNF_MIME_MEDIA -> "MIME"
        NdefRecord.TNF_ABSOLUTE_URI -> "URI"
        NdefRecord.TNF_EXTERNAL_TYPE -> "EXT"
        NdefRecord.TNF_UNKNOWN -> "UNKNOWN"
        NdefRecord.TNF_UNCHANGED -> "UNCHANGED"
        else -> "?($tnf)"
    }

    private fun uriPrefix(code: Int): String = when (code) {
        0x00 -> ""
        0x01 -> "http://www."
        0x02 -> "https://www."
        0x03 -> "http://"
        0x04 -> "https://"
        0x05 -> "tel:"
        0x06 -> "mailto:"
        0x07 -> "ftp://"
        0x08 -> "ftps://"
        0x09 -> "sftp://"
        0x0A -> "smb://"
        0x0B -> "nfs://"
        0x0C -> "ftp://ftp."
        0x0D -> "dav://"
        0x0E -> "news:"
        0x0F -> "telnet://"
        0x10 -> "imap:"
        0x11 -> "rtsp://"
        0x12 -> "urn:"
        0x13 -> "pop:"
        0x14 -> "sip:"
        0x15 -> "sips:"
        0x16 -> "tftp:"
        0x17 -> "btspp://"
        0x18 -> "btl2cap://"
        0x19 -> "btgoep://"
        0x1A -> "tcpobex://"
        0x1B -> "irdaobex://"
        0x1C -> "file://"
        0x1D -> "urn:epc:id:"
        0x1E -> "urn:epc:tag:"
        0x1F -> "urn:epc:pat:"
        0x20 -> "urn:epc:raw:"
        0x21 -> "urn:epc:"
        0x22 -> "urn:nfc:"
        else -> "?"
    }

    private fun ByteArray.toHex(maxBytes: Int = this.size): String {
        val len = minOf(size, maxBytes)
        val hex = take(len).joinToString(" ") { "%02X".format(it) }
        return if (size > maxBytes) "$hex ..." else hex
    }

    private fun setupWriteButton() {
        binding.btnWriteAar.setOnClickListener {
            if (isWritingMode) {
                cancelWriteMode()
            } else {
                enterWriteMode()
            }
        }
    }

    private fun enterWriteMode() {
        isWritingMode = true
        binding.btnWriteAar.text = getString(R.string.write_aar_cancel)
        binding.btnWriteAar.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5722"))
        binding.tvWriteStatus.text = getString(R.string.write_aar_waiting)
        binding.tvWriteStatus.setTextColor(Color.parseColor("#FFC107"))
        binding.tvWriteStatus.visibility = View.VISIBLE
        writeTimeoutHandler.postDelayed(writeTimeoutRunnable, 30_000)
    }

    private fun cancelWriteMode() {
        isWritingMode = false
        writeTimeoutHandler.removeCallbacks(writeTimeoutRunnable)
        binding.btnWriteAar.text = getString(R.string.write_aar_btn)
        binding.btnWriteAar.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#03A9F4"))
        binding.tvWriteStatus.visibility = View.GONE
    }

    private fun writeAarToTag(tag: Tag) {
        val aarRecord = NdefRecord.createApplicationRecord(packageName)
        val textBytes = "\u78C1\u529B NFC \u63A2\u6D4B\u5668".toByteArray(Charsets.UTF_8)
        val langBytes = "zh".toByteArray(Charsets.US_ASCII)
        val textPayload = ByteArray(1 + langBytes.size + textBytes.size)
        textPayload[0] = langBytes.size.toByte()
        System.arraycopy(langBytes, 0, textPayload, 1, langBytes.size)
        System.arraycopy(textBytes, 0, textPayload, 1 + langBytes.size, textBytes.size)
        val textRecord = NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), textPayload)
        val ndefMessage = NdefMessage(arrayOf(aarRecord, textRecord))

        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                if (ndef.isWritable) {
                    ndef.writeNdefMessage(ndefMessage)
                    ndef.close()
                    onWriteSuccess()
                } else {
                    ndef.close()
                    onWriteFailed(getString(R.string.write_aar_failed_not_ndef))
                }
            } else {
                val formatable = NdefFormatable.get(tag)
                if (formatable != null) {
                    formatable.connect()
                    formatable.format(ndefMessage)
                    formatable.close()
                    onWriteSuccess()
                } else {
                    onWriteFailed(getString(R.string.write_aar_failed_not_ndef))
                }
            }
        } catch (e: Exception) {
            onWriteFailed(getString(R.string.write_aar_failed_error, e.message ?: ""))
        }
    }

    private fun onWriteSuccess() {
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))

        runOnUiThread {
            writeTimeoutHandler.removeCallbacks(writeTimeoutRunnable)
            binding.btnWriteAar.text = getString(R.string.write_aar_btn)
            binding.btnWriteAar.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#03A9F4"))
            binding.tvWriteStatus.text = getString(R.string.write_aar_success)
            binding.tvWriteStatus.setTextColor(Color.parseColor("#4CAF50"))
            Handler(Looper.getMainLooper()).postDelayed({
                binding.tvWriteStatus.visibility = View.GONE
            }, 4000)
        }
    }

    private fun onWriteFailed(reason: String) {
        runOnUiThread {
            writeTimeoutHandler.removeCallbacks(writeTimeoutRunnable)
            binding.btnWriteAar.text = getString(R.string.write_aar_btn)
            binding.btnWriteAar.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#03A9F4"))
            binding.tvWriteStatus.text = reason
            binding.tvWriteStatus.setTextColor(Color.parseColor("#F44336"))
            Handler(Looper.getMainLooper()).postDelayed({
                binding.tvWriteStatus.visibility = View.GONE
            }, 4000)
        }
    }
}
