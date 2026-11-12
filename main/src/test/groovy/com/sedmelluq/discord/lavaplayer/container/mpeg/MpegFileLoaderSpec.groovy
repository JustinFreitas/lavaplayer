package com.sedmelluq.discord.lavaplayer.container.mpeg

import com.sedmelluq.discord.lavaplayer.container.common.ReplayGainTools
import com.sedmelluq.discord.lavaplayer.tools.io.ByteArraySeekableInputStream
import spock.lang.Specification

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

class MpegFileLoaderSpec extends Specification {

    private static byte[] box(String type, byte[] payload) {
        def out = new ByteArrayOutputStream()
        def dos = new DataOutputStream(out)
        dos.writeInt(8 + payload.length)
        dos.write(type.getBytes(StandardCharsets.ISO_8859_1))
        dos.write(payload)
        dos.flush()
        return out.toByteArray()
    }

    private static byte[] versionedBox(String type, int versionAndFlags, byte[] payload) {
        def out = new ByteArrayOutputStream()
        def dos = new DataOutputStream(out)
        dos.writeInt(12 + payload.length)
        dos.write(type.getBytes(StandardCharsets.ISO_8859_1))
        dos.writeInt(versionAndFlags)
        dos.write(payload)
        dos.flush()
        return out.toByteArray()
    }

    private static byte[] freeFormItunesTag(String tagName, String value) {
        def nameBytes = tagName.getBytes(StandardCharsets.UTF_8)
        def nameBox = versionedBox("name", 0, nameBytes)

        def valueBytes = value.getBytes(StandardCharsets.UTF_8)
        def dataPayload = new ByteArrayOutputStream()
        def dos = new DataOutputStream(dataPayload)
        dos.writeInt(0) // reserved 4 bytes after version+flags
        dos.write(valueBytes)
        dos.flush()
        def dataBox = versionedBox("data", 1, dataPayload.toByteArray())

        def combined = new ByteArrayOutputStream()
        combined.write(nameBox)
        combined.write(dataBox)
        return box("----", combined.toByteArray())
    }

    def "parseHeaders extracts replaygain_track_peak so positive ReplayGain is peak-limited"() {
        given:
        def gainEntry = freeFormItunesTag("replaygain_track_gain", "+6.0 dB")
        def peakEntry = freeFormItunesTag("replaygain_track_peak", "0.800000")

        def ilstPayload = new ByteArrayOutputStream()
        ilstPayload.write(gainEntry)
        ilstPayload.write(peakEntry)

        def ilst = box("ilst", ilstPayload.toByteArray())
        def meta = versionedBox("meta", 0, ilst)
        def udta = box("udta", meta)
        def moov = box("moov", udta)

        def loader = new MpegFileLoader(new ByteArraySeekableInputStream(moov))

        when:
        loader.parseHeaders()
        def tags = loader.getTags()
        float multiplier = ReplayGainTools.resolveMultiplier(tags, 0.0f, "AAC")

        then:
        tags["REPLAYGAIN_TRACK_GAIN"] == "+6.0 dB"
        tags["REPLAYGAIN_TRACK_PEAK"] == "0.800000"
        Math.abs(multiplier - 1.25f) < 0.0001f
    }

    def "getMetadataName normalizes ReplayGain tags using Locale.ROOT"() {
        given:
        def previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))

        when:
        def gainName = MpegFileLoader.getMetadataName("REPLAYGAIN_TRACK_GAIN")
        def peakName = MpegFileLoader.getMetadataName("REPLAYGAIN_TRACK_PEAK")
        def r128Name = MpegFileLoader.getMetadataName("R128_TRACK_GAIN")

        then:
        gainName == "REPLAYGAIN_TRACK_GAIN"
        peakName == "REPLAYGAIN_TRACK_PEAK"
        r128Name == "R128_TRACK_GAIN"

        cleanup:
        Locale.setDefault(previous)
    }
}
