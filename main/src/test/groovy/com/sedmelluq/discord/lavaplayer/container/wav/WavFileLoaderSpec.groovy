package com.sedmelluq.discord.lavaplayer.container.wav

import com.sedmelluq.discord.lavaplayer.tools.io.ByteArraySeekableInputStream
import spock.lang.Specification

class WavFileLoaderSpec extends Specification {

    private static final byte[] RIFF = "RIFF".getBytes("US-ASCII")
    private static final byte[] WAVE = "WAVE".getBytes("US-ASCII")
    private static final byte[] FMT_CHUNK_ID = "fmt ".getBytes("US-ASCII")
    private static final byte[] DATA_CHUNK_ID = "data".getBytes("US-ASCII")
    private static final byte[] FORMAT_SUBTYPE_PCM = [
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        (byte) 0x80, 0x00, 0x00, (byte) 0xaa, 0x00, 0x38, (byte) 0x9b, 0x71
    ] as byte[]

    private static byte[] le16(int v) {
        [(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF)] as byte[]
    }

    private static byte[] le32(int v) {
        [(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF), (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)] as byte[]
    }

    /** Builds a standard 16-byte PCM "fmt " chunk body. */
    private static byte[] pcmFormatBody(int audioFormat = 1, int channels = 2, int sampleRate = 44100,
                                         int blockAlign = 4, int bitsPerSample = 16) {
        def out = new ByteArrayOutputStream()
        out.write(le16(audioFormat))
        out.write(le16(channels))
        out.write(le32(sampleRate))
        out.write(le32(sampleRate * blockAlign)) // byte rate, unused by the parser
        out.write(le16(blockAlign))
        out.write(le16(bitsPerSample))
        return out.toByteArray()
    }

    private static byte[] chunk(byte[] id, byte[] body) {
        def out = new ByteArrayOutputStream()
        out.write(id)
        out.write(le32(body.length))
        out.write(body)
        return out.toByteArray()
    }

    /** Assembles RIFF/WAVE + "fmt " + "data" chunks (plus any extra chunks) into a full fixture. */
    private static byte[] wavFile(byte[] fmtBody, long dataSize = 400L, List<byte[]> extraChunksBeforeFmt = []) {
        def out = new ByteArrayOutputStream()
        out.write(RIFF)
        out.write(le32(0)) // overall RIFF size, ignored by the parser (wildcard-matched)
        out.write(WAVE)
        extraChunksBeforeFmt.each { out.write(it) }
        out.write(chunk(FMT_CHUNK_ID, fmtBody))
        out.write(DATA_CHUNK_ID)
        out.write(le32((int) dataSize))
        return out.toByteArray()
    }

    private static WavFileLoader loaderFor(byte[] bytes) {
        new WavFileLoader(new ByteArraySeekableInputStream(bytes))
    }

    // --- happy path ---

    def "parseHeaders reads a valid PCM header"() {
        given:
        def bytes = wavFile(pcmFormatBody(1, 2, 44100, 4, 16), 400L)

        when:
        def info = loaderFor(bytes).parseHeaders()

        then:
        info.channelCount == 2
        info.sampleRate == 44100
        info.bitsPerSample == 16
        info.blockAlign == 4
        info.blockCount == 100L // 400 / 4
        info.startOffset == bytes.length
    }

    def "parseHeaders throws when the RIFF/WAVE header is missing"() {
        given:
        def bytes = "NOT A WAV FILE AT ALL......".getBytes("US-ASCII")

        when:
        loaderFor(bytes).parseHeaders()

        then:
        thrown(IllegalStateException)
    }

    def "parseHeaders skips unknown chunks before the fmt chunk"() {
        given:
        def junkChunk = chunk("JUNK".getBytes("US-ASCII"), new byte[10])
        def bytes = wavFile(pcmFormatBody(), 400L, [junkChunk])

        when:
        def info = loaderFor(bytes).parseHeaders()

        then:
        info.channelCount == 2
        info.sampleRate == 44100
    }

