package com.sedmelluq.discord.lavaplayer.filter.volume;

import java.nio.ShortBuffer;

/**
 * Class used to apply a volume level to short PCM buffers
 */
public class PcmVolumeProcessor {
    private int currentVolume = -1;
    private int integerMultiplier;

    /**
     * @param initialVolume Initial volume level (only useful for getLastVolume() as specified with each call)
     */
    public PcmVolumeProcessor(int initialVolume) {
        setupMultipliers(initialVolume);
    }

    /**
     * @return Last volume level used with this processor
     */
    public int getLastVolume() {
        return currentVolume;
    }

    /**
     * @param lastVolume Value to explicitly set for the return value of getLastVolume()
     */
    public void setLastVolume(int lastVolume) {
        currentVolume = lastVolume;
    }

    /**
     * @param initialVolume The input volume of the samples
     * @param targetVolume  The target volume of the samples
     * @param buffer        The buffer containing the samples
     */
    public void applyVolume(int initialVolume, int targetVolume, ShortBuffer buffer) {
        if (initialVolume != 100 && initialVolume != 0) {
            setupMultipliers(initialVolume);
            unapplyCurrentVolume(buffer);
        }

        setupMultipliers(targetVolume);
        applyCurrentVolume(buffer);
    }

    private void setupMultipliers(int activeVolume) {
        if (currentVolume != activeVolume) {
            currentVolume = activeVolume;

            if (activeVolume <= 150) {
                float floatMultiplier = (float) Math.tan(activeVolume * 0.0079f);
                integerMultiplier = (int) (floatMultiplier * 10000);
            } else {
                integerMultiplier = 24621 * activeVolume / 150;
            }
        }
    }

    private void applyCurrentVolume(ShortBuffer buffer) {
        if (currentVolume == 100) {
            return;
        }

        int startPosition = buffer.position();
        int length = buffer.limit() - startPosition;
        
        short[] localBuffer = new short[length];
        buffer.get(localBuffer);

        for (int i = 0; i < length; i++) {
            int value = localBuffer[i] * integerMultiplier / 10000;
            localBuffer[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        }

        buffer.position(startPosition);
        buffer.put(localBuffer);
        buffer.position(startPosition);
    }

    private void unapplyCurrentVolume(ShortBuffer buffer) {
        if (integerMultiplier == 0) {
            return;
        }

        int startPosition = buffer.position();
        int length = buffer.limit() - startPosition;
        
        short[] localBuffer = new short[length];
        buffer.get(localBuffer);

        for (int i = 0; i < length; i++) {
            int value = localBuffer[i] * 10000 / integerMultiplier;
            localBuffer[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        }

        buffer.position(startPosition);
        buffer.put(localBuffer);
        buffer.position(startPosition);
    }
}
