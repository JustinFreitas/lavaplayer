package com.sedmelluq.discord.lavaplayer.track.info

import com.sedmelluq.discord.lavaplayer.tools.Units
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import spock.lang.Specification

class AudioTrackInfoBuilderSpec extends Specification {

    private AudioTrackInfoProvider provider(
        String title = null, String author = null, Long length = null, String identifier = null,
        String uri = null, String artworkUrl = null, String isrc = null, Float replayGainDb = null
    ) {
        Stub(AudioTrackInfoProvider) {
            getTitle() >> title
            getAuthor() >> author
            getLength() >> length
            getIdentifier() >> identifier
            getUri() >> uri
            getArtworkUrl() >> artworkUrl
            getISRC() >> isrc
            getReplayGainDb() >> replayGainDb
        }
    }

    // --- individual setters keep the old value on null (except setIsStream) ---

    def "setTitle keeps the previous value when passed null"() {
        given:
        def builder = AudioTrackInfoBuilder.empty().setTitle("Original")

        when:
        builder.setTitle(null)

        then:
        builder.title == "Original"
    }

    def "setIsStream overwrites the previous value with null, unlike the other setters"() {
        given:
        // Known length -> the length-based default for isStream is false, which differs from
        // the true we're about to wipe -- proving setIsStream(null) actually clears the field
        // rather than preserving the earlier explicit value.
        def builder = AudioTrackInfoBuilder.empty().setLength(60000L).setIsStream(true)

        when:
        builder.setIsStream(null)

        then:
        !builder.build().isStream
    }

    // --- apply ---

    def "apply merges non-null fields from the provider, leaving existing fields when the provider returns null"() {
        given:
        def builder = AudioTrackInfoBuilder.empty()
            .setTitle("Existing Title")
            .setAuthor("Existing Author")

        when:
        builder.apply(provider(null, null, 5000L, "id123", "uri123", null, "isrc123", 1.5f))

        then:
        builder.title == "Existing Title"
        builder.author == "Existing Author"
        builder.length == 5000L
        builder.identifier == "id123"
        builder.uri == "uri123"
        builder.getISRC() == "isrc123"
        builder.replayGainDb == 1.5f
    }

    def "apply with a null provider is a no-op"() {
        given:
        def builder = AudioTrackInfoBuilder.empty().setTitle("Existing Title")

        when:
        def result = builder.apply(null)

        then:
        result.is(builder)
        builder.title == "Existing Title"
    }

    def "apply returns the same builder instance for chaining"() {
        given:
        def builder = AudioTrackInfoBuilder.empty()

        expect:
        builder.apply(provider("Title")).is(builder)
    }

    // --- build ---

    def "build defaults length to DURATION_MS_UNKNOWN and marks the track as a stream when never set"() {
        given:
        def builder = AudioTrackInfoBuilder.empty().setTitle("Title").setAuthor("Author")

        when:
        def info = builder.build()

        then:
        info.length == Units.DURATION_MS_UNKNOWN
        info.isStream
    }

    def "build treats a known length as marking a non-stream by default"() {
        given:
        def builder = AudioTrackInfoBuilder.empty().setLength(60000L)

        when:
        def info = builder.build()

        then:
        info.length == 60000L
        !info.isStream
    }

    def "build respects an explicitly set isStream flag regardless of length"() {
        given:
        def builder = AudioTrackInfoBuilder.empty().setLength(60000L).setIsStream(true)

        when:
        def info = builder.build()

        then:
        info.length == 60000L
        info.isStream
    }

    def "build carries through all remaining fields"() {
        given:
        def builder = AudioTrackInfoBuilder.empty()
            .setTitle("Title")
            .setAuthor("Author")
            .setIdentifier("id123")
            .setUri("uri123")
            .setArtworkUrl("art123")
            .setISRC("isrc123")
            .setReplayGainDb(2.0f)

        when:
        def info = builder.build()

        then:
        info.title == "Title"
        info.author == "Author"
        info.identifier == "id123"
        info.uri == "uri123"
        info.artworkUrl == "art123"
        info.isrc == "isrc123"
        info.replayGainDb == 2.0f
    }

    // --- create ---

    def "create seeds unknown-title/author defaults and applies the reference"() {
        given:
        def reference = new AudioReference("ref-id", "Ref Title")

        when:
        def info = AudioTrackInfoBuilder.create(reference, null).build()

        then:
        info.title == "Ref Title"
        info.author == "Unknown artist"
        info.identifier == "ref-id"
        info.uri == "ref-id" // AudioReference.getUri() returns the identifier
        info.length == Units.DURATION_MS_UNKNOWN
    }

    def "create falls back to Unknown title when the reference has no title"() {
        given:
        def reference = new AudioReference("ref-id", null)

        when:
        def info = AudioTrackInfoBuilder.create(reference, null).build()

        then:
        info.title == "Unknown title"
    }

    def "create applies each stream track info provider in order, later providers overriding earlier ones"() {
        given:
        def reference = new AudioReference("ref-id", "Ref Title")
        def stream = Mock(SeekableInputStream) {
            getTrackInfoProviders() >> [
                provider("First Provider Title", "First Author", 1000L),
                provider(null, "Second Author"),
            ]
        }

        when:
        def info = AudioTrackInfoBuilder.create(reference, stream).build()

        then:
        info.title == "First Provider Title" // second provider didn't override (returned null)
        info.author == "Second Author" // second provider did override
        info.length == 1000L
    }

    // --- empty ---

    def "empty starts with every field null"() {
        expect:
        with(AudioTrackInfoBuilder.empty()) {
            title == null
            author == null
            length == null
            identifier == null
            uri == null
            artworkUrl == null
            getISRC() == null
            replayGainDb == null
        }
    }
}
