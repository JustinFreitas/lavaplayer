package com.sedmelluq.discord.lavaplayer.container.ogg.opus;

import com.sedmelluq.discord.lavaplayer.container.common.OpusPacketRouter;
import com.sedmelluq.discord.lavaplayer.container.common.ReplayGainTools;
import com.sedmelluq.discord.lavaplayer.container.ogg.OggPacketInputStream;
import com.sedmelluq.discord.lavaplayer.container.ogg.OggTrackHandler;
import com.sedmelluq.discord.lavaplayer.tools.io.DirectBufferStreamBroker;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * OGG stream handler for Opus codec.
 */
@SuppressWarnings("unused")
public class OggOpusTrackHandler implements OggTrackHandler {
    private static final Logger log = LoggerFactory.getLogger(OggOpusTrackHandler.class);

    private final OggPacketInputStream packetInputStream;
    private final DirectBufferStreamBroker broker;
    private final int channelCount;
    private final int sampleRate;
    private final Map<String, String> tags;
    private final int headerGain;
    private OpusPacketRouter opusPacketRouter;
    private float volumeMultiplier = 1.0f;

    /**
     * @param packetInputStream OGG packet input stream
     * @param broker            Broker for loading stream data into a direct byte
     *                          buffer.
     * @param channelCount      Number of channels in the track.
     * @param sampleRate        Sample rate of the track.
     * @param tags              Parsed OGG tags.
     * @param headerGain        The output gain from the Opus header.
     */
    @SuppressWarnings("unused")
    public OggOpusTrackHandler(OggPacketInputStream packetInputStream, DirectBufferStreamBroker broker,
            int channelCount,
            int sampleRate, Map<String, String> tags, int headerGain) {

        this.packetInputStream = packetInputStream;
        this.broker = broker;
        this.channelCount = channelCount;
        this.sampleRate = sampleRate;
        this.tags = tags;
        this.headerGain = headerGain;
    }

    @Override
    public void initialise(AudioProcessingContext context, long timecode, long desiredTimecode) {
        // The Opus header output gain is folded into resolveVolumeMultiplier() rather than applied unconditionally.
        // The codec spec does treat it as mandatory on decode, but lavaplayer plays streams raw unless configured
        // otherwise and replay gain is disabled by default, so applying any gain to an unconfigured player would
        // change existing playback levels.
        if (context.configuration.isReplayGainEnabled()) {
            this.volumeMultiplier = resolveVolumeMultiplier();
        }

        opusPacketRouter = new OpusPacketRouter(context, 48000, channelCount);
        if (volumeMultiplier != 1.0f) {
            opusPacketRouter.setVolumeMultiplier(volumeMultiplier);
        }
        opusPacketRouter.seekPerformed(desiredTimecode, timecode);
    }

    private float resolveVolumeMultiplier() {
        // The header output gain is in Q7.8 format and is folded in on top of whatever the tags ask for.
        return ReplayGainTools.resolveMultiplier(tags, headerGain / 256.0f, "Opus");
    }

    @Override
    public void provideFrames() throws InterruptedException {
        try {
            while (packetInputStream.startNewPacket()) {
                broker.consumeNext(packetInputStream, Integer.MAX_VALUE, Integer.MAX_VALUE);

                ByteBuffer buffer = broker.getBuffer();

                if (buffer.remaining() > 0) {
                    opusPacketRouter.process(buffer);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Float getReplayGainDb() {
        return volumeMultiplier != 1.0f ? (float) (20.0 * Math.log10(volumeMultiplier)) : null;
    }

    @Override
    public void seekToTimecode(long timecode) {
        try {
            opusPacketRouter.seekPerformed(timecode, packetInputStream.seek(timecode));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        if (opusPacketRouter != null) {
            opusPacketRouter.close();
        }
    }
}
