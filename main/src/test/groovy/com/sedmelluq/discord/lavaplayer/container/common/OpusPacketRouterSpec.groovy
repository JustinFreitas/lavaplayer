package com.sedmelluq.discord.lavaplayer.container.common

import spock.lang.Specification
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpusPacketRouterSpec extends Specification {
    def "applyVolumeMultiplierToAllFramesInBuffer applies volume and clamps"() {
        given:
        def buffer = ByteBuffer.allocateDirect(10).order(ByteOrder.nativeOrder()).asShortBuffer()
        buffer.put(0, (short) 1000)
        buffer.put(1, (short) -1000)
        buffer.put(2, (short) 20000)
        buffer.put(3, (short) -20000)
        buffer.put(4, (short) 0)
        
        when:
        OpusPacketRouter.applyVolumeMultiplierToAllFramesInBuffer(5, buffer, 2.0f)
        
        then:
        buffer.get(0) == (short) 2000
        buffer.get(1) == (short) -2000
        buffer.get(2) == (short) 32767 // Clamped from 40000
        buffer.get(3) == (short) -32768 // Clamped from -40000
        buffer.get(4) == (short) 0
    }
    
    def "applyVolumeMultiplierToAllFramesInBuffer respects length"() {
        given:
        def buffer = ByteBuffer.allocateDirect(10).order(ByteOrder.nativeOrder()).asShortBuffer()
        buffer.put(0, (short) 1000)
        buffer.put(1, (short) 1000)
        
        when:
        OpusPacketRouter.applyVolumeMultiplierToAllFramesInBuffer(1, buffer, 0.5f)
        
        then:
        buffer.get(0) == (short) 500
        buffer.get(1) == (short) 1000 // Untouched
    }
}
