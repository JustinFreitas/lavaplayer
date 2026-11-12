package com.sedmelluq.discord.lavaplayer.container.ogg;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/**
 * Audio track which handles an OGG stream.
 */
public class OggAudioTrack extends BaseAudioTrack {
    private static final Logger log = LoggerFactory.getLogger(OggAudioTrack.class);

    private final SeekableInputStream inputStream;
    private volatile boolean replayGainApplied = false;
    private volatile Float replayGainDb = null;

    /**
     * @param trackInfo   Track info
     * @param inputStream Input stream for the OGG stream
     */
    public OggAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream inputStream) {
        super(trackInfo);

        this.inputStream = inputStream;
    }

    @Override
    public void process(final LocalAudioTrackExecutor localExecutor) throws IOException {
        OggPacketInputStream packetInputStream = new OggPacketInputStream(inputStream, false);
        OggTrackBlueprint blueprint = OggTrackLoader.loadTrackBlueprint(packetInputStream);

        log.debug("Starting to play an OGG track {}", getIdentifier());

        if (blueprint == null) {
            throw new IOException("Stream terminated before the first packet.");
        }

        OggTrackHandler initialHandler = blueprint.loadTrackHandler(packetInputStream);
        AtomicReference<OggTrackHandler> currentHandlerRef = new AtomicReference<>(initialHandler);
        AtomicReference<OggTrackBlueprint> currentBlueprintRef = new AtomicReference<>(blueprint);
        AtomicBoolean initialised = new AtomicBoolean(true);

        // Unlike the other containers, OGG only learns its gain once the handler is initialised, so it reports for
        // itself rather than letting the executor report null on entry.
        localExecutor.deferReplayGainReport();

        try {
            initialHandler.initialise(localExecutor.getProcessingContext(), 0, 0);

            Float gainDb = initialHandler.getReplayGainDb();
            applyReplayGain(gainDb);
            localExecutor.notifyReplayGainResolved(gainDb);

            localExecutor.executeProcessingLoop(() -> {
                try {
                    processTrackLoop(packetInputStream, localExecutor, currentHandlerRef, currentBlueprintRef, initialised);
                } catch (IOException e) {
                    throw new FriendlyException("Stream broke when playing OGG track.", SUSPICIOUS, e);
                }
            }, timecode -> {
                OggTrackHandler active = currentHandlerRef.get();
                if (active != null) {
                    active.seekToTimecode(timecode);
                }
            }, true);
        } finally {
            OggTrackHandler active = currentHandlerRef.getAndSet(null);
            if (active != null) {
                try {
                    active.close();
                } catch (IOException e) {
                    log.debug("Failed to close Ogg handler", e);
                }
            }
        }
    }

    private void processTrackLoop(OggPacketInputStream packetInputStream,
                                  LocalAudioTrackExecutor localExecutor,
                                  AtomicReference<OggTrackHandler> currentHandlerRef,
                                  AtomicReference<OggTrackBlueprint> currentBlueprintRef,
                                  AtomicBoolean initialised) throws IOException, InterruptedException {
        OggTrackBlueprint blueprint = currentBlueprintRef.get();
        while (blueprint != null) {
            OggTrackHandler currentHandler = currentHandlerRef.get();
            // fix: only initialise once to avoid resetting the reported playback position
            if (initialised.compareAndSet(false, true)) {
                currentHandler.initialise(localExecutor.getProcessingContext(), 0, 0);

                Float gainDb = currentHandler.getReplayGainDb();
                applyReplayGain(gainDb);

                // Only the first stream in the chain is reported (the executor ignores later calls); chained
                // sub-streams still update replayGainDb for anything reading it directly.
                localExecutor.notifyReplayGainResolved(gainDb);
            }

            currentHandler.provideFrames();
            OggTrackBlueprint nextBlueprint = OggTrackLoader.loadTrackBlueprint(packetInputStream);

            if (nextBlueprint != null) {
                try {
                    currentHandler.close();
                } catch (IOException e) {
                    log.debug("Failed to close chained Ogg handler", e);
                }
                currentBlueprintRef.set(nextBlueprint);
                currentHandlerRef.set(nextBlueprint.loadTrackHandler(packetInputStream));
                initialised.set(false);
            }

            blueprint = nextBlueprint;
        }
    }

    private void applyReplayGain(Float gainDb) {
        if (gainDb != null) {
            this.replayGainApplied = true;
            this.replayGainDb = gainDb;
        } else {
            this.replayGainApplied = false;
            this.replayGainDb = null;
        }
    }

    @Override
    public boolean isReplayGainApplied() {
        return replayGainApplied;
    }

    @Override
    public Float getReplayGainDb() {
        return replayGainDb;
    }
}
