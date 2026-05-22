package com.example.sonosadaptor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.IBinder
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class AudioPassthroughService : Service() {

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var streamServer: AudioStreamServer? = null
    private var isRunning = false
    private var audioThread: Thread? = null
    private var sampleRate = 48000

    @Volatile private var currentOutput: AudioOutput = AudioOutput.PhoneSpeaker

    enum class OutputStatus { IDLE, CONNECTED, CONNECTING }

    companion object {
        const val ACTION_START = "com.example.sonosadaptor.ACTION_START"
        const val ACTION_STOP = "com.example.sonosadaptor.ACTION_STOP"
        const val ACTION_SET_OUTPUT_PHONE = "com.example.sonosadaptor.ACTION_SET_OUTPUT_PHONE"
        const val ACTION_SET_OUTPUT_SONOS = "com.example.sonosadaptor.ACTION_SET_OUTPUT_SONOS"
        const val ACTION_TEST_TONE = "com.example.sonosadaptor.ACTION_TEST_TONE"
        const val EXTRA_SONOS_NAME = "sonos_name"
        const val EXTRA_SONOS_HOST = "sonos_host"
        const val EXTRA_SONOS_PORT = "sonos_port"
        const val EXTRA_SONOS_CONTROL_URL = "sonos_control_url"

        // Observed by MainActivity via Compose state — same process, no IPC needed.
        val outputStatus = mutableStateOf(OutputStatus.IDLE)

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sonos_adaptor_channel"
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val STREAM_PORT = 8888
        private val PREFERRED_SAMPLE_RATES = intArrayOf(48000, 44100, 96000, 32000, 16000)

        private fun pickSampleRate(device: AudioDeviceInfo?): Int {
            if (device == null) return 48000
            val supported = device.sampleRates
            if (supported.isEmpty()) return 48000
            return PREFERRED_SAMPLE_RATES.firstOrNull { it in supported.toList() }
                ?: supported.last()
        }

        fun getPhoneIpAddress(): String? {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.asSequence() ?: return null
            // Prefer the WiFi interface (wlan0 etc.) so a USB-tethering or adb
            // RNDIS address isn't returned to Sonos instead of the WiFi IP.
            return ifaces
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startPassthrough()
            ACTION_STOP -> {
                stopPassthrough()
                stopSelf()
            }
            ACTION_SET_OUTPUT_PHONE -> switchToPhone()
            ACTION_TEST_TONE -> playTestTone()
            ACTION_SET_OUTPUT_SONOS -> {
                val device = AudioOutput.SonosSpeaker(
                    name = intent.getStringExtra(EXTRA_SONOS_NAME) ?: "",
                    host = intent.getStringExtra(EXTRA_SONOS_HOST) ?: return START_NOT_STICKY,
                    port = intent.getIntExtra(EXTRA_SONOS_PORT, 1400),
                    avTransportControlUrl = intent.getStringExtra(EXTRA_SONOS_CONTROL_URL)
                        ?: return START_NOT_STICKY
                )
                switchToSonos(device)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Start / Stop ──────────────────────────────────────────────────────────

    private fun startPassthrough() {
        if (isRunning) return
        startForeground(NOTIFICATION_ID, buildNotification("Listening on USB-C → Phone speaker"))

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val usbInputDevice = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }

        sampleRate = pickSampleRate(usbInputDevice)

        val inBufSize = maxOf(
            AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG_IN, AUDIO_FORMAT), 4096
        )

        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(CHANNEL_CONFIG_IN)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(inBufSize * 4)
            .build()
            .also { usbInputDevice?.let { dev -> it.preferredDevice = dev } }

        val outBufSize = maxOf(
            AudioTrack.getMinBufferSize(sampleRate, CHANNEL_CONFIG_OUT, AUDIO_FORMAT), 4096
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(CHANNEL_CONFIG_OUT)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(outBufSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track ->
                audioManager
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { track.preferredDevice = it }
            }

        // If a Sonos speaker was pre-selected (before Start was pressed), re-initiate
        // the connection now that we know the correct sample rate.  switchToSonos
        // rebuilds the stream server with the right rate and re-sends the SOAP command.
        // Otherwise start routing to the phone speaker immediately.
        val preSelected = currentOutput as? AudioOutput.SonosSpeaker
        if (preSelected != null) {
            switchToSonos(preSelected)
        } else {
            currentOutput = AudioOutput.PhoneSpeaker
            outputStatus.value = OutputStatus.CONNECTED
            audioTrack?.play()
        }

        isRunning = true
        audioRecord?.startRecording()
        audioThread = Thread(::captureLoop, "audio-passthrough").also { it.start() }
    }

    private fun stopPassthrough() {
        isRunning = false
        outputStatus.value = OutputStatus.IDLE
        audioThread?.join(2000)
        audioThread = null

        streamServer?.stop()
        streamServer = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    // ── Output switching ──────────────────────────────────────────────────────

    private fun switchToPhone() {
        val prev = currentOutput
        currentOutput = AudioOutput.PhoneSpeaker
        outputStatus.value = OutputStatus.CONNECTED
        val track = audioTrack
        android.util.Log.i("AudioPassthrough", "switchToPhone: audioTrack=$track playState=${track?.playState}")
        track?.stop()
        track?.flush()
        track?.play()
        android.util.Log.i("AudioPassthrough", "switchToPhone: after play, playState=${track?.playState}")
        if (prev is AudioOutput.SonosSpeaker) {
            Thread({ SonosController.stop(prev) }, "sonos-stop").start()
            streamServer?.stop()
            streamServer = null
        }
        updateNotification("Listening on USB-C → Phone speaker")
    }

    private fun switchToSonos(device: AudioOutput.SonosSpeaker) {
        streamServer?.stop()
        streamServer = null

        val phoneIp = getPhoneIpAddress() ?: run {
            android.util.Log.e("AudioPassthrough", "No IP address — falling back to phone speaker")
            switchToPhone()
            return
        }

        outputStatus.value = OutputStatus.CONNECTING

        val server = AudioStreamServer(sampleRate, channels = 2)
        server.start()
        streamServer = server

        audioTrack?.pause()
        currentOutput = device

        val streamUrl = "http://$phoneIp:$STREAM_PORT/stream.wav"
        android.util.Log.i("AudioPassthrough", "Connecting to ${device.name} @ ${device.host}:${device.port}${device.avTransportControlUrl} — stream $streamUrl")
        Thread({
            SonosController.playUri(device, streamUrl)
            android.util.Log.i("AudioPassthrough", "SOAP playUri sent to ${device.name}")
        }, "sonos-play").start()

        updateNotification("Connecting to ${device.name}…")
    }

    // ── Test tone ─────────────────────────────────────────────────────────────

    private fun playTestTone() {
        Thread({
            val rate = sampleRate.takeIf { it > 0 } ?: 48000
            val durationMs = 600
            val numSamples = rate * durationMs / 1000
            // Two-tone ding: 880 Hz fading into 660 Hz
            val shortBuf = ShortArray(numSamples * 2)
            val byteBuf = ByteArray(numSamples * 4)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / rate
                val env = if (i < numSamples / 10) i.toDouble() / (numSamples / 10)
                          else 1.0 - (i - numSamples / 10).toDouble() / (numSamples * 9 / 10)
                val freq = if (i < numSamples / 2) 880.0 else 660.0
                val s = (kotlin.math.sin(2.0 * kotlin.math.PI * freq * t) * Short.MAX_VALUE * 0.6 * env).toInt().toShort()
                shortBuf[i * 2] = s
                shortBuf[i * 2 + 1] = s
                byteBuf[i * 4] = (s.toInt() and 0xff).toByte()
                byteBuf[i * 4 + 1] = (s.toInt() shr 8 and 0xff).toByte()
                byteBuf[i * 4 + 2] = byteBuf[i * 4]
                byteBuf[i * 4 + 3] = byteBuf[i * 4 + 1]
            }
            when (currentOutput) {
                is AudioOutput.PhoneSpeaker -> audioTrack?.write(shortBuf, 0, shortBuf.size)
                is AudioOutput.SonosSpeaker -> streamServer?.write(byteBuf, byteBuf.size)
            }
        }, "test-tone").start()
    }

    // ── Audio capture loop ────────────────────────────────────────────────────

    private fun captureLoop() {
        val record = audioRecord ?: return
        val shortBuf = ShortArray(record.bufferSizeInFrames.coerceAtLeast(4096))
        val byteBuf = ByteArray(shortBuf.size * 2)

        while (isRunning) {
            val read = record.read(shortBuf, 0, shortBuf.size)
            if (read <= 0) continue

            when (val out = currentOutput) {
                is AudioOutput.PhoneSpeaker -> {
                    audioTrack?.write(shortBuf, 0, read)
                }
                is AudioOutput.SonosSpeaker -> {
                    for (i in 0 until read) {
                        val s = shortBuf[i].toInt()
                        byteBuf[i * 2] = (s and 0xff).toByte()
                        byteBuf[i * 2 + 1] = (s shr 8 and 0xff).toByte()
                    }
                    val server = streamServer
                    if (server != null) {
                        // Detect the moment Sonos connects and starts pulling audio.
                        if (server.isClientConnected() &&
                            outputStatus.value == OutputStatus.CONNECTING
                        ) {
                            outputStatus.value = OutputStatus.CONNECTED
                            updateNotification("Listening on USB-C → ${out.name}")
                        }
                        server.write(byteBuf, read * 2)
                    } else {
                        switchToPhone()
                    }
                }
            }
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Sonos Adaptor", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Audio passthrough service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sonos Adaptor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopPassthrough()
        super.onDestroy()
    }
}
