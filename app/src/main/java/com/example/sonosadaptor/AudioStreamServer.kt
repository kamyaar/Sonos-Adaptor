package com.example.sonosadaptor

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal HTTP server that streams a continuous WAV audio feed.
 * Sonos connects and pulls audio; if it disconnects it is welcome to reconnect.
 */
class AudioStreamServer(private val sampleRate: Int, private val channels: Int) {

    val port = 8888
    private val TAG = "AudioStreamServer"

    private var serverSocket: ServerSocket? = null
    @Volatile private var clientOut: OutputStream? = null
    @Volatile private var accepting = false

    fun start() {
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        accepting = true
        android.util.Log.i(TAG, "Stream server started on port $port")
        Thread(::acceptLoop, "wav-server-accept").start()
    }

    private fun acceptLoop() {
        while (accepting) {
            try {
                val client: Socket = serverSocket?.accept() ?: break
                android.util.Log.i(TAG, "Client connected: ${client.inetAddress.hostAddress}")
                clientOut = null          // Drop any stale previous connection
                Thread({ handleClient(client) }, "wav-server-client").start()
            } catch (e: Exception) {
                if (accepting) android.util.Log.e(TAG, "Accept error: $e")
            }
        }
        android.util.Log.i(TAG, "Accept loop exited")
    }

    private fun handleClient(socket: Socket) {
        try {
            // Consume the HTTP request headers.
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val request = reader.readLine()
            android.util.Log.i(TAG, "HTTP request: $request")
            while (reader.readLine()?.isNotEmpty() == true) { /* drain */ }

            val out = socket.getOutputStream()
            out.write(
                "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nConnection: close\r\n\r\n"
                    .toByteArray()
            )
            out.write(buildWavHeader())
            out.flush()
            android.util.Log.i(TAG, "WAV header sent, streaming audio…")
            clientOut = out
            // Keep the thread alive so the socket stays open — writes come from the audio thread.
            socket.inputStream.read() // blocks until client disconnects
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Client disconnected: $e")
        } finally {
            clientOut = null
            android.util.Log.i(TAG, "Client handler exited")
        }
    }

    /** Write raw PCM bytes (little-endian 16-bit). No-op if no client is connected. */
    fun write(bytes: ByteArray, length: Int) {
        val out = clientOut ?: return
        try {
            out.write(bytes, 0, length)
            out.flush()
        } catch (_: Exception) {
            clientOut = null
        }
    }

    fun isClientConnected(): Boolean = clientOut != null

    fun stop() {
        android.util.Log.i(TAG, "Stream server stopping")
        accepting = false
        clientOut = null
        serverSocket?.close()
        serverSocket = null
    }

    private fun buildWavHeader(): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        // Use near-max values so players treat this as an effectively infinite stream.
        val dataSize = 0x7FFFFFFF - 36
        val riffSize = dataSize + 36

        return ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put("RIFF".toByteArray())
            putInt(riffSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)                    // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(16)                   // bits per sample
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
    }
}
