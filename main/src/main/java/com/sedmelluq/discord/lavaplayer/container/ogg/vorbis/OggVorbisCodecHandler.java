package com.sedmelluq.discord.lavaplayer.container.ogg.vorbis;

import com.sedmelluq.discord.lavaplayer.container.ogg.*;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.DirectBufferStreamBroker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public class OggVorbisCodecHandler implements OggCodecHandler {
    private static final int VORBIS_IDENTIFIER = ByteBuffer.wrap(new byte[] { 0x01, 'v', 'o', 'r' }).getInt();

    // These are arbitrary - there is no limit specified in Vorbis specification,
    // Opus limit used as reference.
    private static final int MAX_COMMENTS_SAVED_LENGTH = 1024 * 128; // 128 KB
    private static final int MAX_COMMENTS_READ_LENGTH = 1024 * 1024 * 120; // 120 MB

    private static final byte[] COMMENT_PACKET_START = new byte[] { 0x03, 'v', 'o', 'r', 'b', 'i', 's' };

    @Override
    public boolean isMatchingIdentifier(int identifier) {
        return identifier == VORBIS_IDENTIFIER;
    }

    @Override
    public int getMaximumFirstPacketLength() {
        return 64;
    }

    @Override
    public OggTrackBlueprint loadBlueprint(OggPacketInputStream stream, DirectBufferStreamBroker broker)
            throws IOException {
        byte[] infoPacket = broker.extractBytes();

        // Load comments without skipping to capture tags
        loadCommentsHeader(stream, broker);
        Map<String, String> tags = Collections.emptyMap();

        ByteBuffer commentsPacket = broker.getBuffer();
        byte[] packetStart = new byte[COMMENT_PACKET_START.length];
        if (commentsPacket.remaining() >= packetStart.length) {
            commentsPacket.mark();
            commentsPacket.get(packetStart);
            if (Arrays.equals(packetStart, COMMENT_PACKET_START)) {
                tags = VorbisCommentParser.parse(commentsPacket, broker.isTruncated());
            }
            commentsPacket.reset();
        }

        ByteBuffer infoBuffer = ByteBuffer.wrap(infoPacket);
        int sampleRate = Integer.reverseBytes(infoBuffer.getInt(12));
        List<OggSeekPoint> seekPointList = stream.createSeekTable(sampleRate);
        if (seekPointList != null)
            stream.setSeekPoints(seekPointList);
        return new Blueprint(sampleRate, infoPacket, broker, tags);
    }

    @Override
    public OggMetadata loadMetadata(OggPacketInputStream stream, DirectBufferStreamBroker broker) throws IOException {
        byte[] infoPacket = broker.extractBytes();
        loadCommentsHeader(stream, broker);

        ByteBuffer commentsPacket = broker.getBuffer();
        byte[] packetStart = new byte[COMMENT_PACKET_START.length];
        commentsPacket.get(packetStart);

        if (!Arrays.equals(packetStart, COMMENT_PACKET_START)) {
            return OggMetadata.EMPTY;
        }

        ByteBuffer infoBuffer = ByteBuffer.wrap(infoPacket);
        int sampleRate = Integer.reverseBytes(infoBuffer.getInt(12));
        OggStreamSizeInfo sizeInfo = stream.seekForSizeInfo(sampleRate);

        return new OggMetadata(
                VorbisCommentParser.parse(commentsPacket, broker.isTruncated()),
                sizeInfo != null ? sizeInfo.getDuration() : Units.DURATION_MS_UNKNOWN);
    }

    private void loadCommentsHeader(OggPacketInputStream stream, DirectBufferStreamBroker broker)
            throws IOException {

        if (!stream.startNewPacket()) {
            throw new IllegalStateException("No comments packet in track.");
        } else if (!broker.consumeNext(stream, MAX_COMMENTS_SAVED_LENGTH, MAX_COMMENTS_READ_LENGTH)) {
            if (!stream.isPacketComplete()) {
                throw new IllegalStateException("Vorbis comments header packet longer than allowed.");
            }
        }
    }

    private record Blueprint(int sampleRate, byte[] infoPacket, DirectBufferStreamBroker broker,
                             Map<String, String> tags) implements OggTrackBlueprint {

        @Override
            public OggTrackHandler loadTrackHandler(OggPacketInputStream stream) {
                return new OggVorbisTrackHandler(infoPacket, stream, broker, tags);
            }
        }
}
