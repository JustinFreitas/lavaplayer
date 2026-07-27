package com.sedmelluq.discord.lavaplayer.container.matroska;

import com.sedmelluq.discord.lavaplayer.container.common.ReplayGainTools;
import com.sedmelluq.discord.lavaplayer.container.common.OpusPacketRouter;
import com.sedmelluq.discord.lavaplayer.container.matroska.format.MatroskaFileTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Consumes OPUS track data from a matroska file.
 */
public class MatroskaOpusTrackConsumer implements MatroskaTrackConsumer {

    private final MatroskaFileTrack track;
    private final OpusPacketRouter opusPacketRouter;
    private boolean replayGainApplied;
    private Float replayGainDb;

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
                replayGainDb = (float) (20.0 * Math.log10(multiplier));
            }
        }
    }

    private float resolveVolumeMultiplier(Map<String, String> tags, byte[] codecPrivate) {
        float headerGainDb = 0.0f;

        // Header output gain, Q7.8 fixed point, on the same R128 scale as the tags.
        // OpusHead: 8 bytes magic + 1 byte version + 1 byte channels + 2 bytes pre-skip + 4 bytes sample rate + 2 bytes gain
        // Offset 16 (0x10)
        if (codecPrivate != null && codecPrivate.length >= 18) {
            // Little Endian
            short headerGain = (short) ((codecPrivate[16] & 0xFF) | ((codecPrivate[17] & 0xFF) << 8));
            headerGainDb = headerGain / 256.0f;
        }

        return ReplayGainTools.resolveMultiplier(tags, headerGainDb, "Matroska Opus");
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
    public Float getReplayGainDb() {
        return replayGainDb;
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
