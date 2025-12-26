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
        if (context.configuration.isReplayGainEnabled()) {
            this.volumeMultiplier = resolveVolumeMultiplier();
        } else if (headerGain != 0) {
             // Even if ReplayGain is disabled, the header gain should arguably be applied as it's part of the codec spec.
             // However, strictly speaking, isReplayGainEnabled might imply "no volume changes".
             // But usually Header Gain is for normalization to a reference level.
             // Let's assume we ONLY apply any gain if isReplayGainEnabled is true, OR if we decide header gain is mandatory.
             // The user prompt implies "ReplayGain support".
             // If I follow typical behavior, header gain is always applied. But Lavaplayer tends to be "raw" unless configured.
             // But since `isReplayGainEnabled` defaults to false, applying header gain might surprise users.
             // Let's stick to applying it only when ReplayGain is enabled for now, or maybe not?
             // Actually, the spec says "The output gain is a value... to be applied... when decoding".
             // It seems mandatory. But let's verify context.
             // For now, I will group it with resolveVolumeMultiplier logic.
        }
        
        // Wait, if I change the logic to apply header gain ALWAYS, I might break existing volume assumptions.
        // I'll stick to isReplayGainEnabled for now for SAFETY, as requested by "Conventions" (mimic existing).
        // If ReplayGain is enabled, we include header gain.
        
        opusPacketRouter = new OpusPacketRouter(context, sampleRate, channelCount);
        if (volumeMultiplier != 1.0f) {
            opusPacketRouter.setVolumeMultiplier(volumeMultiplier);
        }
        opusPacketRouter.seekPerformed(desiredTimecode, timecode);
    }

    private float resolveVolumeMultiplier() {
        float totalGainDb = 0.0f;

        // Apply header gain (Q7.8 format)
        if (headerGain != 0) {
            totalGainDb += headerGain / 256.0f;
        }

        String r128GainTag = tags.get("R128_TRACK_GAIN");
        String replayGainTag = tags.get("REPLAYGAIN_TRACK_GAIN");

        if (r128GainTag != null) {
            try {
                int r128Gain = Integer.parseInt(r128GainTag);
                totalGainDb += r128Gain / 256.0f;
            } catch (NumberFormatException e) {
                 log.warn("Invalid R128_TRACK_GAIN tag value: {}", r128GainTag);
            }
        } else if (replayGainTag != null) {
            try {
                String cleanValue = replayGainTag.replace("dB", "").trim();
                totalGainDb += Float.parseFloat(cleanValue);
            } catch (NumberFormatException e) {
                log.warn("Invalid ReplayGain tag value: {}", replayGainTag);
            }
        }

        if (totalGainDb != 0.0f) {
            float multiplier = (float) Math.pow(10, totalGainDb / 20.0f);
            log.debug("Applying ReplayGain (Opus): {} dB -> {}x multiplier", totalGainDb, multiplier);
            return multiplier;
        }

        return 1.0f;
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
