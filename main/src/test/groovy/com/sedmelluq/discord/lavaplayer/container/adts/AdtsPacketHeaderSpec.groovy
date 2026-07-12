package com.sedmelluq.discord.lavaplayer.container.adts

import spock.lang.Specification

class AdtsPacketHeaderSpec extends Specification {

    private static AdtsPacketHeader header(int profile = 2, int sampleRate = 44100, int channels = 2, int payloadLength = 100) {
        new AdtsPacketHeader(true, profile, sampleRate, channels, payloadLength)
    }

    def "canUseSameDecoder is true for headers with matching profile, sample rate and channels"() {
        expect:
        header(2, 44100, 2, 50).canUseSameDecoder(header(2, 44100, 2, 999))
    }

    def "canUseSameDecoder ignores differences in protection-absent and payload length"() {
        given:
        def a = new AdtsPacketHeader(true, 2, 44100, 2, 50)
        def b = new AdtsPacketHeader(false, 2, 44100, 2, 999)

        expect:
        a.canUseSameDecoder(b)
    }

    def "canUseSameDecoder is false when the profile differs"() {
        expect:
        !header(2, 44100, 2, 50).canUseSameDecoder(header(1, 44100, 2, 50))
    }

    def "canUseSameDecoder is false when the sample rate differs"() {
        expect:
        !header(2, 44100, 2, 50).canUseSameDecoder(header(2, 48000, 2, 50))
    }

    def "canUseSameDecoder is false when the channel count differs"() {
        expect:
        !header(2, 44100, 2, 50).canUseSameDecoder(header(2, 44100, 1, 50))
    }

    def "canUseSameDecoder is false when compared against null"() {
        expect:
        !header().canUseSameDecoder(null)
    }
}
