package com.sedmelluq.discord.lavaplayer.container.ogg

import com.sedmelluq.discord.lavaplayer.container.ogg.opus.OggOpusTrackHandler
import com.sedmelluq.discord.lavaplayer.container.ogg.OggPacketInputStream
import com.sedmelluq.discord.lavaplayer.tools.io.DirectBufferStreamBroker
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameBuffer
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerOptions
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import spock.lang.Specification

class OggOpusTrackHandlerSpec extends Specification {
    def "initialise forces 48000 sample rate for opus packet router"() {
        given:
        def packetInputStream = new OggPacketInputStream(null, false)
        def broker = new DirectBufferStreamBroker(1024)
        def configuration = new AudioConfiguration()
        def frameBuffer = Mock(AudioFrameBuffer)
        def playerOptions = new AudioPlayerOptions()
        def outputFormat = StandardAudioDataFormats.DISCORD_OPUS
        
        def context = new AudioProcessingContext(configuration, frameBuffer, playerOptions, outputFormat)
        def handler = new OggOpusTrackHandler(packetInputStream, broker, 2, 44100, [:], 0)
        
        when:
        handler.initialise(context, 0, 0)
        
        then:
        handler.opusPacketRouter != null
        handler.opusPacketRouter.inputFrequency == 48000
    }
}
