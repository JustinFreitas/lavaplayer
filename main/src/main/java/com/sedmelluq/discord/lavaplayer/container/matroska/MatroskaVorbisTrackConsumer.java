package com.sedmelluq.discord.lavaplayer.container.matroska;

import com.sedmelluq.discord.lavaplayer.container.matroska.format.MatroskaFileTrack;
import com.sedmelluq.discord.lavaplayer.container.matroska.format.MatroskaFileTrack.AudioDetails;
import com.sedmelluq.discord.lavaplayer.filter.AudioPipeline;
import com.sedmelluq.discord.lavaplayer.filter.AudioPipelineFactory;
import com.sedmelluq.discord.lavaplayer.filter.PcmFormat;
import com.sedmelluq.discord.lavaplayer.natives.vorbis.VorbisDecoder;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Consumes Vorbis track data from a matroska file.
 */
public class MatroskaVorbisTrackConsumer implements MatroskaTrackConsumer {
    private static final Logger log = LoggerFactory.getLogger(MatroskaVorbisTrackConsumer.class);
    private static final int PCM_BUFFER_SIZE = 4096;
    private static final int COPY_BUFFER_SIZE = 256;

    private final MatroskaFileTrack track;
    private final VorbisDecoder decoder;
    private final byte[] copyBuffer;
    private final AudioPipeline downstream;
    private ByteBuffer inputBuffer;
    private float[][] channelPcmBuffers;
    private float volumeMultiplier = 1.0f;

    /**
     * @param context Configuration and output information for processing
     * @param track   The associated matroska track
     * @param tags    Tags associated with the file or track
     */
    public MatroskaVorbisTrackConsumer(AudioProcessingContext context, MatroskaFileTrack track, Map<String, String> tags) {

        this.track = track;
        this.decoder = new VorbisDecoder();
        this.copyBuffer = new byte[COPY_BUFFER_SIZE];

        AudioDetails audioTrack = fillMissingDetails(track.audio, track.codecPrivate);
        this.downstream = AudioPipelineFactory.create(context,
            new PcmFormat(audioTrack.channels, (int) audioTrack.samplingFrequency));

        if (context.configuration.isReplayGainEnabled()) {
            this.volumeMultiplier = resolveVolumeMultiplier(tags);
        }
    }

    private float resolveVolumeMultiplier(Map<String, String> tags) {
        float totalGainDb = 0.0f;

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
            log.debug("Applying ReplayGain (Matroska Vorbis): {} dB -> {}x multiplier", totalGainDb, multiplier);
            return multiplier;
        }

        return 1.0f;
    }

    @Override
    public MatroskaFileTrack getTrack() {
        return track;
    }

    @Override
    public void initialise() {
        ByteBuffer directPrivateData = ByteBuffer.allocateDirect(track.codecPrivate.length);

        directPrivateData.put(track.codecPrivate);
        directPrivateData.flip();

        try {
            int lengthInfoSize = directPrivateData.get();

            if (lengthInfoSize != 2) {
                throw new IllegalStateException("Unexpected lacing count.");
            }

            int firstHeaderSize = readLacingValue(directPrivateData);
            int secondHeaderSize = readLacingValue(directPrivateData);

            ByteBuffer infoBuffer = directPrivateData.duplicate();
            infoBuffer.limit(infoBuffer.position() + firstHeaderSize);

            directPrivateData.position(directPrivateData.position() + firstHeaderSize + secondHeaderSize);

            decoder.initialise(infoBuffer, directPrivateData);

            channelPcmBuffers = new float[decoder.getChannelCount()][];

            for (int i = 0; i < channelPcmBuffers.length; i++) {
                channelPcmBuffers[i] = new float[PCM_BUFFER_SIZE];
            }
        } catch (Exception e) {
            throw new RuntimeException("Reading Vorbis header failed.", e);
        }
    }

    private static int readLacingValue(ByteBuffer buffer) {
        int value = 0;
        int current;

        do {
            current = buffer.get() & 0xFF;
            value += current;
        } while (current == 255);

        return value;
    }

    private static AudioDetails fillMissingDetails(AudioDetails details, byte[] headers) {
        if (details.channels != 0) {
            return details;
        }

        ByteBuffer buffer = ByteBuffer.wrap(headers);
        readLacingValue(buffer); // first header size
        readLacingValue(buffer); // second header size

        buffer.getInt(); // vorbis version
        int channelCount = buffer.get() & 0xFF;

        return new AudioDetails(details.samplingFrequency, details.outputSamplingFrequency, channelCount, details.bitDepth);
    }

    @Override
    public void seekPerformed(long requestedTimecode, long providedTimecode) {
        downstream.seekPerformed(requestedTimecode, providedTimecode);
    }

    @Override
    public void flush() throws InterruptedException {
        downstream.flush();
    }

    private ByteBuffer getDirectBuffer(int size) {
        if (inputBuffer == null || inputBuffer.capacity() < size) {
            inputBuffer = ByteBuffer.allocateDirect(size * 3 / 2);
        }

        inputBuffer.clear();
        return inputBuffer;
    }

    private ByteBuffer getAsDirectBuffer(ByteBuffer data) {
        ByteBuffer buffer = getDirectBuffer(data.remaining());

        while (data.remaining() > 0) {
            int chunk = Math.min(copyBuffer.length, data.remaining());
            data.get(copyBuffer, 0, chunk);
            buffer.put(copyBuffer, 0, chunk);
        }

        buffer.flip();
        return buffer;
    }

    @Override
    public void consume(ByteBuffer data) throws InterruptedException {
        ByteBuffer directBuffer = getAsDirectBuffer(data);
        decoder.input(directBuffer);

        int output;

        do {
            output = decoder.output(channelPcmBuffers);

            if (output > 0) {
                if (volumeMultiplier != 1.0f) {
                    applyVolume(output);
                }
                downstream.process(channelPcmBuffers, 0, output);
            }
        } while (output == PCM_BUFFER_SIZE);
    }

    private void applyVolume(int outputSize) {
        for (float[] channel : channelPcmBuffers) {
            for (int i = 0; i < outputSize; i++) {
                channel[i] *= volumeMultiplier;
            }
        }
    }

    @Override
    public void close() {
        downstream.close();
        decoder.close();
    }
}
