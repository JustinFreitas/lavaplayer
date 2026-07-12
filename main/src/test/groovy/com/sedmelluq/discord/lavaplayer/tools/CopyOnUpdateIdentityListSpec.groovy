package com.sedmelluq.discord.lavaplayer.tools

import spock.lang.Specification

class CopyOnUpdateIdentityListSpec extends Specification {

    CopyOnUpdateIdentityList<String> list = new CopyOnUpdateIdentityList<>()

    def "add appends items in insertion order"() {
        given:
        def a = new String("a")
        def b = new String("b")

        when:
        list.add(a)
        list.add(b)

        then:
        list.items == [a, b]
    }

    def "add does not duplicate the exact same instance"() {
        given:
        def a = new String("a")

        when:
        list.add(a)
        list.add(a)

        then:
        list.items.size() == 1
    }

    def "add treats equals-but-not-identical instances as distinct"() {
        given:
        // Two separate String instances with equal content but different identity.
        def a1 = new String("same")
        def a2 = new String("same")

        when:
        list.add(a1)
        list.add(a2)

        then:
        a1 == a2 // sanity check: they are equal...
        !a1.is(a2) // ...but not the same instance
        list.items.size() == 2
    }

    def "remove drops an item by identity"() {
        given:
        def a = new String("a")
        def b = new String("b")
        list.add(a)
        list.add(b)

        when:
        list.remove(a)

        then:
        list.items == [b]
    }

    def "remove does not drop an equals-but-not-identical instance"() {
        given:
        def a1 = new String("same")
        def a2 = new String("same")
        list.add(a1)

        when:
        list.remove(a2)

        then:
        list.items == [a1]
    }

    def "remove of an absent item is a no-op"() {
        given:
        def a = new String("a")
        list.add(a)

        when:
        list.remove(new String("not present"))

        then:
        list.items == [a]
    }

    def "add and remove produce a new list instance, leaving previously captured references unchanged"() {
        given:
        def before = list.items
        def a = new String("a")

        when:
        list.add(a)

        then: "the reference captured before the mutation is untouched (safe to iterate concurrently)"
        before.isEmpty()
        list.items.size() == 1
        !list.items.is(before)
    }
}
