package com.sedmelluq.discord.lavaplayer.container.playlists

import spock.lang.Specification
import spock.lang.Unroll

class ExtendedM3uParserSpec extends Specification {

    @Unroll
    def "parseLine treats #description as an empty line"() {
        given:
        def line = ExtendedM3uParser.parseLine(text)

        expect:
        !line.isDirective()
        !line.isData()

        where:
        description       | text
        "an empty string" | ""
        "whitespace only" | "   \t  "
    }

    def "parseLine treats a non-hash-prefixed line as a data line"() {
        when:
        def line = ExtendedM3uParser.parseLine("  https://example.com/stream.mp3  ")

        then:
        line.isData()
        !line.isDirective()
        line.lineData == "https://example.com/stream.mp3"
        line.directiveArguments.isEmpty()
    }

    def "parseLine treats a bare directive with no colon as a directive with empty extra data"() {
        when:
        def line = ExtendedM3uParser.parseLine("#EXTM3U")

        then:
        line.isDirective()
        !line.isData()
        line.directiveName == "EXTM3U"
        line.directiveArguments.isEmpty()
        line.extraData == ""
    }

    def "parseLine preserves raw extra data when it does not match the key=value pattern"() {
        when:
        def line = ExtendedM3uParser.parseLine("#EXTINF:123,Some Title")

        then:
        line.isDirective()
        line.directiveName == "EXTINF"
        line.directiveArguments.isEmpty()
        line.extraData == "123,Some Title"
    }

    def "parseLine extracts a single unquoted key=value argument at the end of the line"() {
        when:
        def line = ExtendedM3uParser.parseLine("#EXT-X-KEY:METHOD=AES-128")

        then:
        line.directiveArguments == [METHOD: "AES-128"]
    }

    def "parseLine extracts multiple quoted and unquoted key=value arguments"() {
        when:
        def line = ExtendedM3uParser.parseLine('#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS="mp4a.40.2",RESOLUTION=1920x1080')

        then:
        line.directiveName == "EXT-X-STREAM-INF"
        line.directiveArguments == [
            BANDWIDTH : "1280000",
            CODECS    : "mp4a.40.2",
            RESOLUTION: "1920x1080",
        ]
    }

    def "parseLine keeps the raw extraData alongside the parsed arguments"() {
        when:
        def line = ExtendedM3uParser.parseLine('#EXT-X-KEY:METHOD=AES-128,URI="https://example.com/key"')

        then:
        line.extraData == 'METHOD=AES-128,URI="https://example.com/key"'
    }
}
