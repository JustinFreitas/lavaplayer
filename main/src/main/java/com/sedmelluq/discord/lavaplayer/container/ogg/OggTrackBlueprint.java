package com.sedmelluq.discord.lavaplayer.container.ogg;

@SuppressWarnings("unused")
public interface OggTrackBlueprint {
    OggTrackHandler loadTrackHandler(OggPacketInputStream stream);

    @SuppressWarnings("unused")
    int sampleRate();
}
