package com.sedmelluq.discord.lavaplayer.tools.io

import spock.lang.Specification

import java.nio.ByteBuffer

class BitStreamIoSpec extends Specification {

    // --- BitStreamReader ---

    def "asLong reads bits within a single byte"() {
        given:
        // 0b10110100
        def reader = new BitStreamReader(new ByteArrayInputStream([(byte) 0xB4] as byte[]))

        expect:
        reader.asLong(4) == 0b1011L
        reader.asLong(4) == 0b0100L
    }

    def "asLong reads bits spanning a byte boundary"() {
        given:
        // 0b10110100 0b11001010 -- read 12 bits: 1011 0100 1100 -> 0xB4C
        def reader = new BitStreamReader(new ByteArrayInputStream([(byte) 0xB4, (byte) 0xCA] as byte[]))

        expect:
        reader.asLong(12) == 0xB4CL
        reader.asLong(4) == 0xAL
    }

    def "asInteger returns the same value as asLong for small widths"() {
        given:
        def reader = new BitStreamReader(new ByteArrayInputStream([(byte) 0xB4] as byte[]))

        expect:
        reader.asInteger(8) == 0xB4
    }

    def "asSignedLong interprets a set high bit as negative"() {
        given:
        // 4 bits: 1000 -> unsigned 8, signed -8
        def reader = new BitStreamReader(new ByteArrayInputStream([(byte) 0x80] as byte[]))

        expect:
        reader.asSignedLong(4) == -8L
    }

    def "asSignedLong interprets a clear high bit as positive"() {
        given:
        // 4 bits: 0011 -> 3, positive either way
        def reader = new BitStreamReader(new ByteArrayInputStream([(byte) 0x30] as byte[]))

        expect:
        reader.asSignedLong(4) == 3L
    }

    def "asSignedInteger matches asSignedLong for small widths"() {
        given:
        def reader = new BitStreamReader(new ByteArrayInputStream([(byte) 0x80] as byte[]))

        expect:
        reader.asSignedInteger(4) == -8
    }

    def "readAllZeroes counts leading zero bits and stops at the first set bit"() {
        given:
        // 0b00000000 0b00100000 -- 10 leading zero bits, then a 1, then more bits
        def reader = new BitStreamReader(new ByteArrayInputStream([(byte) 0x00, (byte) 0x20] as byte[]))

        when:
        def zeroCount = reader.readAllZeroes()

        then:
        zeroCount == 10

        and: "the reader is positioned right after the set bit for subsequent reads"
        reader.asLong(5) == 0 // remaining 5 bits of the second byte (00100000 -> after the '1', "00000")
    }

    def "readRemainingBits consumes the rest of the current byte and resets alignment"() {
        given:
        // byte1 = 0b10110100, byte2 = 0b11111111
        def reader = new BitStreamReader(new ByteArrayInputStream([(byte) 0xB4, (byte) 0xFF] as byte[]))
        reader.asLong(3) // consumes "101", 5 bits left in byte1: "10100"

        when:
        def remaining = reader.readRemainingBits()

        then:
        remaining == 0b10100

        and: "the next read starts fresh on the second byte"
        reader.asLong(8) == 0xFF
    }

    def "asLong throws an EOFException when the stream runs out of bytes"() {
        given:
        def reader = new BitStreamReader(new ByteArrayInputStream(new byte[0]))

        when:
        reader.asLong(1)

        then:
        thrown(EOFException)
    }

    // --- BitBufferReader ---

    def "BitBufferReader reads bits from a ByteBuffer without throwing checked exceptions"() {
        given:
        def reader = new BitBufferReader(ByteBuffer.wrap([(byte) 0xB4, (byte) 0xCA] as byte[]))

        expect:
        reader.asLong(12) == 0xB4CL
        reader.asInteger(4) == 0xA
    }

    def "BitBufferReader propagates a buffer underflow when reading past the end"() {
        given:
        def reader = new BitBufferReader(ByteBuffer.wrap([(byte) 0xFF] as byte[]))
        reader.asLong(8)

        when:
        reader.asLong(1)

        then:
        thrown(java.nio.BufferUnderflowException)
    }

    // --- BitStreamWriter ---

    def "write packs multiple fields into a single byte MSB-first"() {
        given:
        def out = new ByteArrayOutputStream()
        def writer = new BitStreamWriter(out)

        when:
        writer.write(0b1011, 4)
        writer.write(0b0100, 4)
        writer.flush()

        then:
        out.toByteArray() == [(byte) 0xB4] as byte[]
    }

    def "a byte that becomes exactly full is not emitted until the next write or an explicit flush"() {
        // sendOnFullByte() checks bitsUnused before it is decremented for the current chunk, so a
        // byte completed by one write() call isn't actually flushed to the stream until the START
        // of the next write() call (or an explicit flush()) -- there's a one-call lag.
        given:
        def out = new ByteArrayOutputStream()
        def writer = new BitStreamWriter(out)

        when:
        writer.write(0b1011, 4)
        writer.write(0b0100, 4) // completes a byte internally, but does not emit it yet

        then:
        out.toByteArray() == [] as byte[]

        when:
        writer.write(0, 1) // any further write flushes the previously-completed byte first

        then:
        out.toByteArray().length == 1
        out.toByteArray()[0] == (byte) 0xB4
    }

    def "write spans multiple bytes and pads the final partial byte with flush"() {
        given:
        def out = new ByteArrayOutputStream()
        def writer = new BitStreamWriter(out)

        when:
        writer.write(0xB4C, 12) // 1011 0100 1100
        writer.flush()

        then: "the trailing 4 bits of the second byte are zero-padded"
        out.toByteArray() == [(byte) 0xB4, (byte) 0xC0] as byte[]
    }

    def "flush does not emit an extra byte when already byte-aligned"() {
        given:
        def out = new ByteArrayOutputStream()
        def writer = new BitStreamWriter(out)

        when:
        writer.write(0xB4, 8)
        writer.flush()

        then:
        out.toByteArray() == [(byte) 0xB4] as byte[]
    }

    // --- round trip ---

    def "values written by BitStreamWriter are read back identically by BitStreamReader"() {
        given:
        def out = new ByteArrayOutputStream()
        def writer = new BitStreamWriter(out)
        writer.write(0xFFF, 12)  // syncword-like field
        writer.write(2, 3)
        writer.write(0, 1)
        writer.write(500, 13)
        writer.flush()

        when:
        def reader = new BitStreamReader(new ByteArrayInputStream(out.toByteArray()))

        then:
        reader.asLong(12) == 0xFFFL
        reader.asLong(3) == 2L
        reader.asLong(1) == 0L
        reader.asLong(13) == 500L
    }
}
