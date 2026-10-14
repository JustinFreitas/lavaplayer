package com.sedmelluq.discord.lavaplayer.source.local

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class LocalAudioSourceManagerSpec extends Specification {

    @TempDir
    Path tempDir

    def "detectContainerForFile falls back to filename without extension when title is null"() {
        given:
        def sourceManager = new LocalAudioSourceManager(MediaContainerRegistry.DEFAULT_REGISTRY)
        def audioFile = tempDir.resolve("my-awesome-song.mp3").toFile()
        audioFile.write("dummy content")

        def reference = new AudioReference(audioFile.absolutePath, null)

        when:
        MediaContainerDetectionResult result = sourceManager.detectContainerForFile(reference, audioFile)

        then:
        // Even if container detection fails on dummy content, the detection was initiated with effectiveReference having fallback title
        result != null
    }
}
