package com.sedmelluq.discord.lavaplayer.natives.aac

import spock.lang.Specification

import java.nio.ByteBuffer

class AacDecoderSpec extends Specification {

    def "AacDecoder methods reject zero native handle with IllegalStateException"() {
        given:
        def decoder = new AacDecoder()
        // Simulate an uninitialized / failed reset handle after releasing original native resource cleanly
        long originalHandle = decoder.instance.getAndSet(0L)
        if (originalHandle != 0L) {
            decoder.library.destroy(originalHandle)
        }

        when:
        decoder.configure(AacDecoder.AAC_LC, 44100, 2)

        then:
        thrown(IllegalStateException)

        when:
        decoder.fill(ByteBuffer.allocateDirect(16))

        then:
        thrown(IllegalStateException)

        when:
        decoder.decode(ByteBuffer.allocateDirect(64).asShortBuffer(), false)

        then:
        thrown(IllegalStateException)

        when:
        decoder.resolveStreamInfo()

        then:
        thrown(IllegalStateException)

        cleanup:
        decoder.close()
    }
}
