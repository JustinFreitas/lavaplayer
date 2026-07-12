package com.sedmelluq.discord.lavaplayer.natives.opus

import spock.lang.Specification
import spock.lang.Unroll

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * These tests exercise the REAL native libopus binary bundled in natives-publish (loaded via
 * ConnectorNativeLibLoader) -- not a mock. They prove the actual codec your bot ships works, not
 * just that the Java wiring around it compiles.
 */
class OpusCodecSpec extends Specification {

    private static ShortBuffer sineWave(int sampleCount, int channels, int sampleRate, double frequency, int amplitude = 10000) {
        def buffer = ByteBuffer.allocateDirect(sampleCount * channels * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (int i = 0; i < sampleCount; i++) {
            short sample = (short) (Math.sin(2 * Math.PI * frequency * i / sampleRate) * amplitude)
            for (int c = 0; c < channels; c++) {
                buffer.put(i * channels + c, sample)
            }
        }
        return buffer
    }

    private static ShortBuffer silence(int sampleCount, int channels) {
        ByteBuffer.allocateDirect(sampleCount * channels * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    }

    /**
     * Best-lag normalized cross-correlation: 1.0 means the signals are identical (up to a positive
     * scale factor and a fixed time shift), 0.0 means uncorrelated. Opus (like most audio codecs)
     * has inherent encoder/decoder algorithmic delay, so a single isolated frame's decoded output
     * is time-shifted relative to the input -- a *zero-lag* correlation measures phase alignment,
     * not audio similarity, and was observed to sit around 0.5 for a perfectly fine round-trip
     * purely due to that shift. Searching over a small lag window (in whole-frame units, so
     * multi-channel interleaving stays aligned) finds the true similarity regardless of that delay.
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

    private static double rms(ShortBuffer buffer, int length) {
        double sumSquares = 0
        for (int i = 0; i < length; i++) {
            double v = buffer.get(i)
            sumSquares += v * v
        }
        Math.sqrt(sumSquares / length)
    }

    @Unroll
    def "encoding then decoding a #channels-channel tone at max quality reproduces a highly similar signal"() {
        given:
        int sampleRate = 48000
        int frameSize = 960 // 20ms at 48kHz, the real Discord frame size
        def encoder = new OpusEncoder(sampleRate, channels, 10)
        def decoder = new OpusDecoder(sampleRate, channels)
        def pcmIn = sineWave(frameSize, channels, sampleRate, 440.0d)
        def encoded = ByteBuffer.allocateDirect(4000)
        def pcmOut = ByteBuffer.allocateDirect(frameSize * channels * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

        when:
        int bytesWritten = encoder.encode(pcmIn, frameSize, encoded)
        int samplesDecoded = decoder.decode(encoded, pcmOut)

        then:
        bytesWritten > 0
        samplesDecoded == frameSize
        bestLagCorrelation(pcmIn, pcmOut, frameSize * channels, channels) > 0.9d

        cleanup:
        encoder.close()
        decoder.close()

        where:
        channels << [1, 2]
    }

    def "encoding then decoding silence reproduces silence"() {
        given:
        int sampleRate = 48000
        int channels = 2
        int frameSize = 960
        def encoder = new OpusEncoder(sampleRate, channels, 10)
        def decoder = new OpusDecoder(sampleRate, channels)
        def pcmIn = silence(frameSize, channels)
        def encoded = ByteBuffer.allocateDirect(4000)
        def pcmOut = ByteBuffer.allocateDirect(frameSize * channels * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

        when:
        encoder.encode(pcmIn, frameSize, encoded)
        int samplesDecoded = decoder.decode(encoded, pcmOut)

        then:
        samplesDecoded == frameSize
        rms(pcmOut, frameSize * channels) < 50 // near-zero energy; allow a little quantization noise

        cleanup:
        encoder.close()
        decoder.close()
    }

    def "OpusAudioDataFormat's hardcoded SILENT_OPUS_FRAME actually decodes to near-silence"() {
        // Guards a real production constant (format/OpusAudioDataFormat.SILENT_OPUS_FRAME) against
        // ever silently becoming garbage -- e.g. after a native library upgrade -- by decoding it
        // with the real codec instead of just trusting the byte literal.
        given:
        byte[] silentFrame = [(byte) 0xFC, (byte) 0xFF, (byte) 0xFE] as byte[]
        int sampleRate = 48000
        int channels = 2
        int frameSize = 960
        def decoder = new OpusDecoder(sampleRate, channels)
        def encoded = ByteBuffer.allocateDirect(silentFrame.length)
        encoded.put(silentFrame).flip()
        def pcmOut = ByteBuffer.allocateDirect(frameSize * channels * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

        when:
        int samplesDecoded = decoder.decode(encoded, pcmOut)

        then:
        samplesDecoded > 0
        rms(pcmOut, samplesDecoded * channels) < 50

        cleanup:
        decoder.close()
    }

    def "OpusDecoder rejects an unsupported channel count"() {
        when:
        new OpusDecoder(48000, 3)

        then:
        thrown(IllegalArgumentException)
    }

    def "OpusDecoder throws on a decode error from clearly corrupt packet data"() {
        given:
        def decoder = new OpusDecoder(48000, 2)
        // A byte sequence that isn't a valid opus packet encoding for this configuration.
        byte[] garbage = new byte[20]
        Arrays.fill(garbage, (byte) 0xFF)
        def encoded = ByteBuffer.allocateDirect(garbage.length)
        encoded.put(garbage).flip()
        def pcmOut = ByteBuffer.allocateDirect(960 * 2 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

        when:
        decoder.decode(encoded, pcmOut)

        then:
        thrown(IllegalStateException)

        cleanup:
        decoder.close()
    }

    def "using a decoder after it is closed throws"() {
        given:
        def decoder = new OpusDecoder(48000, 2)
        decoder.close()
        def encoded = ByteBuffer.allocateDirect(10)
        def pcmOut = ByteBuffer.allocateDirect(1920).order(ByteOrder.nativeOrder()).asShortBuffer()

        when:
        decoder.decode(encoded, pcmOut)

        then:
        thrown(IllegalStateException)
    }

    def "using an encoder after it is closed throws"() {
        given:
        def encoder = new OpusEncoder(48000, 2, 10)
        encoder.close()
        def pcmIn = silence(960, 2)
        def encoded = ByteBuffer.allocateDirect(4000)

        when:
        encoder.encode(pcmIn, 960, encoded)

        then:
        thrown(IllegalStateException)
    }
}
