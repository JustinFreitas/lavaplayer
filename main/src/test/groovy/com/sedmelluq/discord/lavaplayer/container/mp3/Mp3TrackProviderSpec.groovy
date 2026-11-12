package com.sedmelluq.discord.lavaplayer.container.mp3

import com.sedmelluq.discord.lavaplayer.filter.AudioPipeline
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerOptions
import com.sedmelluq.discord.lavaplayer.tools.io.ByteArraySeekableInputStream
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameBuffer
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext
import spock.lang.Specification

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class Mp3TrackProviderSpec extends Specification {

    private static byte[] syncSafeInt(int value) {
        return [
            (byte) ((value >> 21) & 0x7F),
            (byte) ((value >> 14) & 0x7F),
            (byte) ((value >> 7) & 0x7F),
            (byte) (value & 0x7F)
        ] as byte[]
    }

    private static byte[] buildTxxxFrame(String key, String value) {
        byte[] content = "${key}\0${value}".getBytes(StandardCharsets.UTF_8)
        int frameSize = 1 + content.length // encoding byte (3 = UTF-8) + text
        def out = new ByteArrayOutputStream()
        out.write("TXXX".getBytes(StandardCharsets.ISO_8859_1))
        // ID3v2.3 uses big-endian 32-bit int for frame size
        out.write((frameSize >> 24) & 0xFF)
        out.write((frameSize >> 16) & 0xFF)
        out.write((frameSize >> 8) & 0xFF)
        out.write(frameSize & 0xFF)
        out.write(0) // flags byte 1
        out.write(0) // flags byte 2
        out.write(3) // UTF-8 encoding
        out.write(content)
        return out.toByteArray()
    }

    private static byte[] buildId3v23Block(List<byte[]> frames) {
        def payload = new ByteArrayOutputStream()
        frames.each { payload.write(it) }
        byte[] body = payload.toByteArray()

        def out = new ByteArrayOutputStream()
        out.write("ID3".getBytes(StandardCharsets.ISO_8859_1))
        out.write(3) // major version 2.3
        out.write(0) // minor version
        out.write(0) // flags
        out.write(syncSafeInt(body.length))
        out.write(body)
        return out.toByteArray()
    }

    /**
     * MPEG-2 Layer III, 64 kbps (index 8), 24000 Hz (index 1), stereo, no padding:
     * samplesPerFrame = 576, frameSize = (576 / 8) * 64000 / 24000 = 192 bytes exact.
     */
    private static byte[] buildMpeg2CbrFrames(int count) {
        def out = new ByteArrayOutputStream()
        for (int i = 0; i < count; i++) {
            byte[] frame = new byte[192]
            frame[0] = (byte) 0xFF
            frame[1] = (byte) 0xF3 // MPEG-2 (10), Layer III (01), no CRC (1)
            frame[2] = (byte) 0x84 // bitrate index 8 (64 kbps), samplerate index 1 (24000 Hz)
            frame[3] = (byte) 0x00 // stereo
            out.write(frame)
        }
        return out.toByteArray()
    }

    def "seekToTimecode uses MPEG-2 576 samplesPerFrame instead of hardcoded 1152"() {
        given:
        // 100 frames * 576 samples / 24000 Hz = 2400 ms total duration
        byte[] mp3Data = buildMpeg2CbrFrames(100)
        def config = new AudioConfiguration()
        def context = new AudioProcessingContext(config, Mock(AudioFrameBuffer), new AudioPlayerOptions(), StandardAudioDataFormats.DISCORD_OPUS)
        def provider = new Mp3TrackProvider(context, new ByteArraySeekableInputStream(mp3Data))
        def mockPipeline = Mock(AudioPipeline)

        when:
        provider.parseHeaders()
        provider.downstream = mockPipeline
        provider.seekToTimecode(1200L)

        then:
        provider.getDuration() == 2400L
        1 * mockPipeline.seekPerformed(1200L, 1200L)

        cleanup:
        provider.close()
    }

    def "TXXX ReplayGain parsing matches exact NUL-delimited keys under Locale.ROOT and ignores substring collisions"() {
        given:
        def previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))

        byte[] id3 = buildId3v23Block([
            buildTxxxFrame("replaygain_track_gain", "-6.0 dB"),
            buildTxxxFrame("replaygain_track_peak", "1.0"),
            // Substring collision frames that previously overwrote replayGainTxxx / replayGainPeakTxxx:
            buildTxxxFrame("COMMENT", "Contains REPLAYGAIN_TRACK_GAIN mention"),
            buildTxxxFrame("MY_REPLAYGAIN_TRACK_GAIN_BACKUP", "+12.0 dB"),
            buildTxxxFrame("EXTRA_REPLAYGAIN_TRACK_PEAK_NOTE", "0.1")
        ])
        byte[] frames = buildMpeg2CbrFrames(10)
        def combined = new ByteArrayOutputStream()
        combined.write(id3)
        combined.write(frames)

        def config = new AudioConfiguration()
        config.setReplayGainEnabled(true)
        def context = new AudioProcessingContext(config, Mock(AudioFrameBuffer), new AudioPlayerOptions(), StandardAudioDataFormats.DISCORD_OPUS)
        def provider = new Mp3TrackProvider(context, new ByteArraySeekableInputStream(combined.toByteArray()))

        when:
        provider.parseHeaders()

        then:
        provider.getReplayGainDb() == -6.0f
        Math.abs(provider.getVolumeMultiplier() - (float) Math.pow(10, -6.0 / 20.0)) < 0.0001f

        cleanup:
        provider?.close()
        Locale.setDefault(previousLocale)
    }
}
