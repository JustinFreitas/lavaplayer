package com.sedmelluq.discord.lavaplayer.natives.vorbis;

import com.sedmelluq.discord.lavaplayer.natives.ConnectorNativeLibLoader;

import java.nio.ByteBuffer;

class VorbisDecoderLibrary {
    private VorbisDecoderLibrary() {

    }

    static VorbisDecoderLibrary getInstance() {
        ConnectorNativeLibLoader.loadConnectorLibrary();
        return new VorbisDecoderLibrary();
    }

    native long create();

    native void destroy(long instance);

    /**
     * @return 1 (JNI_TRUE) on success. Any other value is a failure: 0, or a libvorbis error code
     * combined with a flag indicating which header failed. Declared as int because the native
     * implementation returns a jint - declaring it boolean would make the JVM keep only the lowest
     * byte, which is non-zero for most libvorbis error codes, silently turning failures into successes.
     */
    native int initialise(long instance, ByteBuffer infoBuffer, int infoOffset, int infoLength,
                          ByteBuffer setupBuffer, int setupOffset, int setupLength);

    native int getChannelCount(long instance);

    native int input(long instance, ByteBuffer directBuffer, int offset, int length);

    native int output(long instance, float[][] channels, int length);
}
