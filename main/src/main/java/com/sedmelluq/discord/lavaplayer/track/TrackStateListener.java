package com.sedmelluq.discord.lavaplayer.track;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

/**
 * Listener of track execution events.
 */
public interface TrackStateListener {
    /**
     * Called when an exception occurs while a track is playing or loading. This is always fatal, but it may have left
     * some data in the audio buffer which can still play until the buffer clears out.
     *
     * @param track     The audio track for which the exception occurred
     * @param exception The exception that occurred
     */
    void onTrackException(AudioTrack track, FriendlyException exception);

    /**
     * Called when an exception occurs while a track is playing or loading. This is always fatal, but it may have left
     * some data in the audio buffer which can still play until the buffer clears out.
     *
     * @param track       The audio track for which the exception occurred
     * @param thresholdMs The wait threshold that was exceeded for this event to trigger
     */
    void onTrackStuck(AudioTrack track, long thresholdMs);

    /**
     * Called once the ReplayGain state of a track is known. Every container resolves ReplayGain before producing its
     * first audio frame, so this always runs ahead of any audio, but necessarily after the track has been handed to
     * the executor (and therefore after the track start event).
     *
     * <p>Fired exactly once per track execution, including for tracks with no ReplayGain data, in which case
     * {@code gainDb} is null. Defaulted to a no-op so existing implementations are unaffected.
     *
     * @param track  The audio track whose ReplayGain state was resolved
     * @param gainDb The gain in decibels being applied, or null if the track has no ReplayGain data
     */
    default void onTrackReplayGainResolved(AudioTrack track, Float gainDb) {
        // Optional for implementations that do not care about ReplayGain.
    }
}
