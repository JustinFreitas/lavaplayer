package com.sedmelluq.discord.lavaplayer.container.playlists

import spock.lang.Specification

class PlsPlaylistContainerProbeSpec extends Specification {

    PlsPlaylistContainerProbe probe = new PlsPlaylistContainerProbe()

    def "loadFromLines refers to the file url with its matching title"() {
        given:
        def lines = [
            "[playlist]",
            "File1=https://example.com/stream.mp3",
            "Title1=My Stream",
            "NumberOfEntries=1",
        ] as String[]

        when:
        def result = probe.loadFromLines(lines)

        then:
        result.isSupportedFile()
        result.getReference().identifier == "https://example.com/stream.mp3"
        result.getReference().title == "My Stream"
    }

    def "loadFromLines falls back to the unknown title constant when no matching Title entry exists"() {
        given:
        def lines = ["File1=https://example.com/stream.mp3"] as String[]

        when:
        def result = probe.loadFromLines(lines)

        then:
        result.getReference().identifier == "https://example.com/stream.mp3"
        result.getReference().title != null
        result.getReference().title != "My Stream"
    }

    def "loadFromLines reports an unsupported format when there are no File entries"() {
        given:
        def lines = ["[playlist]", "NumberOfEntries=0"] as String[]

        when:
        def result = probe.loadFromLines(lines)

        then:
        !result.isSupportedFile()
        result.unsupportedReason != null
    }

    def "loadFromLines ignores non-http(s)/icy file entries"() {
        given:
        def lines = ["File1=/some/local/path.mp3"] as String[]

        when:
        def result = probe.loadFromLines(lines)

        then:
        !result.isSupportedFile()
    }
}
