package com.sedmelluq.discord.lavaplayer.container.ogg.opus;

import com.sedmelluq.discord.lavaplayer.container.common.OpusPacketRouter;
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
    private OpusPacketRouter opusPacketRouter;
    private float volumeMultiplier = 1.0f;

    /**
     * @param packetInputStream OGG packet input stream
     * @param broker            Broker for loading stream data into a direct byte
     *                          buffer.
     * @param channelCount      Number of channels in the track.
     * @param sampleRate        Sample rate of the track.
     * @param tags              Parsed OGG tags.
     */
    @SuppressWarnings("unused")
    public OggOpusTrackHandler(OggPacketInputStream packetInputStream, DirectBufferStreamBroker broker,
            int channelCount,
            int sampleRate, Map<String, String> tags) {

        this.packetInputStream = packetInputStream;
        this.broker = broker;
        this.channelCount = channelCount;
        this.sampleRate = sampleRate;
        this.tags = tags;
    }

    @Override
    public void initialise(AudioProcessingContext context, long timecode, long desiredTimecode) {
        if (context.configuration.isReplayGainEnabled()) {
            this.volumeMultiplier = resolveVolumeMultiplier();
        }

        opusPacketRouter = new OpusPacketRouter(context, sampleRate, channelCount);
        if (volumeMultiplier != 1.0f) {
            opusPacketRouter.setVolumeMultiplier(volumeMultiplier);
        }
        opusPacketRouter.seekPerformed(desiredTimecode, timecode);
    }

    private float resolveVolumeMultiplier() {
        String replayGainTag = tags.get("REPLAYGAIN_TRACK_GAIN");
        if (replayGainTag == null) {
            return 1.0f;
        }

        try {
            String cleanValue = replayGainTag.replace("dB", "").trim();
            float gainDb = Float.parseFloat(cleanValue);
            float multiplier = (float) Math.pow(10, gainDb / 20.0f);
            log.debug("Applying ReplayGain (Opus): {} dB -> {}x multiplier", gainDb, multiplier);
            return multiplier;
        } catch (NumberFormatException e) {
            log.warn("Invalid ReplayGain tag value: {}", replayGainTag);
            return 1.0f;
        }
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
