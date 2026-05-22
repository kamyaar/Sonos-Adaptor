package com.example.sonosadaptor

sealed class AudioOutput {
    object PhoneSpeaker : AudioOutput()
    data class SonosSpeaker(
        val name: String,
        val host: String,
        val port: Int,
        val avTransportControlUrl: String
    ) : AudioOutput()
}
