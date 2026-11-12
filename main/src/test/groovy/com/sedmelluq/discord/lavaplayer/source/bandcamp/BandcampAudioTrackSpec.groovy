package com.sedmelluq.discord.lavaplayer.source.bandcamp

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.HttpEntity
import org.apache.hc.client5.http.classic.methods.HttpGet
import spock.lang.Specification

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class BandcampAudioTrackSpec extends Specification {

    def "getTrackMediaUrl correctly unescapes HTML entities"() {
        given:
        def sourceManager = new BandcampAudioSourceManager()
        def trackInfo = new AudioTrackInfo(
            "title", "artist", 1000L, "https://example.com/track/title", false, "https://example.com/track/title"
        )
        def track = new BandcampAudioTrack(trackInfo, sourceManager)

        // Mock the HTTP components
        def httpInterface = Mock(HttpInterface)
        def response = Mock(ClassicHttpResponse)
        def entity = Mock(HttpEntity)

        // Mock HTML response containing a Bandcamp tralbum JSON with escaped HTML entities in the mp3 URL
        def escapedUrl = "https://t4.bcbits.com/stream/abc&amp;xyz"
        def htmlContent = """
            <html>
                <head>
                    <script data-tralbum="{&quot;artist&quot;:&quot;Artist&quot;,&quot;trackinfo&quot;:[{&quot;title&quot;:&quot;Title&quot;,&quot;title_link&quot;:&quot;/track/title&quot;,&quot;duration&quot;:100.0,&quot;file&quot;:{&quot;mp3-128&quot;:&quot;${escapedUrl}&quot;}}],&quot;current&quot;:{&quot;isrc&quot;:&quot;1234&quot;}}"></script>
                </head>
            </html>
        """

        when:
        // Use Groovy's direct access to private methods to call getTrackMediaUrl
        def resultUrl = track.getTrackMediaUrl(httpInterface)

        then:
        1 * httpInterface.execute(_ as HttpGet) >> response
        1 * response.getCode() >> 200
        1 * response.getEntity() >> entity
        1 * entity.getContent() >> new ByteArrayInputStream(htmlContent.getBytes(StandardCharsets.UTF_8))

        // Check that the returned URL is correctly unescaped
        resultUrl == "https://t4.bcbits.com/stream/abc&xyz"
    }

    def "process throws FriendlyException when media URL is not available"() {
        given:
        def sourceManager = Mock(BandcampAudioSourceManager)
        def trackInfo = new AudioTrackInfo(
            "title", "artist", 1000L, "https://example.com/track/title", false, "https://example.com/track/title"
        )
        def track = new BandcampAudioTrack(trackInfo, sourceManager)
        def httpInterface = Mock(HttpInterface)
        def response = Mock(ClassicHttpResponse)
        def entity = Mock(HttpEntity)
        def executor = Mock(com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor)

        sourceManager.getHttpInterface() >> httpInterface
        sourceManager.readTrackListInformation(_ as String) >> com.sedmelluq.discord.lavaplayer.tools.JsonBrowser.NULL_BROWSER

        when:
        track.process(executor)

        then:
        1 * httpInterface.execute(_ as HttpGet) >> response
        1 * response.getCode() >> 200
        1 * response.getEntity() >> entity
        1 * entity.getContent() >> new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8))

        def ex = thrown(com.sedmelluq.discord.lavaplayer.tools.FriendlyException)
        ex.severity == com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS
        ex.message == "Track media stream URL not found on Bandcamp page."
    }

    def "readTrackListInformation unescapes HTML entities and extractTrack guards null duration on pre-order tracks"() {
        given:
        def sourceManager = new BandcampAudioSourceManager()
        def htmlContent = """
            <html>
                <head>
                    <script data-tralbum="{&quot;artist&quot;:&quot;Simon &amp; Garfunkel&quot;,&quot;current&quot;:{&quot;title&quot;:&quot;Collector&#39;s Album &amp; More&quot;},&quot;trackinfo&quot;:[{&quot;title&quot;:&quot;Don&#39;t Stop &amp; Listen&quot;,&quot;title_link&quot;:&quot;/track/dont-stop&quot;,&quot;duration&quot;:null},{&quot;title&quot;:&quot;Unlinked Bonus&quot;,&quot;title_link&quot;:null,&quot;duration&quot;:null}]}"></script>
                </head>
            </html>
        """

        when:
        def info = sourceManager.readTrackListInformation(htmlContent)
        def artist = info.get("artist").safeText()
        def playablePreorder = sourceManager.extractTrack(info.get("trackinfo").index(0), "https://example.bandcamp.com", artist, null, null)
        def unlinkedTrack = sourceManager.extractTrack(info.get("trackinfo").index(1), "https://example.bandcamp.com", artist, null, null)

        then:
        artist == "Simon & Garfunkel"
        info.get("current").get("title").safeText() == "Collector's Album & More"
        playablePreorder != null
        playablePreorder.info.title == "Don't Stop & Listen"
        playablePreorder.info.author == "Simon & Garfunkel"
        playablePreorder.info.length == com.sedmelluq.discord.lavaplayer.tools.Units.DURATION_MS_UNKNOWN
        unlinkedTrack == null
    }
}
