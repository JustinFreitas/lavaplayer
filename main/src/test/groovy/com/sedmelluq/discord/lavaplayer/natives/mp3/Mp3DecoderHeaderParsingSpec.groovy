package com.sedmelluq.discord.lavaplayer.natives.mp3

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Mp3Decoder's frame-header helpers are pure bit-math over the 4-byte MPEG frame header -- no
 * native call involved -- so they're testable with hand-built header bytes, the same way the
 * ADTS header parsing was tested. Actual MP3 payload *decode* correctness would need a real
 * encoded fixture file (there's no MP3 encoder in this codebase to generate one), which is out of
 * scope here.
 */
class Mp3DecoderHeaderParsingSpec extends Specification {

    /**
     * Builds a 4-byte MPEG audio frame header.
     * @param versionBits 0=MPEG2.5, 2=MPEG2, 3=MPEG1 (1 is reserved/unsupported)
     * @param layerBits 1=Layer III (the only layer lavaplayer's isValidFrame() accepts)
     */
    private static byte[] header(int versionBits, int layerBits, int bitrateIndex, int sampleRateIndex, int padding = 0, int channelModeBits = 0) {
        int b1 = 0xFF
        int b2 = 0xE0 | (versionBits << 3) | (layerBits << 1) | 1 // protection bit set = "no CRC"
        int b3 = (bitrateIndex << 4) | (sampleRateIndex << 2) | (padding << 1)
        int b4 = (channelModeBits << 6)
        return [(byte) b1, (byte) b2, (byte) b3, (byte) b4] as byte[]
    }

    def "hasFrameSync recognizes a valid 11-bit sync word"() {
        expect:
        Mp3Decoder.hasFrameSync(header(3, 1, 9, 0), 0)
    }

    def "hasFrameSync rejects a buffer missing the sync word"() {
        expect:
        !Mp3Decoder.hasFrameSync([0x00, 0x00, 0x00, 0x00] as byte[], 0)
    }

    def "isUnsupportedVersion flags the reserved version bits"() {
        expect:
        Mp3Decoder.isUnsupportedVersion(header(1, 1, 9, 0), 0)
        !Mp3Decoder.isUnsupportedVersion(header(3, 1, 9, 0), 0)
    }

    def "isValidFrame requires Layer III, a defined bitrate, and a valid sample rate"() {
        expect:
        Mp3Decoder.isValidFrame(header(3, 1, 9, 0), 0)

        and: "not Layer III"
        !Mp3Decoder.isValidFrame(header(3, 2, 9, 0), 0)

        and: "free bitrate (index 0) is rejected"
        !Mp3Decoder.isValidFrame(header(3, 1, 0, 0), 0)

        and: "bitrate index 15 ('bad') is rejected"
        !Mp3Decoder.isValidFrame(header(3, 1, 15, 0), 0)

        and: "sample rate index 3 (reserved) is rejected"
        !Mp3Decoder.isValidFrame(header(3, 1, 9, 3), 0)
    }

    def "getFrameChannelCount reads mono for the single-channel mode and stereo otherwise"() {
        expect:
        Mp3Decoder.getFrameChannelCount(header(3, 1, 9, 0, 0, 3), 0) == 1 // mode 3 = mono
        Mp3Decoder.getFrameChannelCount(header(3, 1, 9, 0, 0, 0), 0) == 2 // mode 0 = stereo
    }

    @Unroll
    def "getFrameSampleRate reads #expected Hz for MPEG1 sample rate index #index"() {
        expect:
        Mp3Decoder.getFrameSampleRate(header(3, 1, 9, index), 0) == expected

        where:
        index | expected
        0     | 44100
        1     | 48000
        2     | 32000
    }

    def "getFrameSampleRate applies the MPEG2 sample rate halving"() {
        expect:
        Mp3Decoder.getFrameSampleRate(header(2, 1, 9, 0), 0) == 22050 // MPEG2, same index as 44100 in MPEG1
    }

    def "getFrameSampleRate applies the MPEG2.5 quarter-rate multiplier"() {
        expect:
        Mp3Decoder.getFrameSampleRate(header(0, 1, 9, 0), 0) == 11025 // MPEG2.5
    }

    def "getSamplesPerFrame differs between MPEG1 and MPEG2"() {
        expect:
        Mp3Decoder.getSamplesPerFrame(header(3, 1, 9, 0), 0) == Mp3Decoder.MPEG1_SAMPLES_PER_FRAME
        Mp3Decoder.getSamplesPerFrame(header(2, 1, 9, 0), 0) == Mp3Decoder.MPEG2_SAMPLES_PER_FRAME
    }

    def "getFrameSize computes the standard 44.1kHz/128kbps Layer III frame size with no padding"() {
        given:
        // MPEG1, Layer III, bitrate index 9 (128kbps), sample rate index 0 (44100Hz), no padding
        def bytes = header(3, 1, 9, 0, 0)

        expect: "144 * 128000 / 44100 = 417 (floor)"
        Mp3Decoder.getFrameSize(bytes, 0) == 417
    }

    def "getFrameSize adds one byte when the padding bit is set"() {
        given:
        def padded = header(3, 1, 9, 0, 1)

        expect:
        Mp3Decoder.getFrameSize(padded, 0) == 418
    }
}
