package com.sedmelluq.discord.lavaplayer.tools.io

import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider

/**
 * A minimal byte-array-backed SeekableInputStream for feeding hand-built container header
 * fixtures to header/metadata parsers under test, without needing a real file or network stream.
 */
class ByteArraySeekableInputStream extends SeekableInputStream {
    private final byte[] data
    private int position = 0

    ByteArraySeekableInputStream(byte[] data) {
        super(data.length, Long.MAX_VALUE)
        this.data = data
    }

    @Override
    long getPosition() {
        return position
    }

    @Override
    protected void seekHard(long newPosition) throws IOException {
        position = (int) newPosition
    }

    @Override
    boolean canSeekHard() {
        return true
    }

    @Override
    List<AudioTrackInfoProvider> getTrackInfoProviders() {
        return Collections.emptyList()
    }

    @Override
    int read() throws IOException {
        return position < data.length ? (data[position++] & 0xFF) : -1
    }

    @Override
    int read(byte[] b, int off, int len) throws IOException {
        if (position >= data.length) {
            return -1
        }
        int available = Math.min(len, data.length - position)
        System.arraycopy(data, position, b, off, available)
        position += available
        return available
    }

    @Override
    long skip(long n) throws IOException {
        int available = (int) Math.min(n, data.length - position)
        position += available
        return available
    }
}
