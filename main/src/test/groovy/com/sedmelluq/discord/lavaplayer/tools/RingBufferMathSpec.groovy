package com.sedmelluq.discord.lavaplayer.tools

import spock.lang.Specification

class RingBufferMathSpec extends Specification {

    private static RingBufferMath identityBuffer(int size) {
        new RingBufferMath(size, { it }, { it })
    }

    def "mean returns the transformed zero value before anything is added"() {
        given:
        def buffer = new RingBufferMath(4, { it }, { it + 100 })

        expect:
        buffer.mean() == 100.0d
    }

    def "mean averages the values added so far when under capacity"() {
        given:
        def buffer = identityBuffer(4)

        when:
        buffer.add(2.0d)
        buffer.add(4.0d)

        then:
        buffer.mean() == 3.0d
    }

    def "mean averages all values once the buffer is exactly full"() {
        given:
        def buffer = identityBuffer(4)

        when:
        [1.0d, 2.0d, 3.0d, 4.0d].each { buffer.add(it) }

        then:
        buffer.mean() == 2.5d
    }

    def "mean reflects only the most recent values once capacity is exceeded"() {
        given:
        def buffer = identityBuffer(3)

        when:
        // Capacity 3: the first value (1.0) should be evicted by the fourth add.
        [1.0d, 2.0d, 3.0d, 4.0d].each { buffer.add(it) }

        then:
        buffer.mean() == 3.0d // (2+3+4)/3
    }

    def "the input processor transforms values before they are stored"() {
        given:
        def buffer = new RingBufferMath(4, { it * 2 }, { it })

        when:
        buffer.add(2.0d)
        buffer.add(4.0d)

        then:
        buffer.mean() == 6.0d // (4+8)/2
    }

    def "the output processor transforms the final mean"() {
        given:
        def buffer = new RingBufferMath(4, { it }, { it + 1000 })

        when:
        buffer.add(2.0d)
        buffer.add(4.0d)

        then:
        buffer.mean() == 1003.0d
    }
}
