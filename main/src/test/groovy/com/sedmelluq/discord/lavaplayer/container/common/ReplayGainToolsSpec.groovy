package com.sedmelluq.discord.lavaplayer.container.common

import spock.lang.Specification
import spock.lang.Unroll

class ReplayGainToolsSpec extends Specification {
    @Unroll
    def "parses gain tag #tag as #expected dB"() {
        expect:
        ReplayGainTools.parseGainDb(tag) == expected

        where:
        tag           || expected
        "-5.0 dB"     || -5.0f
        "+2.5 dB"     || 2.5f
        "-1.31 dB"    || -1.31f
        "  +10.25dB " || 10.25f
        "13.04"       || 13.04f
        null          || null
        "not a gain"  || null
    }

    def "converts decibels to a linear multiplier"() {
        expect:
        ReplayGainTools.multiplierFromDb(0.0f) == 1.0f
        Math.abs(ReplayGainTools.multiplierFromDb(6.0206f) - 2.0f) < 0.001f
        Math.abs(ReplayGainTools.multiplierFromDb(-6.0206f) - 0.5f) < 0.001f
    }

    def "applies the R128 reference offset so opus matches replaygain 2.0"() {
        given: "an R128 gain of 0, which is -23 LUFS, and the equivalent ReplayGain 2.0 tag at -18 LUFS"
        def r128 = [(ReplayGainTools.R128_TRACK_GAIN_TAG): "0"]
        def replayGain = [(ReplayGainTools.TRACK_GAIN_TAG): "5.0 dB"]

        expect: "both resolve to the same multiplier"
        ReplayGainTools.resolveGainDb(r128) == 5.0f
        ReplayGainTools.resolveMultiplier(r128, 0.0f, "test") ==
            ReplayGainTools.resolveMultiplier(replayGain, 0.0f, "test")
    }

    def "prefers R128 over the replaygain tag when both are present"() {
        given:
        def tags = [
            (ReplayGainTools.R128_TRACK_GAIN_TAG): "256", // 1 dB, plus the 5 dB reference offset
            (ReplayGainTools.TRACK_GAIN_TAG)     : "-20.0 dB"
        ]

        expect:
        ReplayGainTools.resolveGainDb(tags) == 6.0f
    }

    def "folds extra gain such as the opus header gain into the total"() {
        given:
        def tags = [(ReplayGainTools.TRACK_GAIN_TAG): "-3.0 dB"]

        expect: "-3 dB tagged plus 3 dB of header gain cancels out to no change"
        ReplayGainTools.resolveMultiplier(tags, 3.0f, "test") == 1.0f
    }

    @Unroll
    def "caps multiplier #multiplier against peak #peak, giving #expected"() {
        expect:
        Math.abs(ReplayGainTools.limitToPeak(multiplier, peak) - expected) < 0.0001f

        where:
        multiplier | peak         || expected
        4.487f     | "0.449324"   || 2.22557f  // D&D Ambience - Damp Cave: +13.04 dB would clip at 2.016x
        3.25f      | "0.283578"   || 3.25f     // Generic Dungeon 1: +10.25 dB reaches 0.92, no cap needed
        2.0f       | "0.5"        || 2.0f      // exactly full scale is not clipping
        2.0f       | null         || 2.0f      // no peak tag, nothing to cap against
        0.5f       | "0.9"        || 0.5f      // attenuation can never clip
        2.0f       | "0"          || 2.0f      // a nonsense peak is ignored
        2.0f       | "not a peak" || 2.0f
    }

    def "does not cap when the track has no gain at all"() {
        expect:
        ReplayGainTools.resolveMultiplier([:], 0.0f, "test") == 1.0f
    }

    def "limits a boosted track so it lands exactly at full scale"() {
        given: "the real Damp Cave tags, which multiply out to 2.016x without a limit"
        def tags = [
            (ReplayGainTools.TRACK_GAIN_TAG): "+13.04 dB",
            (ReplayGainTools.TRACK_PEAK_TAG): "0.449324"
        ]

        when:
        def multiplier = ReplayGainTools.resolveMultiplier(tags, 0.0f, "test")

        then: "the peak is brought to full scale rather than past it"
        Math.abs(multiplier * 0.449324f - 1.0f) < 0.0001f
    }
}
