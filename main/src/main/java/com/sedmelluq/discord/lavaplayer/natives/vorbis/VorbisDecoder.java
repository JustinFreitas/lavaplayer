package com.sedmelluq.discord.lavaplayer.natives.vorbis;

import com.sedmelluq.lava.common.natives.NativeResourceHolder;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;

/**
 * A wrapper around the native methods of AacDecoder, which uses libvorbis native library.
 */
public class VorbisDecoder extends NativeResourceHolder {
    private final VorbisDecoderLibrary library;
    private final long instance;
    private int channelCount = 0;

    /**
     * Create an instance.
     */
    public VorbisDecoder() {
        library = VorbisDecoderLibrary.getInstance();
        instance = library.create();
        registerCleanup(new Destroyer(library, instance));
    }

    private static class Destroyer implements Runnable {
        private final VorbisDecoderLibrary library;
        private final long instance;

        Destroyer(VorbisDecoderLibrary library, long instance) {
            this.library = library;
            this.instance = instance;
        }

        @Override
        public void run() {
            library.destroy(instance);
        }
    }

    /**
     * Initialize the decoder by passing in identification and setup header data. See
     * https://xiph.org/vorbis/doc/Vorbis_I_spec.html#x1-170001.2.6 for definitions. The comment header is not required as
     * it is not actually used for decoding setup.
     *
     * @param infoBuffer  Identification header, including the 'vorbis' string.
     * @param setupBuffer Setup header (also known as codebook header), including the 'vorbis' string.
     */
    public void initialise(ByteBuffer infoBuffer, ByteBuffer setupBuffer) {
        checkNotReleased();

        if (!infoBuffer.isDirect() || !setupBuffer.isDirect()) {
            throw new IllegalArgumentException("Buffer argument must be a direct buffer.");
        }

        int result;
        try {
            result = library.initialise(instance, infoBuffer, infoBuffer.position(), infoBuffer.remaining(), setupBuffer,
                setupBuffer.position(), setupBuffer.remaining());

            if (result != 1) {
                throw new IllegalStateException("Could not initialise library, error " + result + ".");
            }

            channelCount = library.getChannelCount(instance);
        } finally {
            // Keeps this object reachable for the duration of the native calls, otherwise the Cleaner
            // may destroy the native decoder while it is still executing.
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Get the number of channels, valid only after initialisation.
     *
     * @return Number of channels
     */
    public int getChannelCount() {
        return channelCount;
    }

    /**
     * Provide input for the decoder
     *
     * @param buffer Buffer with the input
     */
    public void input(ByteBuffer buffer) {
        checkNotReleased();

        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer argument must be a direct buffer.");
        }

        int result;
        try {
            result = library.input(instance, buffer, buffer.position(), buffer.remaining());
        } finally {
            Reference.reachabilityFence(this);
        }

        if (result != 0) {
            throw new IllegalStateException("Passing input failed with error " + result + ".");
        }

        buffer.position(buffer.limit());
    }

    /**
     * Fetch output from the decoder
     *
     * @param channels Channel buffers to fetch the output to
     * @return The number of samples fetched for each channel
     */
    public int output(float[][] channels) {
        checkNotReleased();

        if (channels.length != channelCount) {
            throw new IllegalStateException("Invalid channel float buffer length");
        }

        int result;
        try {
            result = library.output(instance, channels, channels[0].length);
        } finally {
            Reference.reachabilityFence(this);
        }

        if (result < 0) {
            throw new IllegalStateException("Retrieving output failed");
        }

        return result;
    }


}
