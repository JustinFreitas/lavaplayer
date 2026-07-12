package com.sedmelluq.discord.lavaplayer.container.playlists

import spock.lang.Specification

class HlsStreamSegmentParserSpec extends Specification {

    def "parseFromLines pairs an EXTINF directive with the following data line"() {
        given:
        def lines = [
            "#EXTM3U",
            "#EXTINF:10.5,Segment Title",
            "segment001.ts",
        ] as String[]

        when:
        def segments = HlsStreamSegmentParser.parseFromLines(lines)

        then:
        segments.size() == 1
        segments[0].url == "segment001.ts"
        segments[0].duration == 10500L
        segments[0].name == "Segment Title"
    }

    def "parseFromLines handles multiple segments in sequence"() {
        given:
        def lines = [
            "#EXTINF:5.0,First",
            "seg1.ts",
            "#EXTINF:7.5,Second",
            "seg2.ts",
        ] as String[]

        when:
        def segments = HlsStreamSegmentParser.parseFromLines(lines)

        then:
        segments.size() == 2
        segments[0].url == "seg1.ts"
        segments[0].duration == 5000L
        segments[1].url == "seg2.ts"
        segments[1].duration == 7500L
    }

    def "parseFromLines adds a segment with null duration and name when there is no preceding EXTINF"() {
        given:
        def lines = ["segment001.ts"] as String[]

        when:
        def segments = HlsStreamSegmentParser.parseFromLines(lines)

        then:
        segments.size() == 1
        segments[0].url == "segment001.ts"
        segments[0].duration == null
        segments[0].name == null
    }

    def "parseFromLines still extracts the name when the duration text is not parseable"() {
        given:
        def lines = ["#EXTINF:not-a-number,Segment Title", "segment001.ts"] as String[]

        when:
        def segments = HlsStreamSegmentParser.parseFromLines(lines)

        then:
        segments[0].duration == null
        segments[0].name == "Segment Title"
    }

    def "parseFromLines leaves duration and name null when EXTINF has no comma-separated title"() {
        given:
        def lines = ["#EXTINF:10.5", "segment001.ts"] as String[]

        when:
        def segments = HlsStreamSegmentParser.parseFromLines(lines)

        then:
        segments[0].duration == null
        segments[0].name == null
    }

    def "parseFromLines incorrectly reuses stale EXTINF info for a data line with no EXTINF of its own"() {
        // This documents an actual quirk in parseFromLines: segmentInfo is never reset to null
        // after being consumed, so a data line that has no EXTINF directive immediately before it
        // silently inherits the duration/name from whatever EXTINF was last seen.
        given:
        def lines = [
            "#EXTINF:5.0,First",
            "seg1.ts",
            "seg2.ts", // no EXTINF before this one
        ] as String[]

        when:
        def segments = HlsStreamSegmentParser.parseFromLines(lines)

        then:
        segments.size() == 2
        segments[1].url == "seg2.ts"
        segments[1].duration == 5000L
        segments[1].name == "First"
    }

    def "parseFromLines ignores blank lines and non-EXTINF directives"() {
        given:
        def lines = [
            "#EXTM3U",
            "",
            "#EXT-X-VERSION:3",
            "#EXTINF:5.0,Only Segment",
            "seg1.ts",
        ] as String[]

        when:
        def segments = HlsStreamSegmentParser.parseFromLines(lines)

        then:
        segments.size() == 1
        segments[0].name == "Only Segment"
    }

    def "parseFromLines returns an empty list for no segments"() {
        expect:
        HlsStreamSegmentParser.parseFromLines(["#EXTM3U"] as String[]).isEmpty()
    }
}
