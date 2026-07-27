package com.sedmelluq.discord.lavaplayer.player.event;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

/**
 * Event that is fired once the ReplayGain state of a track is known.
 *
 * <p>{@link TrackStartEvent} is dispatched before the track is handed to the executor, so at that point nothing has
 * been decoded yet and {@link AudioTrack#isReplayGainApplied()} still reports its initial {@code false}. Every
 * container resolves ReplayGain before producing its first audio frame, and this event is fired at that moment, so a
 * listener can act on the real value while it is still ahead of any audio.
 *
 * <p>This is fired exactly once per track execution, including for tracks that carry no ReplayGain data at all — in
 * that case {@link #gainDb} is null. Listeners that need to distinguish "no ReplayGain" from "not yet known" should
 * rely on this event rather than polling the track.
 */
public class TrackReplayGainResolvedEvent extends AudioEvent {
    /**
     * Audio track whose ReplayGain state was resolved
     */
    public final AudioTrack track;

    /**
     * The gain in decibels that is being applied to this track, or null if the track has no ReplayGain data (or
     * ReplayGain is disabled in the configuration).
     */
    public final Float gainDb;

    /**
     * @param player Audio player
     * @param track  Audio track whose ReplayGain state was resolved
     * @param gainDb Applied gain in decibels, or null if none
     */
    public TrackReplayGainResolvedEvent(AudioPlayer player, AudioTrack track, Float gainDb) {
        super(player);
        this.track = track;
        this.gainDb = gainDb;
    }
}
