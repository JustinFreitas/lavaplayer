package com.sedmelluq.discord.lavaplayer.container.adts

import spock.lang.Specification

class AdtsStreamReaderSpec extends Specification {

    private static byte[] concat(byte[]... arrays) {
        def out = new ByteArrayOutputStream()
        arrays.each { out.write(it) }
        return out.toByteArray()
    }

    /** Packs a sequence of [value, bitWidth] pairs into a byte array, MSB-first, zero-padded to a byte boundary. */
    private static byte[] packBits(List<List<Integer>> fields) {
        StringBuilder bits = new StringBuilder()
        fields.each { f ->
            int value = f[0]
            int width = f[1]
            String binary = Integer.toBinaryString(value)
            bits.append(("0" * width + binary).with { it.substring(it.length() - width) })
        }
        while (bits.length() % 8 != 0) {
            bits.append('0')
        }
        byte[] result = new byte[bits.length() / 8]
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2)
        }
        return result
    }

    /**
     * Builds a 7-byte ADTS header (no CRC). sampleRateIndex 4 == 44100Hz, per AdtsStreamReader's mapping table.
     * rawProfile is stored as profile-1 in the bitstream (AdtsStreamReader adds 1 back when decoding).
     */
    private static byte[] adtsHeader(int rawProfile = 1, int sampleRateIndex = 4, int channels = 2,
                                      int payloadLength = 5, boolean protectionAbsent = true,
                                      int numRawDataBlocks = 0, int layer = 0, int syncword = 0xFFF) {
        int frameLength = payloadLength + 7 + (protectionAbsent ? 0 : 2)
        return packBits([
            [syncword, 12],
            [1, 1], // MPEG version id, ignored by the parser
            [layer, 2],
            [protectionAbsent ? 1 : 0, 1],
            [rawProfile, 2],
            [sampleRateIndex, 4],
            [0, 1], // private bit
            [channels, 3],
            [0, 4], // original/copy + home + copyright bits, ignored
            [frameLength, 13],
            [0, 11], // buffer fullness, ignored
            [numRawDataBlocks, 2],
        ])
    }

    private static AdtsStreamReader readerFor(byte[] bytes) {
        new AdtsStreamReader(new ByteArrayInputStream(bytes))
    }

    def "findPacketHeader parses a valid header at the start of the stream"() {
        given:
        def reader = readerFor(adtsHeader(1, 4, 2, 5))

        when:
        def header = reader.findPacketHeader()

        then:
        header != null
        header.profile == 2 // rawProfile(1) + 1
        header.sampleRate == 44100
        header.channels == 2
        header.payloadLength == 5
        header.isProtectionAbsent
    }

    def "findPacketHeader returns the same header on repeated calls until nextPacket is called"() {
        given:
        def reader = readerFor(adtsHeader())

        expect:
        reader.findPacketHeader().is(reader.findPacketHeader())
    }

    def "findPacketHeader scans past leading non-header bytes"() {
        given:
        def junk = [0x00, 0x11, 0x22, 0x33, 0x44] as byte[]
        def bytes = concat(junk, adtsHeader(1, 4, 2, 5))
        def reader = readerFor(bytes)

        when:
        def header = reader.findPacketHeader()

        then:
        header != null
        header.sampleRate == 44100
    }

    def "findPacketHeader accounts for the CRC bytes when protection is present"() {
        given:
        def reader = readerFor(concat(adtsHeader(1, 4, 2, 5, false), [0x00, 0x00] as byte[]))

        when:
        def header = reader.findPacketHeader()

        then:
        header != null
        !header.isProtectionAbsent
        header.payloadLength == 5
    }

    def "findPacketHeader returns null when the stream never contains a valid header"() {
        given:
        def reader = readerFor([0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77] as byte[])

        expect:
        reader.findPacketHeader() == null
    }

    def "findPacketHeader rejects a header with a non-zero layer field and continues scanning"() {
        given:
        def invalid = adtsHeader(1, 4, 2, 5, true, 0, 1) // layer=1, invalid
        def valid = adtsHeader(1, 4, 2, 5)
        def reader = readerFor(concat(invalid, valid))

        when:
        def header = reader.findPacketHeader()

        then:
        header != null
        header.sampleRate == 44100
    }

    def "findPacketHeader rejects a header with a non-zero raw-data-block count"() {
        given:
        def invalid = adtsHeader(1, 4, 2, 5, true, 1) // numRawDataBlocks=1, unsupported
        def reader = readerFor(invalid)

        expect:
        reader.findPacketHeader() == null
    }

    def "findPacketHeader rejects a header with an invalid sample rate index"() {
        given:
        def invalid = adtsHeader(1, 13, 2, 5) // index 13 maps to INVALID_VALUE
        def reader = readerFor(invalid)

        expect:
        reader.findPacketHeader() == null
    }

    def "findPacketHeader rejects a header with a zero channel count"() {
        given:
        def invalid = adtsHeader(1, 4, 0, 5)
        def reader = readerFor(invalid)

        expect:
        reader.findPacketHeader() == null
    }

    def "nextPacket clears the cached header so the next call re-scans"() {
        given:
        def bytes = concat(adtsHeader(1, 4, 2, 5), adtsHeader(2, 3, 1, 10))
        def reader = readerFor(bytes)
        def first = reader.findPacketHeader()

        when:
        reader.nextPacket()
        def second = reader.findPacketHeader()

        then:
        first.channels == 2
        second.channels == 1
        second.sampleRate == 48000 // index 3
    }
}
