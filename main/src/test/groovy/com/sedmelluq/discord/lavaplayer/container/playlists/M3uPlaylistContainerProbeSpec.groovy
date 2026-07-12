package com.sedmelluq.discord.lavaplayer.container.playlists

import spock.lang.Specification

class M3uPlaylistContainerProbeSpec extends Specification {

    M3uPlaylistContainerProbe probe = new M3uPlaylistContainerProbe()

    def "loadSingleItemPlaylist refers to the first http(s)/icy url found, using the preceding EXTINF title"() {
        given:
        def lines = [
            "#EXTM3U",
            "#EXTINF:-1,My Stream",
            "https://example.com/stream.mp3",
        ] as String[]

        when:
        def result = probe.loadSingleItemPlaylist(lines)

        then:
        result != null
        result.getReference().identifier == "https://example.com/stream.mp3"
        result.getReference().title == "My Stream"
    }

    def "loadSingleItemPlaylist accepts icy:// urls"() {
        given:
        def lines = ["icy://example.com/stream"] as String[]

        when:
        def result = probe.loadSingleItemPlaylist(lines)

        then:
        result.getReference().identifier == "icy://example.com/stream"
    }

    def "loadSingleItemPlaylist has a null title when there was no preceding EXTINF"() {
        given:
        def lines = ["https://example.com/stream.mp3"] as String[]

        when:
        def result = probe.loadSingleItemPlaylist(lines)

        then:
        result.getReference().title == null
    }

    def "loadSingleItemPlaylist resets the pending title after a non-matching, non-empty data line"() {
        given:
        def lines = [
            "#EXTINF:-1,Stale Title",
            "some-non-url-line",
            "https://example.com/stream.mp3",
        ] as String[]

        when:
        def result = probe.loadSingleItemPlaylist(lines)

        then:
        result.getReference().title == null
    }

    def "loadSingleItemPlaylist returns null when no url line is present"() {
        expect:
        probe.loadSingleItemPlaylist(["#EXTM3U", "#EXTINF:-1,Title"] as String[]) == null
    }

    def "extractTitleFromInfo returns the text after the first comma"() {
        expect:
        probe.extractTitleFromInfo("#EXTINF:-1,My Stream Title") == "My Stream Title"
    }

    def "extractTitleFromInfo returns null when there is no comma"() {
        expect:
        probe.extractTitleFromInfo("#EXTINF:-1") == null
    }
}
