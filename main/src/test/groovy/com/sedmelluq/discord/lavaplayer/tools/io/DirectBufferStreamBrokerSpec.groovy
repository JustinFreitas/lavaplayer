package com.sedmelluq.discord.lavaplayer.tools.io

import spock.lang.Specification

import java.io.ByteArrayInputStream
import java.util.concurrent.ThreadLocalRandom

class DirectBufferStreamBrokerSpec extends Specification {

    def "consumeNext successfully allocates buffer capacity when incoming stream exceeds double initial capacity"() {
        given:
        int initialSize = 64
        int streamSize = 2048
        byte[] payload = new byte[streamSize]
        ThreadLocalRandom.current().nextBytes(payload)

        def broker = new DirectBufferStreamBroker(initialSize)

        when:
        boolean completed = broker.consumeNext(new ByteArrayInputStream(payload), streamSize, streamSize)
        byte[] extracted = broker.extractBytes()

        then:
        completed
        extracted.length == streamSize
        extracted == payload
    }

    def "resetAndCompact restores initial buffer capacity"() {
        given:
        def broker = new DirectBufferStreamBroker(64)
        byte[] payload = new byte[512]
        broker.consumeNext(new ByteArrayInputStream(payload), 512, 512)

        when:
        broker.resetAndCompact()

        then:
        broker.getBuffer().capacity() == 64
    }
}
