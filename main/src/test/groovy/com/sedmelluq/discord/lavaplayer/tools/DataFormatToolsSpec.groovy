package com.sedmelluq.discord.lavaplayer.tools

import org.apache.hc.core5.net.URLEncodedUtils
import spock.lang.Specification
import spock.lang.Unroll

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

class DataFormatToolsSpec extends Specification {

    // --- extractBetween ---

    def "extractBetween returns the text between the markers"() {
        expect:
        DataFormatTools.extractBetween("prefix[content]suffix", "[", "]") == "content"
    }

    def "extractBetween returns null when the start marker is absent"() {
        expect:
        DataFormatTools.extractBetween("no markers here", "[", "]") == null
    }

    def "extractBetween returns null when the end marker is absent after the start"() {
        expect:
        DataFormatTools.extractBetween("prefix[content", "[", "]") == null
    }

    def "extractBetween with candidates uses the first matching range"() {
        given:
        def candidates = [
            new DataFormatTools.TextRange("<<", ">>"),
            new DataFormatTools.TextRange("[", "]"),
        ] as DataFormatTools.TextRange[]

        expect:
        DataFormatTools.extractBetween("prefix[content]suffix", candidates) == "content"
    }

    def "extractBetween with candidates returns null when none match"() {
        given:
        def candidates = [new DataFormatTools.TextRange("<<", ">>")] as DataFormatTools.TextRange[]

        expect:
        DataFormatTools.extractBetween("prefix[content]suffix", candidates) == null
    }

    // --- extractAfter ---

    def "extractAfter returns everything after the marker"() {
        expect:
        DataFormatTools.extractAfter("prefix=value", "prefix=") == "value"
    }

    def "extractAfter returns null when the marker is absent"() {
        expect:
        DataFormatTools.extractAfter("no marker here", "prefix=") == null
    }

    def "extractAfter with candidates uses the first matching marker"() {
        expect:
        DataFormatTools.extractAfter("prefix=value", ["other=", "prefix="] as String[]) == "value"
    }

    // --- isNullOrEmpty ---

    @Unroll
    def "isNullOrEmpty(#text) == #expected"() {
        expect:
        DataFormatTools.isNullOrEmpty(text) == expected

        where:
        text   || expected
        null   || true
        ""     || true
        "text" || false
    }

    // --- convertToMapLayout(Collection<NameValuePair>) ---

    def "convertToMapLayout from name-value pairs keeps the last value for duplicate names"() {
        given:
        def pairs = URLEncodedUtils.parse("a=1&b=2&a=3", StandardCharsets.UTF_8)

        expect:
        DataFormatTools.convertToMapLayout(pairs) == [a: "3", b: "2"]
    }

    // --- convertToMapLayout(String) ---

    def "convertToMapLayout from a newline-delimited string parses key=value lines"() {
        expect:
        DataFormatTools.convertToMapLayout("a=1\nb=2\r\nc=3") == [a: "1", b: "2", c: "3"]
    }

    def "convertToMapLayout skips lines with no equals sign"() {
        expect:
        DataFormatTools.convertToMapLayout("a=1\nnoEqualsHere\nb=2") == [a: "1", b: "2"]
    }

    def "convertToMapLayout keeps embedded equals signs in the value"() {
        expect:
        DataFormatTools.convertToMapLayout("a=1=2=3") == [a: "1=2=3"]
    }

    // --- decodeUrlEncodedItems ---

    def "decodeUrlEncodedItems parses a plain query string"() {
        expect:
        DataFormatTools.decodeUrlEncodedItems("a=1&b=2", false) == [a: "1", b: "2"]
    }

    def "decodeUrlEncodedItems un-escapes a doubly-escaped unicode ampersand before parsing"() {
        // The input contains a literal backslash-backslash-u0026 sequence (as if a real "&"
        // JSON escape for '&' had itself been escaped again by an outer layer of encoding).
        given:
        def input = 'a=1\\\\u0026b=2'

        expect:
        DataFormatTools.decodeUrlEncodedItems(input, true) == [a: "1", b: "2"]
    }

    // --- defaultOnNull ---

    def "defaultOnNull returns the value when present"() {
        expect:
        DataFormatTools.defaultOnNull("value", "default") == "value"
    }

    def "defaultOnNull returns the default when the value is null"() {
        expect:
        DataFormatTools.defaultOnNull(null, "default") == "default"
    }

    // --- streamToLines ---

    def "streamToLines splits on newlines and collapses runs of blank lines"() {
        given:
        def stream = new ByteArrayInputStream("line1\n\n\nline2\r\nline3".getBytes(StandardCharsets.UTF_8))

        expect:
        DataFormatTools.streamToLines(stream, StandardCharsets.UTF_8) == ["line1", "line2", "line3"] as String[]
    }

    // --- durationTextToMillis ---

    @Unroll
    def "durationTextToMillis(#text) == #expectedMillis"() {
        expect:
        DataFormatTools.durationTextToMillis(text) == expectedMillis

        where:
        text        || expectedMillis
        "45"        || 45000L
        "3:45"      || 225000L
        "1:02:03"   || 3723000L
        "1.02.03"   || 3723000L
    }

    // --- writeNullableText / readNullableText ---

    def "writeNullableText and readNullableText round-trip a non-null string"() {
        given:
        def buffer = new ByteArrayOutputStream()
        DataFormatTools.writeNullableText(new DataOutputStream(buffer), "hello")

        when:
        def result = DataFormatTools.readNullableText(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())))

        then:
        result == "hello"
    }

    def "writeNullableText and readNullableText round-trip null"() {
        given:
        def buffer = new ByteArrayOutputStream()
        DataFormatTools.writeNullableText(new DataOutputStream(buffer), null)

        when:
        def result = DataFormatTools.readNullableText(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())))

        then:
        result == null
    }

    // --- arrayRangeEquals ---

    def "arrayRangeEquals matches a segment at the given offset"() {
        expect:
        DataFormatTools.arrayRangeEquals([1, 2, 3, 4, 5] as byte[], 1, [2, 3, 4] as byte[])
    }

    def "arrayRangeEquals returns false for a mismatched segment"() {
        expect:
        !DataFormatTools.arrayRangeEquals([1, 2, 3, 4, 5] as byte[], 1, [2, 9, 4] as byte[])
    }

    def "arrayRangeEquals returns false when the segment extends past the array bounds"() {
        expect:
        !DataFormatTools.arrayRangeEquals([1, 2, 3] as byte[], 1, [2, 3, 4] as byte[])
    }
}
