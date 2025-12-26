package com.sedmelluq.discord.lavaplayer.container.flac;

import com.sedmelluq.discord.lavaplayer.container.flac.frame.FlacFrameReader;
import com.sedmelluq.discord.lavaplayer.filter.AudioPipeline;
import com.sedmelluq.discord.lavaplayer.filter.AudioPipelineFactory;
import com.sedmelluq.discord.lavaplayer.filter.PcmFormat;
import com.sedmelluq.discord.lavaplayer.tools.io.BitStreamReader;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * A provider of audio frames from a FLAC track.
 */
@SuppressWarnings("unused")
public class FlacTrackProvider {
    private static final Logger log = LoggerFactory.getLogger(FlacTrackProvider.class);

    private final FlacTrackInfo info;
    private final SeekableInputStream inputStream;
    private final AudioPipeline downstream;
    private final BitStreamReader bitStreamReader;
    private final int[] decodingBuffer;
    private final int[][] rawSampleBuffers;
    private final short[][] sampleBuffers;
    private final float volumeMultiplier;

    public float getVolumeMultiplier() {
        return volumeMultiplier;
    }

    /**
     * @param context     Configuration and output information for processing
     * @param info        Track information from FLAC metadata
     * @param inputStream Input stream to use
     */
    @SuppressWarnings("unused")
    public FlacTrackProvider(AudioProcessingContext context, FlacTrackInfo info, SeekableInputStream inputStream) {
        this.info = info;
        this.inputStream = inputStream;
        this.downstream = AudioPipelineFactory.create(context,
                new PcmFormat(info.stream.channelCount, info.stream.sampleRate));
        this.bitStreamReader = new BitStreamReader(inputStream);
        this.decodingBuffer = new int[FlacFrameReader.TEMPORARY_BUFFER_SIZE];
        this.rawSampleBuffers = new int[info.stream.channelCount][];
        this.sampleBuffers = new short[info.stream.channelCount][];

        for (int i = 0; i < rawSampleBuffers.length; i++) {
            rawSampleBuffers[i] = new int[info.stream.maximumBlockSize];
            sampleBuffers[i] = new short[info.stream.maximumBlockSize];
        }

        this.volumeMultiplier = resolveVolumeMultiplier(context, info);
    }

    @SuppressWarnings("unused")
    private float resolveVolumeMultiplier(AudioProcessingContext context, FlacTrackInfo info) {
        if (!context.configuration.isReplayGainEnabled()) {
            return 1.0f;
        }

        String replayGainTag = info.tags.get("REPLAYGAIN_TRACK_GAIN");
        if (replayGainTag == null) {
            return 1.0f;
        }

        try {
            // Tag format example: "-5.0 dB" or "+2.5 dB"
            String cleanValue = replayGainTag.replace("dB", "").trim();
            float gainDb = Float.parseFloat(cleanValue);
            float multiplier = (float) Math.pow(10, gainDb / 20.0f);
            log.debug("Applying ReplayGain: {} dB -> {}x multiplier", gainDb, multiplier);
            return multiplier;
        } catch (NumberFormatException e) {
            log.warn("Invalid ReplayGain tag value: {}", replayGainTag);
            return 1.0f;
        }
    }

    /**
     * Decodes audio frames and sends them to frame consumer
     *
     * @throws InterruptedException When interrupted externally (or for seek/stop).
     */
    public void provideFrames() throws InterruptedException {
        try {
            int sampleCount;

            while ((sampleCount = readFlacFrame()) != 0) {
                if (volumeMultiplier != 1.0f) {
                    applyVolume(sampleCount);
                }
                downstream.process(sampleBuffers, 0, sampleCount);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void applyVolume(int sampleCount) {
        for (int channel = 0; channel < info.stream.channelCount; channel++) {
            short[] buffer = sampleBuffers[channel];
            for (int i = 0; i < sampleCount; i++) {
                int sample = buffer[i];
                // Apply gain and clamp to a 16-bit signed integer range
                int newValue = (int) (sample * volumeMultiplier);
                if (newValue > Short.MAX_VALUE) {
                    newValue = Short.MAX_VALUE;
                } else if (newValue < Short.MIN_VALUE) {
                    newValue = Short.MIN_VALUE;
                }
                buffer[i] = (short) newValue;
            }
        }
    }

    private int readFlacFrame() throws IOException {
        return FlacFrameReader.readFlacFrame(inputStream, bitStreamReader, info.stream, rawSampleBuffers, sampleBuffers,
                decodingBuffer);
    }

    /**
     * Seeks to the specified timecode.
     *
     * @param timecode The timecode in milliseconds
     */
    public void seekToTimecode(long timecode) {
        try {
            FlacSeekPoint seekPoint = findSeekPointForTime(timecode);
            inputStream.seek(info.firstFramePosition + seekPoint.byteOffset);
            downstream.seekPerformed(timecode, seekPoint.sampleIndex * 1000 / info.stream.sampleRate);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private FlacSeekPoint findSeekPointForTime(long timecode) {
        if (info.seekPointCount == 0) {
            return new FlacSeekPoint(0, 0, 0);
        }

        long targetSampleIndex = timecode * info.stream.sampleRate / 1000L;
        return binarySearchSeekPoints(info.seekPoints, info.seekPointCount, targetSampleIndex);
    }

    private FlacSeekPoint binarySearchSeekPoints(FlacSeekPoint[] seekPoints, int length, long targetSampleIndex) {
        int low = 0;
        int high = length - 1;

        while (high > low) {
            int mid = (low + high + 1) / 2;

            if (info.seekPoints[mid].sampleIndex > targetSampleIndex) {
                high = mid - 1;
            } else {
                low = mid;
            }
        }

        return seekPoints[low];
    }

    /**
     * Free all resources associated with processing the track.
     */
    public void close() {
        downstream.close();
    }
}
