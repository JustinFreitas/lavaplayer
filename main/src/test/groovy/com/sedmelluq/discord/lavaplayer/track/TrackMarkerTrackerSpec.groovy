package com.sedmelluq.discord.lavaplayer.track

import spock.lang.Specification

import static com.sedmelluq.discord.lavaplayer.track.TrackMarkerHandler.MarkerState.*

class TrackMarkerTrackerSpec extends Specification {

    TrackMarkerTracker tracker = new TrackMarkerTracker()

    private static TrackMarker marker(long timecode, TrackMarkerHandler handler) {
        new TrackMarker(timecode, handler)
    }

    // --- add ---

    def "add stores a marker whose timecode has not yet been reached"() {
        given:
        def handler = Mock(TrackMarkerHandler)

        when:
        tracker.add(marker(1000, handler), 500)

        then:
        0 * handler.handle(_)
        tracker.markers.size() == 1
    }

    def "add immediately triggers LATE when the current timecode is already past the marker"() {
        given:
        def handler = Mock(TrackMarkerHandler)

        when:
        tracker.add(marker(500, handler), 1000)

        then:
        1 * handler.handle(LATE)
        tracker.markers.isEmpty()
    }

    def "add immediately triggers LATE when the current timecode exactly equals the marker"() {
        given:
        def handler = Mock(TrackMarkerHandler)

        when:
        tracker.add(marker(1000, handler), 1000)

        then:
        1 * handler.handle(LATE)
        tracker.markers.isEmpty()
    }

    def "add does nothing for a null marker"() {
        when:
        tracker.add(null, 500)

        then:
        noExceptionThrown()
        tracker.markers.isEmpty()
    }

    // --- set ---

    def "set with a marker triggers OVERWRITTEN on any previous markers and adds the new one"() {
        given:
        def oldHandler = Mock(TrackMarkerHandler)
        def newHandler = Mock(TrackMarkerHandler)
        tracker.add(marker(1000, oldHandler), 0)

        when:
        tracker.set(marker(2000, newHandler), 500)

        then:
        1 * oldHandler.handle(OVERWRITTEN)
        0 * newHandler.handle(_)
        tracker.markers.size() == 1
        tracker.markers[0].timecode == 2000
    }

    def "set with null triggers REMOVED on any previous markers and clears the list"() {
        given:
        def handler = Mock(TrackMarkerHandler)
        tracker.add(marker(1000, handler), 0)

        when:
        tracker.set(null, 500)

        then:
        1 * handler.handle(REMOVED)
        tracker.markers.isEmpty()
    }

    // --- remove(TrackMarker) ---

    def "remove(marker) removes a present marker and triggers REMOVED"() {
        given:
        def handler = Mock(TrackMarkerHandler)
        def m = marker(1000, handler)
        tracker.add(m, 0)

        when:
        tracker.remove(m)

        then:
        1 * handler.handle(REMOVED)
        tracker.markers.isEmpty()
    }

    def "remove(marker) does nothing for a marker that is not present"() {
        given:
        def handler = Mock(TrackMarkerHandler)
        def other = Mock(TrackMarkerHandler)
        tracker.add(marker(1000, handler), 0)

        when:
        tracker.remove(marker(2000, other))

        then:
        0 * handler.handle(_)
        0 * other.handle(_)
        tracker.markers.size() == 1
    }

    // --- deprecated remove() ---

    def "deprecated remove() removes and returns the first marker without triggering its handler"() {
        given:
        def handlerA = Mock(TrackMarkerHandler)
        def handlerB = Mock(TrackMarkerHandler)
        def markerA = marker(1000, handlerA)
        tracker.add(markerA, 0)
        tracker.add(marker(2000, handlerB), 0)

        when:
        def removed = tracker.remove()

        then:
        removed.is(markerA)
        0 * handlerA.handle(_)
        0 * handlerB.handle(_)
        tracker.markers.size() == 1
    }

    def "deprecated remove() returns null when there are no markers"() {
        expect:
        tracker.remove() == null
    }

    // --- trigger(state) ---

    def "trigger broadcasts the given state to all markers and clears the list"() {
        given:
        def handlerA = Mock(TrackMarkerHandler)
        def handlerB = Mock(TrackMarkerHandler)
        tracker.add(marker(1000, handlerA), 0)
        tracker.add(marker(2000, handlerB), 0)

        when:
        tracker.trigger(STOPPED)

        then:
        1 * handlerA.handle(STOPPED)
        1 * handlerB.handle(STOPPED)
        tracker.markers.isEmpty()
    }

    // --- checkPlaybackTimecode ---

    def "checkPlaybackTimecode triggers REACHED only for markers at or before the given timecode"() {
        given:
        def reachedHandler = Mock(TrackMarkerHandler)
        def futureHandler = Mock(TrackMarkerHandler)
        tracker.add(marker(1000, reachedHandler), 0)
        tracker.add(marker(5000, futureHandler), 0)

        when:
        tracker.checkPlaybackTimecode(1500)

        then:
        1 * reachedHandler.handle(REACHED)
        0 * futureHandler.handle(_)
        tracker.markers.size() == 1
        tracker.markers[0].timecode == 5000
    }

    // --- checkSeekTimecode ---

    def "checkSeekTimecode triggers BYPASSED only for markers at or before the given timecode"() {
        given:
        def bypassedHandler = Mock(TrackMarkerHandler)
        def futureHandler = Mock(TrackMarkerHandler)
        tracker.add(marker(1000, bypassedHandler), 0)
        tracker.add(marker(5000, futureHandler), 0)

        when:
        tracker.checkSeekTimecode(1500)

        then:
        1 * bypassedHandler.handle(BYPASSED)
        0 * futureHandler.handle(_)
        tracker.markers.size() == 1
    }

    // --- getMarkers / clear ---

    def "getMarkers returns an unmodifiable view"() {
        given:
        tracker.add(marker(1000, Mock(TrackMarkerHandler)), 0)

        when:
        tracker.markers.add(marker(2000, Mock(TrackMarkerHandler)))

        then:
        thrown(UnsupportedOperationException)
    }

    def "clear empties the marker list without triggering any handlers"() {
        given:
        def handler = Mock(TrackMarkerHandler)
        tracker.add(marker(1000, handler), 0)

        when:
        tracker.clear()

        then:
        0 * handler.handle(_)
        tracker.markers.isEmpty()
    }
}
