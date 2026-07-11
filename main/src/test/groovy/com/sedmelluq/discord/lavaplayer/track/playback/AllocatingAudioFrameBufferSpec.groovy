package com.sedmelluq.discord.lavaplayer.track.playback

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import spock.lang.Specification
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeoutException
import java.nio.ByteBuffer

class AllocatingAudioFrameBufferSpec extends Specification {
    def format = StandardAudioDataFormats.DISCORD_OPUS
    def stopping = new AtomicBoolean(false)

    def "buffer calculates correct capacities"() {
        given:
        def buffer = new AllocatingAudioFrameBuffer(100, format, stopping) // 100ms / 20 + 1 = 6 capacity

        expect:
        buffer.getFullCapacity() == 6
        buffer.getRemainingCapacity() == 6
    }

    def "consume adds frame to buffer and provide retrieves it"() {
        given:
        def buffer = new AllocatingAudioFrameBuffer(100, format, stopping)
        def data = [1, 2, 3] as byte[]
        def frame = new ImmutableAudioFrame(1000L, data, 100, format)

        when:
        buffer.consume(frame)

        then:
        buffer.getRemainingCapacity() == 5
        buffer.getLastInputTimecode() == 1000L

        when:
        def provided = buffer.provide()

        then:
        provided != null
        provided.timecode == 1000L
        provided.data == data
        provided.volume == 100
        buffer.getRemainingCapacity() == 6
    }

    def "provide with mutable target frame copies data correctly"() {
        given:
        def buffer = new AllocatingAudioFrameBuffer(100, format, stopping)
        def data = [1, 2, 3] as byte[]
        def frame = new ImmutableAudioFrame(1000L, data, 100, format)
        def target = new MutableAudioFrame(ByteBuffer.allocate(10))

        when:
        buffer.consume(frame)
        boolean success = buffer.provide(target)

        then:
        success == true
        target.timecode == 1000L
        target.volume == 100
        target.getData()[0..2] == [1, 2, 3] as byte[]
        target.getDataLength() == 3
        !target.isTerminator()
    }

    def "replaces frame with silence when volume is zero"() {
        given:
        def buffer = new AllocatingAudioFrameBuffer(100, format, stopping)
        def data = [1, 2, 3] as byte[]
        def frame = new ImmutableAudioFrame(1000L, data, 0, format)

        when:
        buffer.consume(frame)
        def provided = buffer.provide()

        then:
        provided != null
        provided.volume == 0
        provided.data == format.silenceBytes()
    }

    def "clearOnInsert clears buffer when new frame is consumed"() {
        given:
        def buffer = new AllocatingAudioFrameBuffer(100, format, stopping)
        def frame1 = new ImmutableAudioFrame(1000L, [1] as byte[], 100, format)
        def frame2 = new ImmutableAudioFrame(2000L, [2] as byte[], 100, format)

        when:
        buffer.consume(frame1)
        buffer.setClearOnInsert()
        buffer.consume(frame2)
        def provided = buffer.provide()

        then:
        provided.timecode == 2000L // Frame 1 was cleared when Frame 2 was inserted
        buffer.provide() == null
    }

    def "provide returns terminator when terminateOnEmpty is set"() {
        given:
        def buffer = new AllocatingAudioFrameBuffer(100, format, stopping)

        when:
        buffer.setTerminateOnEmpty()
        def provided = buffer.provide()

        then:
        provided != null
        provided.isTerminator()
    }

    def "rebuild transforms frames in buffer"() {
        given:
        def buffer = new AllocatingAudioFrameBuffer(100, format, stopping)
        def frame = new ImmutableAudioFrame(1000L, [1] as byte[], 100, format)
        def rebuilder = Mock(AudioFrameRebuilder)

        when:
        buffer.consume(frame)
        buffer.rebuild(rebuilder)
        buffer.provide()

        then:
        1 * rebuilder.rebuild(frame) >> new ImmutableAudioFrame(1000L, [9] as byte[], 50, format)
    }

    def "consume throws InterruptedException when stopping is set"() {
        given:
        stopping.set(true)
        def buffer = new AllocatingAudioFrameBuffer(100, format, stopping)
        def frame = new ImmutableAudioFrame(1000L, [1] as byte[], 100, format)

        when:
        buffer.consume(frame)

        then:
        thrown(InterruptedException)
    }

    def "provide with timeout respects timeline"() {
        given:
        def buffer = new AllocatingAudioFrameBuffer(100, format, stopping)
        def frame = new ImmutableAudioFrame(1000L, [1] as byte[], 100, format)

        when:
        buffer.consume(frame)
        def provided = buffer.provide(10, TimeUnit.MILLISECONDS)

        then:
        provided != null
        provided.timecode == 1000L
    }
}
