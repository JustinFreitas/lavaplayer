package com.sedmelluq.discord.lavaplayer.container.mp3;

import com.sedmelluq.discord.lavaplayer.container.common.OpusPacketRouter;
import com.sedmelluq.discord.lavaplayer.filter.AudioPipeline;
import com.sedmelluq.discord.lavaplayer.filter.AudioPipelineFactory;
import com.sedmelluq.discord.lavaplayer.filter.PcmFormat;
import com.sedmelluq.discord.lavaplayer.natives.mp3.Mp3Decoder;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.sedmelluq.discord.lavaplayer.natives.mp3.Mp3Decoder.MPEG1_SAMPLES_PER_FRAME;

/**
 * Handles parsing MP3 files, seeking, and sending the decoded frames to the
 * specified frame consumer.
 */
@SuppressWarnings("unused")
public class Mp3TrackProvider implements AudioTrackInfoProvider {
    private static final Logger log = LoggerFactory.getLogger(Mp3TrackProvider.class);

    private static final byte[] IDV3_TAG = new byte[] { 0x49, 0x44, 0x33 };
    private static final int IDV3_FLAG_EXTENDED = 0x40;

    private static final String TITLE_TAG = "TIT2";
    private static final String ARTIST_TAG = "TPE1";
    private static final String ISRC_TAG = "TSRC";
    private static final String USER_TEXT_TAG = "TXXX";

    private static final List<String> knownTextExtensions = Arrays.asList(TITLE_TAG, ARTIST_TAG, ISRC_TAG,
            USER_TEXT_TAG);

    private final AudioProcessingContext context;
    private final SeekableInputStream inputStream;
    private final DataInputStream dataInput;
    private final Mp3Decoder mp3Decoder;
    private final ShortBuffer outputBuffer;
    private final ByteBuffer inputBuffer;
    private final byte[] frameBuffer;
    private final byte[] tagHeaderBuffer;
    private final Mp3FrameReader frameReader;
    private final Map<String, String> tags;
    private String replayGainTxxx;

    private int sampleRate;
    private int channelCount;
    private AudioPipeline downstream;
    private Mp3Seeker seeker;
    private float volumeMultiplier = 1.0f;

    /**
     * @param context     Configuration and output information for processing. May
     *                    be null in case no frames are read, and this
     *                    instance is only used to retrieve information about the
     *                    track.
     * @param inputStream Stream to read the file from
     */
    public Mp3TrackProvider(AudioProcessingContext context, SeekableInputStream inputStream) {
        this.context = context;
        this.inputStream = inputStream;
        this.dataInput = new DataInputStream(inputStream);
        this.outputBuffer = ByteBuffer.allocateDirect((int) MPEG1_SAMPLES_PER_FRAME * 4).order(ByteOrder.nativeOrder())
                .asShortBuffer();
        this.inputBuffer = ByteBuffer.allocateDirect(Mp3Decoder.getMaximumFrameSize());
        this.frameBuffer = new byte[Mp3Decoder.getMaximumFrameSize()];
        this.tagHeaderBuffer = new byte[4];
        this.frameReader = new Mp3FrameReader(inputStream, frameBuffer);
        this.mp3Decoder = new Mp3Decoder();
        this.tags = new HashMap<>();
    }

    /**
     * Parses file headers to find the first MP3 frame and to get the settings for
     * initializing the filter chain.
     *
     * @throws IOException On read error
     */
    public void parseHeaders() throws IOException {
        skipIdv3Tags();

        if (context != null && context.configuration.isReplayGainEnabled()) {
            this.volumeMultiplier = resolveVolumeMultiplier();
        }

        if (!frameReader.scanForFrame(2048, true)) {
            throw new IllegalStateException("File ended before the first frame was found.");
        }

        sampleRate = Mp3Decoder.getFrameSampleRate(frameBuffer, 0);
        channelCount = Mp3Decoder.getFrameChannelCount(frameBuffer, 0);
        downstream = context != null ? AudioPipelineFactory.create(context, new PcmFormat(channelCount, sampleRate))
                : null;

        initialiseSeeker();
    }