    def "parseHeaders reads the WAVE_FORMAT_EXTENSIBLE format with a valid PCM subformat"() {
        given:
        def body = new ByteArrayOutputStream()
        body.write(pcmFormatBody(0xFFFE, 2, 48000, 4, 16))
        body.write(new byte[8]) // extension size + valid bits per sample + channel mask, unused by the parser
        body.write(FORMAT_SUBTYPE_PCM)
        def bytes = wavFile(body.toByteArray(), 400L)

        when:
        def info = loaderFor(bytes).parseHeaders()

        then:
        info.channelCount == 2
        info.sampleRate == 48000
    }

    // --- validateFormat rejections ---

    def "parseHeaders rejects an unrecognized audio format code"() {
        given:
        def bytes = wavFile(pcmFormatBody(3, 2, 44100, 4, 16))

        when:
        loaderFor(bytes).parseHeaders()

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Invalid audio format")
    }

    def "parseHeaders rejects a WAVE_FORMAT_EXTENSIBLE subformat that is not PCM"() {
        given:
        def body = new ByteArrayOutputStream()
        body.write(pcmFormatBody(0xFFFE, 2, 48000, 4, 16))
        body.write(new byte[8])
        body.write(new byte[16]) // all-zero subformat, does not match FORMAT_SUBTYPE_PCM
        def bytes = wavFile(body.toByteArray())

        when:
        loaderFor(bytes).parseHeaders()

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Invalid subformat")
    }

    def "parseHeaders rejects a channel count of zero"() {
        given:
        def bytes = wavFile(pcmFormatBody(1, 0, 44100, 2, 16))

        when:
        loaderFor(bytes).parseHeaders()

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Invalid channel count")
    }

    def "parseHeaders rejects a channel count above 16"() {
        given:
        def bytes = wavFile(pcmFormatBody(1, 17, 44100, 34, 16))

        when:
        loaderFor(bytes).parseHeaders()

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Invalid channel count")
    }

    def "parseHeaders rejects a sample rate below 100"() {
        given:
        def bytes = wavFile(pcmFormatBody(1, 2, 50, 4, 16))

        when:
        loaderFor(bytes).parseHeaders()

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Invalid sample rate")
    }

    def "parseHeaders rejects a sample rate above 384000"() {
        given:
        def bytes = wavFile(pcmFormatBody(1, 2, 400000, 4, 16))

        when:
        loaderFor(bytes).parseHeaders()

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Invalid sample rate")
    }

    def "parseHeaders rejects an unsupported bits-per-sample value"() {
        given:
        def bytes = wavFile(pcmFormatBody(1, 2, 44100, 2, 8))

        when:
        loaderFor(bytes).parseHeaders()

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Unsupported bits per sample")
    }

    // --- validateAlignment rejections ---

    def "parseHeaders rejects a block align smaller than the minimum"() {
        given:
        // minimum for 2 channels * 16 bits = 4; 2 is too small
        def bytes = wavFile(pcmFormatBody(1, 2, 44100, 2, 16))

        when:
        loaderFor(bytes).parseHeaders()

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Block align is not valid")
    }

    def "parseHeaders rejects a block align larger than the minimum plus 32"() {
        given:
        def bytes = wavFile(pcmFormatBody(1, 2, 44100, 4 + 34, 16))

        when:
        loaderFor(bytes).parseHeaders()

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Block align is not valid")
    }

    def "parseHeaders rejects a block align that is not a multiple of the sample byte width"() {
        given:
        // minimum is 4 (2ch * 2 bytes); 5 is within +32 of the minimum but not a multiple of 2
        def bytes = wavFile(pcmFormatBody(1, 2, 44100, 5, 16))

        when:
        loaderFor(bytes).parseHeaders()

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("not a multiple of bits per sample")
    }
}
