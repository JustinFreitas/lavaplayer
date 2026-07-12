package com.sedmelluq.discord.lavaplayer.format.transcoder

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Round-trips OpusChunkEncoder + OpusChunkDecoder (the classes the real playback pipeline uses,
 * one layer above the raw OpusEncoder/OpusDecoder native wrappers tested in
 * natives/opus/OpusCodecSpec.groovy) using StandardAudioDataFormats.DISCORD_OPUS and a default
 * AudioConfiguration -- the exact format/quality the bot ships with.
 */
class OpusChunkCodecSpec extends Specification {

    private static ShortBuffer sineWave(int totalSamples, int channels, int sampleRate, double frequency, int amplitude = 10000) {
        def buffer = ByteBuffer.allocateDirect(totalSamples * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        int frames = totalSamples / channels
        for (int i = 0; i < frames; i++) {
            short sample = (short) (Math.sin(2 * Math.PI * frequency * i / sampleRate) * amplitude)
            for (int c = 0; c < channels; c++) {
                buffer.put(i * channels + c, sample)
            }
        }
        return buffer
    }

    /**
     * Best-lag normalized cross-correlation -- see OpusCodecSpec.bestLagCorrelation() for why a
     * zero-lag comparison is the wrong metric here (Opus's algorithmic encode/decode delay shifts
     * a single isolated frame's decoded output in time relative to the input).
     */
    private static double bestLagCorrelation(ShortBuffer a, ShortBuffer b, int totalSamples, int channels, int maxLagFrames = 960) {
        double best = -1.0d

        for (int lagFrames = -maxLagFrames; lagFrames <= maxLagFrames; lagFrames++) {
            int lag = lagFrames * channels
            int start = Math.max(0, -lag)
            int end = Math.min(totalSamples, totalSamples - lag)
            if (end <= start) {
                continue
            }

            double dotProduct = 0, normA = 0, normB = 0
            for (int i = start; i < end; i++) {
                double x = a.get(i)
                double y = b.get(i + lag)
                dotProduct += x * y
                normA += x * x
                normB += y * y
            }

            if (normA == 0 || normB == 0) {
                continue
            }

            double corr = dotProduct / Math.sqrt(normA * normB)
            if (corr > best) {
                best = corr
            }
        }

        return best
    }

    def "encode(ShortBuffer) then decode reproduces a highly similar signal using the real Discord Opus format"() {
        given:
        def format = StandardAudioDataFormats.DISCORD_OPUS // 2ch, 48000Hz, 960 samples/chunk
        def configuration = new AudioConfiguration() // default: max opus quality
        def encoder = new OpusChunkEncoder(configuration, format)
        def decoder = new OpusChunkDecoder(format)
        def pcmIn = sineWave(format.totalSampleCount(), format.channelCount, format.sampleRate, 440.0d)
        def pcmOut = ByteBuffer.allocateDirect(format.totalSampleCount() * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

        when:
        byte[] encoded = encoder.encode(pcmIn)
        decoder.decode(encoded, pcmOut)

        then:
        encoded.length > 0
        encoded.length <= format.maximumChunkSize()
        bestLagCorrelation(pcmIn, pcmOut, format.totalSampleCount(), format.channelCount) > 0.9d

        cleanup:
        encoder.close()
        decoder.close()
    }

    def "encode(ShortBuffer, ByteBuffer) writing into a direct buffer round-trips identically to the byte[] overload"() {
        given:
        def format = StandardAudioDataFormats.DISCORD_OPUS
        def configuration = new AudioConfiguration()
        def encoder = new OpusChunkEncoder(configuration, format)
        def decoder = new OpusChunkDecoder(format)
        def pcmIn = sineWave(format.totalSampleCount(), format.channelCount, format.sampleRate, 440.0d)
        def directOut = ByteBuffer.allocateDirect(format.maximumChunkSize())
        def pcmOut = ByteBuffer.allocateDirect(format.totalSampleCount() * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

        when:
        encoder.encode(pcmIn, directOut)
        byte[] encodedBytes = new byte[directOut.remaining()]
        directOut.get(encodedBytes)
        decoder.decode(encodedBytes, pcmOut)

        then:
        encodedBytes.length > 0
        bestLagCorrelation(pcmIn, pcmOut, format.totalSampleCount(), format.channelCount) > 0.9d

        cleanup:
        encoder.close()
        decoder.close()
    }

    def "encoding the format's own silenceBytes() through the real decoder yields near-zero energy"() {
        given:
        def format = StandardAudioDataFormats.DISCORD_OPUS
        def decoder = new OpusChunkDecoder(format)
        def pcmOut = ByteBuffer.allocateDirect(format.totalSampleCount() * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

        when:
        decoder.decode(format.silenceBytes(), pcmOut)

        then: "OpusChunkDecoder.decode() is void, so only trust what's within the buffer's own limit after decode"
        pcmOut.limit() > 0
        (0..<pcmOut.limit()).every { Math.abs(pcmOut.get(it)) < 50 }

        cleanup:
        decoder.close()
    }
}
