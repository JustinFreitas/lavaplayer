package com.sedmelluq.discord.lavaplayer.container.ogg

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusEncoder
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import com.sedmelluq.discord.lavaplayer.tools.io.ByteArraySeekableInputStream
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.TrackStateListener
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class OggAudioTrackSpec extends Specification {

    private static byte[] buildOggPage(int flags, long granulePosition, int serial, int pageSeq, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(27 + 1 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
        buf.put((byte) 0x4F).put((byte) 0x67).put((byte) 0x67).put((byte) 0x53)
        buf.put((byte) 0)
        buf.put((byte) flags)
        buf.putLong(granulePosition)
        buf.putInt(serial)
        buf.putInt(pageSeq)
        buf.putInt(0)
        buf.put((byte) 1)
        buf.put((byte) payload.length)
        buf.put(payload)
        return buf.array()
    }

    private static byte[] buildOpusHead(int headerGainQ78) {
        ByteBuffer buf = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("OpusHead".getBytes(StandardCharsets.US_ASCII))
        buf.put((byte) 1) // version
        buf.put((byte) 2) // channels
        buf.putShort((short) 0) // pre-skip
        buf.putInt(48000) // input sample rate
        buf.putShort((short) headerGainQ78) // output gain
        buf.put((byte) 0) // channel mapping family
        return buf.array()
    }

    private static byte[] buildOpusTags() {
        byte[] vendor = "test".getBytes(StandardCharsets.UTF_8)
        ByteBuffer buf = ByteBuffer.allocate(8 + 4 + vendor.length + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("OpusTags".getBytes(StandardCharsets.US_ASCII))
        buf.putInt(vendor.length)
        buf.put(vendor)
        buf.putInt(0) // 0 user comments
        return buf.array()
    }

    private static byte[] encodeSilentOpusFrame() {
        def encoder = new OpusEncoder(48000, 2, 1)
        try {
            ShortBuffer pcm = ByteBuffer.allocateDirect(960 * 2 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            ByteBuffer encoded = ByteBuffer.allocateDirect(512)
            encoder.encode(pcm, 960, encoded)
            byte[] packet = new byte[encoded.remaining()]
            encoded.get(packet)
            return packet
        } finally {
            encoder.close()
        }
    }

    private static byte[] buildOggOpusSubStream(int serial, int headerGainQ78, byte[] opusPacket) {
        def out = new ByteArrayOutputStream()
        out.write(buildOggPage(OggPageHeader.FLAG_FIRST_PAGE, 0L, serial, 0, buildOpusHead(headerGainQ78)))
        out.write(buildOggPage(0, 0L, serial, 1, buildOpusTags()))
        out.write(buildOggPage(OggPageHeader.FLAG_LAST_PAGE, 960L, serial, 2, opusPacket))
        return out.toByteArray()
    }

    def "chained Ogg sub-stream without ReplayGain resets replayGainApplied and replayGainDb"() {
        given:
        byte[] opusFrame = encodeSilentOpusFrame()
        // First stream has -2.0 dB header gain (-512 in Q7.8 -> +3.0 dB with +5 dB R128 offset); second has 0 gain
        byte[] stream1 = buildOggOpusSubStream(101, -512, opusFrame)
        byte[] stream2 = buildOggOpusSubStream(102, 0, opusFrame)

        def combined = new ByteArrayOutputStream()
        combined.write(stream1)
        combined.write(stream2)

        def trackInfo = new AudioTrackInfo("Chained", "Artist", 40L, "chained.ogg", false, "chained.ogg")
        def track = new OggAudioTrack(trackInfo, new ByteArraySeekableInputStream(combined.toByteArray()))

        def config = new AudioConfiguration()
        config.setReplayGainEnabled(true)
        config.setOutputFormat(StandardAudioDataFormats.DISCORD_OPUS)

        def reportedInitialGain = new AtomicReference<Float>()
        def listener = Stub(TrackStateListener) {
            onTrackReplayGainResolved(_, _) >> { t, Float gain ->
                reportedInitialGain.set(gain)
            }
        }

        def executor = new LocalAudioTrackExecutor(track, config, new com.sedmelluq.discord.lavaplayer.player.AudioPlayerOptions(), false, 1000)

        when:
        Thread consumer = new Thread({
            while (true) {
                def frame = executor.audioBuffer.provide(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (frame == null || frame.isTerminator()) {
                    break
                }
            }
        })
        consumer.start()
        executor.execute(listener)
        consumer.join(2000)

        then: "first stream reported non-null ReplayGain, and second stream reset track fields to null/false"
        reportedInitialGain.get() != null
        !track.isReplayGainApplied()
        track.getReplayGainDb() == null
    }

    def "executeProcessingLoop delegates seeks to the active chained sub-stream handler"() {
        given:
        byte[] opusFrame = encodeSilentOpusFrame()
        byte[] stream1 = buildOggOpusSubStream(201, 0, opusFrame)

        def stream2Out = new ByteArrayOutputStream()
        stream2Out.write(buildOggPage(OggPageHeader.FLAG_FIRST_PAGE, 0L, 202, 0, buildOpusHead(0)))
        stream2Out.write(buildOggPage(0, 0L, 202, 1, buildOpusTags()))
        for (int p = 1; p <= 10; p++) {
            int flags = (p == 10) ? OggPageHeader.FLAG_LAST_PAGE : 0
            stream2Out.write(buildOggPage(flags, p * 48000L, 202, p + 1, opusFrame))
        }

        def combined = new ByteArrayOutputStream()
        combined.write(stream1)
        combined.write(stream2Out.toByteArray())

        def trackInfo = new AudioTrackInfo("ChainedSeek", "Artist", 10000L, "chained-seek.ogg", false, "chained-seek.ogg")
        def track = new OggAudioTrack(trackInfo, new ByteArraySeekableInputStream(combined.toByteArray()))

        def config = new AudioConfiguration()
        config.setOutputFormat(StandardAudioDataFormats.DISCORD_OPUS)
        def trackError = new AtomicReference<Throwable>()
        def listener = Stub(TrackStateListener) {
            onTrackException(_, _) >> { t, Throwable ex -> trackError.set(ex) }
        }

        // Small 20ms frame buffer so sub-stream 2 blocks inside provideFrames() where interruptibleForSeek == true
        def executor = new LocalAudioTrackExecutor(track, config, new com.sedmelluq.discord.lavaplayer.player.AudioPlayerOptions(), false, 20)
        def postSeekTimecodes = Collections.synchronizedList([])

        when:
        Thread controller = new Thread({
            // Drain stream 1's frame and stream 2's first frame so stream 2 is actively blocked in provideFrames()
            executor.audioBuffer.provide(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            executor.audioBuffer.provide(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(30)

            // Seek within the second chained sub-stream to its 6000ms page
            executor.setPosition(6000L)

            while (true) {
                def frame = executor.audioBuffer.provide(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (frame == null || frame.isTerminator()) {
                    break
                }
                postSeekTimecodes.add(frame.timecode)
            }
        })
        controller.start()
        executor.execute(listener)
        controller.join(3000)

        then:
        trackError.get() == null
        postSeekTimecodes.containsAll([6020L, 6040L, 6060L, 6080L, 6100L])
    }
}
