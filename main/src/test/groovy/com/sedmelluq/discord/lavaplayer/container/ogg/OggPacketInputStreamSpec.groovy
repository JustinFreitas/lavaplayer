package com.sedmelluq.discord.lavaplayer.container.ogg

import com.sedmelluq.discord.lavaplayer.tools.io.ByteArraySeekableInputStream
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.ByteBuffer
import java.nio.ByteOrder

class OggPacketInputStreamSpec extends Specification {

    private static byte[] buildOggPage(int flags, long granulePosition, int serial, int pageSeq, byte[] payload) {
        int segmentCount = payload.length == 0 ? 0 : 1
        ByteBuffer buf = ByteBuffer.allocate(27 + segmentCount + payload.length).order(ByteOrder.LITTLE_ENDIAN)
        buf.put((byte) 0x4F).put((byte) 0x67).put((byte) 0x67).put((byte) 0x53) // OggS
        buf.put((byte) 0) // version
        buf.put((byte) flags)
        buf.putLong(granulePosition)
        buf.putInt(serial)
        buf.putInt(pageSeq)
        buf.putInt(0) // checksum
        buf.put((byte) segmentCount)
        if (segmentCount > 0) {
            buf.put((byte) payload.length)
            buf.put(payload)
        }
        return buf.array()
    }

    def "seek throws IllegalStateException when seekPoints have not been set"() {
        given:
        def stream = new OggPacketInputStream(new ByteArraySeekableInputStream(new byte[16]), false)

        when:
        stream.seek(0)

        then:
        thrown(IllegalStateException)
    }

    def "seek returns 0 safely on empty seek table without IndexOutOfBoundsException"() {
        given:
        def input = new ByteArraySeekableInputStream(new byte[32])
        def stream = new OggPacketInputStream(input, false)
        stream.setSeekPoints([])

        expect:
        stream.seek(0) == 0L
        stream.seek(1000) == 0L
    }

    def "seek selects single page safely on 1-page seek table"() {
        given:
        def input = new ByteArraySeekableInputStream(new byte[256])
        def stream = new OggPacketInputStream(input, false)
        stream.setSeekPoints([new OggSeekPoint(128L, 48000L, 1000L, 1L)])

        when:
        long tcAtZero = stream.seek(0)

        then:
        tcAtZero == 1000L
        input.getPosition() == 128L

        when:
        long tcAtLater = stream.seek(5000)

        then:
        tcAtLater == 1000L
        input.getPosition() == 128L
    }

    @Unroll
    def "seek binary search selects floor seek point for target #targetMs without skipping page 0"() {
        given:
        def input = new ByteArraySeekableInputStream(new byte[1024])
        def stream = new OggPacketInputStream(input, false)
        stream.setSeekPoints([
            new OggSeekPoint(100L, 0L, 0L, 1L),
            new OggSeekPoint(200L, 48000L, 1000L, 2L),
            new OggSeekPoint(300L, 96000L, 2000L, 3L),
            new OggSeekPoint(400L, 144000L, 3000L, 4L)
        ])

        when:
        long actual = stream.seek(targetMs)

        then:
        actual == expectedTimecode
        input.getPosition() == expectedPosition

        where:
        targetMs | expectedTimecode | expectedPosition
        0L       | 0L               | 100L
        500L     | 0L               | 100L
        1000L    | 1000L            | 200L
        1500L    | 1000L            | 200L
        2000L    | 2000L            | 300L
        2999L    | 2000L            | 300L
        3000L    | 3000L            | 400L
        9000L    | 3000L            | 400L
    }

    def "OggPageScanner createSeekTable excludes header and continuation pages and indexes audio pages"() {
        given:
        int serial = 0x12345678
        byte[] headerPage = buildOggPage(OggPageHeader.FLAG_FIRST_PAGE, 0L, serial, 0, [1, 2, 3, 4] as byte[])
        byte[] commentPage = buildOggPage(0, 0L, serial, 1, [5, 6, 7, 8] as byte[])
        byte[] contPage = buildOggPage(OggPageHeader.FLAG_CONTINUATION, 24000L, serial, 2, [9, 10] as byte[])
        byte[] audioPage1 = buildOggPage(0, 48000L, serial, 3, [11, 12, 13] as byte[])
        byte[] audioPage2 = buildOggPage(OggPageHeader.FLAG_LAST_PAGE, 96000L, serial, 4, [14, 15, 16] as byte[])

        def out = new ByteArrayOutputStream()
        out.write(headerPage)
        out.write(commentPage)
        out.write(contPage)
        int expectedAudio1Offset = out.size()
        out.write(audioPage1)
        int expectedAudio2Offset = out.size()
        out.write(audioPage2)
        byte[] allBytes = out.toByteArray()

        when:
        def seekPoints = new OggPageScanner(0L, allBytes, allBytes.length).createSeekTable(48000, serial)

        then:
        seekPoints.size() == 2
        seekPoints[0].position == expectedAudio1Offset
        seekPoints[0].timecode == 1000L
        seekPoints[1].position == expectedAudio2Offset
        seekPoints[1].timecode == 2000L
    }
}
