package com.sedmelluq.discord.lavaplayer.container.matroska;

import com.sedmelluq.discord.lavaplayer.container.common.OpusPacketRouter;
import com.sedmelluq.discord.lavaplayer.container.matroska.format.MatroskaFileTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Consumes OPUS track data from a matroska file.
 */
public class MatroskaOpusTrackConsumer implements MatroskaTrackConsumer {
    private static final Logger log = LoggerFactory.getLogger(MatroskaOpusTrackConsumer.class);

    private final MatroskaFileTrack track;
    private final OpusPacketRouter opusPacketRouter;
    private boolean replayGainApplied;

    /**
     * @param context Configuration and output information for processing
     * @param track   The associated matroska track
     * @param tags    Tags associated with the file or track
     */
    public MatroskaOpusTrackConsumer(AudioProcessingContext context, MatroskaFileTrack track, Map<String, String> tags) {
        this.track = track;
        this.opusPacketRouter = new OpusPacketRouter(context, (int) track.audio.samplingFrequency, track.audio.channels);

        if (context.configuration.isReplayGainEnabled()) {
            float multiplier = resolveVolumeMultiplier(tags, track.codecPrivate);
            if (multiplier != 1.0f) {
                opusPacketRouter.setVolumeMultiplier(multiplier);
                replayGainApplied = true;
            }
        }
    }

    private float resolveVolumeMultiplier(Map<String, String> tags, byte[] codecPrivate) {
        float totalGainDb = 0.0f;

        // Apply header gain (Q7.8 format)
        // OpusHead: 8 bytes magic + 1 byte version + 1 byte channels + 2 bytes pre-skip + 4 bytes sample rate + 2 bytes gain
        // Offset 16 (0x10)
        if (codecPrivate != null && codecPrivate.length >= 18) {
            // Little Endian
            short headerGain = (short) ((codecPrivate[16] & 0xFF) | ((codecPrivate[17] & 0xFF) << 8));
            if (headerGain != 0) {
                totalGainDb += headerGain / 256.0f;
            }
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
            log.debug("Applying ReplayGain (Matroska Opus): {} dB -> {}x multiplier", totalGainDb, multiplier);
            return multiplier;
        }

        return 1.0f;
    }

    @Override
    public MatroskaFileTrack getTrack() {
        return track;
    }

    @Override
    public boolean isReplayGainApplied() {
        return replayGainApplied;
    }

    @Override
    public void initialise() {
        // Nothing to do here
    }

    @Override
    public void seekPerformed(long requestedTimecode, long providedTimecode) {
        opusPacketRouter.seekPerformed(requestedTimecode, providedTimecode);
    }

    @Override
    public void flush() throws InterruptedException {
        opusPacketRouter.flush();
    }

    @Override
    public void consume(ByteBuffer data) throws InterruptedException {
        opusPacketRouter.process(data);
    }

    @Override
    public void close() {
        opusPacketRouter.close();
    }
}
