package com.sedmelluq.discord.lavaplayer.player

import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import spock.lang.Specification

import java.io.DataInput
import java.io.DataOutput
import java.util.function.Consumer
import java.util.function.Function

class DefaultAudioPlayerManagerSpec extends Specification {
    def manager = new DefaultAudioPlayerManager()

    def cleanup() {
        manager.shutdown()
    }

    /** A minimal source manager that also tracks HttpConfigurable calls, for the configurator tests. */
    static class TrackingSourceManager implements AudioSourceManager, HttpConfigurable {
        List<Function<RequestConfig, RequestConfig>> appliedRequestConfigurators = []
        List<Consumer<HttpClientBuilder>> appliedBuilderConfigurators = []
        boolean shutdownCalled = false

        @Override
        String getSourceName() { "tracking" }

        @Override
        AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) { null }

        @Override
        boolean isTrackEncodable(AudioTrack track) { false }

        @Override
        void encodeTrack(AudioTrack track, DataOutput output) { }

        @Override
        AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) { null }

        @Override
        void shutdown() { shutdownCalled = true }

        @Override
        void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
            appliedRequestConfigurators.add(configurator)
        }

        @Override
        void configureBuilder(Consumer<HttpClientBuilder> configurator) {
            appliedBuilderConfigurators.add(configurator)
        }
    }

    def "registerSourceManager makes the manager retrievable by class"() {
        given:
        def sourceManager = Mock(AudioSourceManager)

        when:
        manager.registerSourceManager(sourceManager)

        then:
        manager.source(AudioSourceManager).is(sourceManager)
    }

    def "source returns null for a manager class that was never registered"() {
        expect:
        manager.source(AudioSourceManager) == null
    }

    def "getSourceManagers returns an unmodifiable view containing the registered managers"() {
        given:
        def sourceManager = Mock(AudioSourceManager)
        manager.registerSourceManager(sourceManager)

        when:
        manager.getSourceManagers().add(Mock(AudioSourceManager))

        then:
        thrown(UnsupportedOperationException)
    }

    def "setFrameBufferDuration clamps to a minimum of 200ms"() {
        when:
        manager.setFrameBufferDuration(50)

        then:
        manager.getFrameBufferDuration() == 200
    }

    def "setFrameBufferDuration keeps a value above the minimum unchanged"() {
        when:
        manager.setFrameBufferDuration(3000)

        then:
        manager.getFrameBufferDuration() == 3000
    }

    def "setUseSeekGhosting round-trips through isUsingSeekGhosting"() {
        expect:
        manager.isUsingSeekGhosting() // enabled by default

        when:
        manager.setUseSeekGhosting(false)

        then:
        !manager.isUsingSeekGhosting()
    }

    def "registering an HttpConfigurable source manager after a request configurator was set applies it immediately"() {
        given:
        Function<RequestConfig, RequestConfig> configurator = { it }
        manager.setHttpRequestConfigurator(configurator)
        def sourceManager = new TrackingSourceManager()

        when:
        manager.registerSourceManager(sourceManager)

        then:
        sourceManager.appliedRequestConfigurators == [configurator]
    }

    def "setting a request configurator after registration retroactively applies it to already-registered managers"() {
        given:
        def sourceManager = new TrackingSourceManager()
        manager.registerSourceManager(sourceManager)
        Function<RequestConfig, RequestConfig> configurator = { it }

        when:
        manager.setHttpRequestConfigurator(configurator)

        then:
        sourceManager.appliedRequestConfigurators == [configurator]
    }

    def "setting a builder configurator retroactively applies it to already-registered managers"() {
        given:
        def sourceManager = new TrackingSourceManager()
        manager.registerSourceManager(sourceManager)
        Consumer<HttpClientBuilder> configurator = { }

        when:
        manager.setHttpBuilderConfigurator(configurator)

        then:
        sourceManager.appliedBuilderConfigurators == [configurator]
    }

    def "a non-HttpConfigurable source manager is registered without error"() {
        given:
        def sourceManager = Mock(AudioSourceManager)
        manager.setHttpRequestConfigurator({ it })

        when:
        manager.registerSourceManager(sourceManager)

        then:
        noExceptionThrown()
        manager.source(AudioSourceManager).is(sourceManager)
    }

    def "shutdown shuts down every registered source manager"() {
        // Uses its own manager instance (rather than the shared field-level one) since it calls
        // shutdown() itself -- the shared instance is also shut down by cleanup() after every test.
        given:
        def localManager = new DefaultAudioPlayerManager()
        def sourceManager = new TrackingSourceManager()
        localManager.registerSourceManager(sourceManager)

        when:
        localManager.shutdown()

        then:
        sourceManager.shutdownCalled
    }
}