    private float resolveVolumeMultiplier() {
        if (replayGainTxxx != null) {
            String normalized = replayGainTxxx.replace('\0', '=');

            if (normalized.toUpperCase().contains("REPLAYGAIN_TRACK_GAIN")) {
                try {
                    int tagIndex = normalized.toUpperCase().indexOf("REPLAYGAIN_TRACK_GAIN");
                    if (tagIndex >= 0) {
                        int dbIndex = normalized.indexOf("dB");
                        if (dbIndex > 0) {
                            String[] parts = normalized.split("=");
                            if (parts.length >= 2) {
                                String val = parts[1].replace("dB", "").trim();
                                float gainDb = Float.parseFloat(val);
                                float multiplier = (float) Math.pow(10, gainDb / 20.0f);
                                log.debug("Applying ReplayGain (MP3): {} dB -> {}x multiplier", gainDb, multiplier);
                                return multiplier;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse ReplayGain from TXXX: {}", replayGainTxxx);
                }
            }
        }
        return 1.0f;
    }

    private void initialiseSeeker() throws IOException {
        long startPosition = frameReader.getFrameStartPosition();
        frameReader.fillFrameBuffer();

        seeker = Mp3XingSeeker.createFromFrame(startPosition, inputStream.getContentLength(), frameBuffer);

        if (seeker == null) {
            if (inputStream.getContentLength() == Units.CONTENT_LENGTH_UNKNOWN) {
                seeker = new Mp3StreamSeeker();
            } else {
                if (context == null) {
                    // Skip meta-frames if this provider is created only for reading metadata.
                    for (int i = 0; Mp3ConstantRateSeeker.isMetaFrame(frameBuffer) && i < 2; i++) {
                        frameReader.nextFrame();
                        frameReader.fillFrameBuffer();
                    }
                }

                seeker = Mp3ConstantRateSeeker.createFromFrame(startPosition, inputStream.getContentLength(),
                        frameBuffer);
            }
        }
    }

    /**
     * Decodes audio frames and sends them to frame consumer
     *
     * @throws InterruptedException When interrupted externally (or for seek/stop).
     */
    public void provideFrames() throws InterruptedException {
        try {
            while (frameReader.fillFrameBuffer()) {
                inputBuffer.clear();
                inputBuffer.put(frameBuffer, 0, frameReader.getFrameSize());
                inputBuffer.flip();

                outputBuffer.clear();
                outputBuffer.limit(channelCount * (int) Mp3Decoder.getSamplesPerFrame(frameBuffer, 0));

                int produced = mp3Decoder.decode(inputBuffer, outputBuffer);

                if (produced > 0) {
                    if (volumeMultiplier != 1.0f) {
                        applyVolume();
                    }
                    downstream.process(outputBuffer);
                }

                frameReader.nextFrame();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void applyVolume() {
        // Output buffer is ShortBuffer
        int limit = outputBuffer.limit();
        OpusPacketRouter.applyVolumeMultiplierToAllFramesInBuffer(limit, outputBuffer, volumeMultiplier);
    }

    /**
     * Seeks to the specified timecode.
     *
     * @param timecode The timecode in milliseconds
     */
    public void seekToTimecode(long timecode) {
        try {
            long frameIndex = seeker.seekAndGetFrameIndex(timecode, inputStream);
            long actualTimecode = frameIndex * MPEG1_SAMPLES_PER_FRAME * 1000 / sampleRate;
            downstream.seekPerformed(timecode, actualTimecode);

            frameReader.nextFrame();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Records seek to the specified timecode without actually seeking.
     *
     * @param timecode       The requested timecode in milliseconds
     * @param actualTimecode The actual timecode in milliseconds
     */
    public void recordSeek(long timecode, long actualTimecode) {
        downstream.seekPerformed(timecode, actualTimecode);
    }

    /**
     * @return True if the track is seekable (false for streams, for example).
     */
    public boolean isSeekable() {
        return seeker.isSeekable();
    }

    /**
     * @return An estimated duration of the file in milliseconds
     */
    public long getDuration() {
        return seeker.getDuration();
    }

    /**
     * Gets an ID3 tag. These are loaded when parsing headers and only for a fixed
     * list of tags.
     *
     * @param tagId The FourCC of the tag
     * @return The value of the tag if present, otherwise null
     */
    public String getIdv3Tag(String tagId) {
        return tags.get(tagId);
    }

    /**
     * Closes resources.
     */
    public void close() {
        if (downstream != null) {
            downstream.close();
        }

        mp3Decoder.close();
    }

    private void skipIdv3Tags() throws IOException {
        byte[] lastTagHeader = new byte[4];

        for (int reads = 0; reads < 3; reads++) {
            dataInput.readFully(lastTagHeader, 0, 3);

            for (int i = 0; i < 3; i++) {
                if (lastTagHeader[i] != IDV3_TAG[i]) {
                    System.arraycopy(lastTagHeader, 0, tagHeaderBuffer, 0, 4);
                    frameReader.appendToScanBuffer(tagHeaderBuffer, 0, 3);
                    return;
                }
            }

            int majorVersion = dataInput.readByte() & 0xFF;
            // Minor version
            dataInput.readByte();

            if (majorVersion < 2 || majorVersion > 5) {
                return;
            }

            int flags = dataInput.readByte() & 0xFF;
            int tagsSize = readSyncProofInteger();

            long tagsEndPosition = inputStream.getPosition() + tagsSize;

            skipExtendedHeader(flags);

            if (majorVersion < 5) {
                parseIdv3Frames(majorVersion, tagsEndPosition);
            }

            inputStream.seek(tagsEndPosition);
        }

        throw new RuntimeException("Read more than 3 IDv3 blocks, file is possibly invalid");
    }

    private int readSyncProofInteger() throws IOException {
        return (dataInput.readByte() & 0xFF) << 21
                | (dataInput.readByte() & 0xFF) << 14
                | (dataInput.readByte() & 0xFF) << 7
                | (dataInput.readByte() & 0xFF);
    }

    private int readSyncProof3ByteInteger() throws IOException {
        return (dataInput.readByte() & 0xFF) << 14
                | (dataInput.readByte() & 0xFF) << 7
                | (dataInput.readByte() & 0xFF);
    }

    private void skipExtendedHeader(int flags) throws IOException {
        if ((flags & IDV3_FLAG_EXTENDED) != 0) {
            int size = readSyncProofInteger();

            inputStream.seek(inputStream.getPosition() + size - 4);
        }
    }

    private void parseIdv3Frames(int version, long tagsEndPosition) throws IOException {
        FrameHeader header;

        while (inputStream.getPosition() + 10 <= tagsEndPosition && (header = readFrameHeader(version)) != null) {
            long nextTagPosition = inputStream.getPosition() + header.size;

            if (header.hasRawFormat() && knownTextExtensions.contains(header.id)) {
                String text = parseIdv3TextContent(header.size);

                if (text != null) {
                    tags.put(header.id, text);

                    if (USER_TEXT_TAG.equals(header.id) && text.toUpperCase().contains("REPLAYGAIN_TRACK_GAIN")) {
                        replayGainTxxx = text;
                    }
                }
            }

            inputStream.seek(nextTagPosition);
        }
    }

    private String parseIdv3TextContent(int size) throws IOException {
        int encoding = dataInput.readByte() & 0xFF;

        byte[] data = new byte[size - 1];
        dataInput.readFully(data);

        boolean shortTerminator = data.length > 0 && data[data.length - 1] == 0;
        boolean wideTerminator = data.length > 1 && data[data.length - 2] == 0 && shortTerminator;

        return switch (encoding) {
            case 0 -> new String(data, 0, size - (shortTerminator ? 2 : 1), StandardCharsets.ISO_8859_1);
            case 1 -> new String(data, 0, size - (wideTerminator ? 3 : 1), StandardCharsets.UTF_16);
            case 2 -> new String(data, 0, size - (wideTerminator ? 3 : 1), StandardCharsets.UTF_16BE);
            case 3 -> new String(data, 0, size - (shortTerminator ? 2 : 1), StandardCharsets.UTF_8);
            default -> null;
        };
    }

    private String readId3v22TagName() throws IOException {
        dataInput.readFully(tagHeaderBuffer, 0, 3);

        if (tagHeaderBuffer[0] == 0) {
            return null;
        }

        String shortName = new String(tagHeaderBuffer, 0, 3, StandardCharsets.ISO_8859_1);

        if ("TT2".equals(shortName)) {
            return "TIT2";
        } else if ("TP1".equals(shortName)) {
            return "TPE1";
        } else {
            return shortName;
        }
    }

    private String readTagName() throws IOException {
        dataInput.readFully(tagHeaderBuffer, 0, 4);

        if (tagHeaderBuffer[0] == 0) {
            return null;
        }

        return new String(tagHeaderBuffer, 0, 4, StandardCharsets.ISO_8859_1);
    }

    private FrameHeader readFrameHeader(int version) throws IOException {
        if (version == 2) {
            String tagName = readId3v22TagName();

            if (tagName != null) {
                return new FrameHeader(tagName, readSyncProof3ByteInteger(), 0);
            }
        } else {
            String tagName = readTagName();

            if (tagName != null) {
                int size = version == 3 ? dataInput.readInt() : readSyncProofInteger();
                return new FrameHeader(tagName, size, dataInput.readUnsignedShort());
            }
        }

        return null;
    }

    @Override
    public String getTitle() {
        return getIdv3Tag(TITLE_TAG);
    }

    @Override
    public String getAuthor() {
        return getIdv3Tag(ARTIST_TAG);
    }

    @Override
    public Long getLength() {
        return getDuration();
    }

    @Override
    public String getIdentifier() {
        return null;
    }

    @Override
    public String getUri() {
        return null;
    }

    @Override
    public String getArtworkUrl() {
        return null;
    }

    @Override
    public String getISRC() {
        return getIdv3Tag(ISRC_TAG);
    }

    private record FrameHeader(String id, int size, int flags) {

        private boolean hasRawFormat() {
                boolean compression = (flags & 0x0008) != 0;
                boolean encryption = (flags & 0x0004) != 0;
                boolean unsynchronization = (flags & 0x0002) != 0;
                boolean dataLengthIndicator = (flags & 0x0001) != 0;
                return !compression && !encryption && !unsynchronization && !dataLengthIndicator;
            }
        }
}
