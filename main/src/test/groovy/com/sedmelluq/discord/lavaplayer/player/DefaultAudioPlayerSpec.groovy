package com.sedmelluq.discord.lavaplayer.player

import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener
import com.sedmelluq.discord.lavaplayer.player.event.AudioEvent
import com.sedmelluq.discord.lavaplayer.player.event.TrackStartEvent
import com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent
import spock.lang.Specification
import java.util.concurrent.TimeUnit

class DefaultAudioPlayerSpec extends Specification {
    def manager = new DefaultAudioPlayerManager()
    def player = new DefaultAudioPlayer(manager)

    def cleanup() {
        manager.shutdown()
    }

    def "player initializes with correct defaults"() {
        expect:
        player.getPlayingTrack() == null
        !player.isPaused()
        player.getVolume() == 100
    }

    def "playTrack sets track, starts it, and dispatches events"() {
        given:
        def track = Mock(InternalAudioTrack)
        def listener = Mock(AudioEventListener)
        player.addListener(listener)

        when:
        player.playTrack(track)

        then:
        player.getPlayingTrack() == track
        1 * listener.onEvent(_ as TrackStartEvent)
    }

    def "stopTrack stops current track and dispatches end event"() {
        given:
        def track = Mock(InternalAudioTrack)
        // Keep the track running so it doesn't immediately exit and finish before we stop it
        track.run(_) >> { Thread.sleep(2000) }
        
        def listener = Mock(AudioEventListener)
        player.addListener(listener)
        player.playTrack(track)

        when:
        player.stopTrack()

        then:
        player.getPlayingTrack() == null
        1 * track.stop()
        1 * listener.onEvent({ it instanceof TrackEndEvent && it.endReason == AudioTrackEndReason.STOPPED })
    }

    def "paused toggle halts play"() {
        given:
        player.setPaused(true)

        expect:
        player.isPaused()

        when:
        player.setPaused(false)

        then:
        !player.isPaused()
    }

    def "volume set clamps volume value"() {
        when:
        player.setVolume(50)

        then:
        player.getVolume() == 50

        when:
        player.setVolume(-10)

        then:
        player.getVolume() == 0

        when:
        player.setVolume(2000)

        then:
        player.getVolume() == 1000 // max volume is 1000
    }

    def "provide returns null if no track playing"() {
        expect:
        player.provide() == null
    }

    def "provide delegates to active track"() {
        given:
        def track = Mock(InternalAudioTrack)
        def frame = Mock(AudioFrame)
        track.provide() >> frame
        player.playTrack(track)

        when:
        def provided = player.provide()

        then:
        provided == frame
    }

    def "listener throwing Throwable does not kill dispatch loop"() {
        given:
        def track = Mock(InternalAudioTrack)
        def badListener = Mock(AudioEventListener)
        def goodListener = Mock(AudioEventListener)
        player.addListener(badListener)
        player.addListener(goodListener)

        badListener.onEvent(_ as AudioEvent) >> { throw new Error("Cruel Error") }

        when:
        player.playTrack(track)

        then:
        1 * goodListener.onEvent(_ as TrackStartEvent) // still dispatches to good listener!
    }
}
