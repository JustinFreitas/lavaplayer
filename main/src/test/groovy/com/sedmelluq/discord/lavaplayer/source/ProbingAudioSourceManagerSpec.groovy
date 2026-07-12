package com.sedmelluq.discord.lavaplayer.source

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult
import com.sedmelluq.discord.lavaplayer.container.MediaContainerProbe
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import spock.lang.Specification

import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult.refer
import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult.supportedFormat
import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult.unsupportedFormat

class ProbingAudioSourceManagerSpec extends Specification {

    static class TestProbingSourceManager extends ProbingAudioSourceManager {
        AudioTrack trackToReturn
        AudioTrackInfo lastTrackInfo
        MediaContainerDescriptor lastDescriptor

        TestProbingSourceManager(MediaContainerRegistry registry) {
            super(registry)
        }

        @Override
        AudioTrack createTrack(AudioTrackInfo trackInfo, MediaContainerDescriptor containerTrackFactory) {
            lastTrackInfo = trackInfo
            lastDescriptor = containerTrackFactory
            return trackToReturn
        }

        @Override
        String getSourceName() { "test-probing" }

        @Override
        AudioItem loadItem(com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager manager, AudioReference reference) { null }

        @Override
        boolean isTrackEncodable(AudioTrack track) { false }

        @Override
        void encodeTrack(AudioTrack track, DataOutput output) { }

        @Override
        AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) { null }

        @Override
        void shutdown() { }

        // Expose the protected members for direct testing.
        AudioItem handleLoadResultPublic(MediaContainerDetectionResult result) { handleLoadResult(result) }

        void encodeTrackFactoryPublic(MediaContainerDescriptor factory, DataOutput output) { encodeTrackFactory(factory, output) }

        MediaContainerDescriptor decodeTrackFactoryPublic(DataInput input) { decodeTrackFactory(input) }
    }

    private MediaContainerProbe probe(String name) {
        Stub(MediaContainerProbe) {
            getName() >> name
        }
    }

    TestProbingSourceManager manager = new TestProbingSourceManager(new MediaContainerRegistry([probe("mp3")]))

    def "handleLoadResult returns null for a null detection result"() {
        expect:
        manager.handleLoadResultPublic(null) == null
    }

    def "handleLoadResult returns the reference when the result refers elsewhere"() {
        given:
        def reference = new AudioReference("id", "title")
        def result = refer(probe("mp3"), reference)

        expect:
        manager.handleLoadResultPublic(result).is(reference)
    }

    def "handleLoadResult throws an unknown-format exception when no container was detected"() {
        given:
        def result = MediaContainerDetectionResult.unknownFormat()

        when:
        manager.handleLoadResultPublic(result)

        then:
        def ex = thrown(FriendlyException)
        ex.message == "Unknown file format."
    }

    def "handleLoadResult throws with the unsupported reason when the file is not supported"() {
        given:
        def result = unsupportedFormat(probe("mp3"), "Bitrate too high.")

        when:
        manager.handleLoadResultPublic(result)

        then:
        def ex = thrown(FriendlyException)
        ex.message == "Bitrate too high."
    }

    def "handleLoadResult delegates to createTrack for a supported file"() {
        given:
        def trackInfo = new AudioTrackInfo("Title", "Author", 1000L, "id", false, "uri", null, null, null)
        def expectedTrack = Stub(AudioTrack)
        manager.trackToReturn = expectedTrack
        def result = supportedFormat(probe("mp3"), "params", trackInfo)

        when:
        def item = manager.handleLoadResultPublic(result)

        then:
        item.is(expectedTrack)
        manager.lastTrackInfo.is(trackInfo)
        manager.lastDescriptor.parameters == "params"
    }

    def "encodeTrackFactory writes just the probe name when there are no parameters"() {
        given:
        def out = new ByteArrayOutputStream()
        def factory = new MediaContainerDescriptor(probe("mp3"), null)

        when:
        manager.encodeTrackFactoryPublic(factory, new DataOutputStream(out))

        then:
        new DataInputStream(new ByteArrayInputStream(out.toByteArray())).readUTF() == "mp3"
    }

    def "encodeTrackFactory joins the probe name and parameters with a pipe"() {
        given:
        def out = new ByteArrayOutputStream()
        def factory = new MediaContainerDescriptor(probe("mp3"), "some-params")

        when:
        manager.encodeTrackFactoryPublic(factory, new DataOutputStream(out))

        then:
        new DataInputStream(new ByteArrayInputStream(out.toByteArray())).readUTF() == "mp3|some-params"
    }

    def "decodeTrackFactory round-trips a probe with parameters through the registry"() {
        given:
        def out = new ByteArrayOutputStream()
        manager.encodeTrackFactoryPublic(new MediaContainerDescriptor(probe("mp3"), "some-params"), new DataOutputStream(out))

        when:
        def decoded = manager.decodeTrackFactoryPublic(new DataInputStream(new ByteArrayInputStream(out.toByteArray())))

        then:
        decoded.probe.getName() == "mp3"
        decoded.parameters == "some-params"
    }

    def "decodeTrackFactory returns null when the probe is not found in the registry"() {
        given:
        def out = new ByteArrayOutputStream()
        new DataOutputStream(out).writeUTF("unknown-probe")

        expect:
        manager.decodeTrackFactoryPublic(new DataInputStream(new ByteArrayInputStream(out.toByteArray()))) == null
    }
}
