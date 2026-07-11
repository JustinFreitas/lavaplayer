package com.sedmelluq.discord.lavaplayer.tools

import spock.lang.Specification
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class OrderedExecutorSpec extends Specification {
    def "tasks with the same key execute sequentially and in submission order"() {
        given:
        def delegate = Executors.newFixedThreadPool(4)
        def executor = new OrderedExecutor(delegate)
        def executionOrder = new CopyOnWriteArrayList<Integer>()
        def key = "same-key"

        when:
        def futures = (1..5).collect { i ->
            executor.submit(key, {
                Thread.sleep(50) // simulate work
                executionOrder.add(i)
            })
        }
        futures.each { it.get() }

        then:
        executionOrder == [1, 2, 3, 4, 5]

        cleanup:
        delegate.shutdown()
    }

    def "tasks with different keys can execute in parallel"() {
        given:
        def delegate = Executors.newFixedThreadPool(4)
        def executor = new OrderedExecutor(delegate)
        def latch = new CountDownLatch(2)
        def startLatch = new CountDownLatch(1)
        def key1 = "key-1"
        def key2 = "key-2"
        def task1FinishedFirst = false

        when:
        def f1 = executor.submit(key1, {
            startLatch.await()
            Thread.sleep(100)
            task1FinishedFirst = latch.getCount() == 2
            latch.countDown()
        })
        def f2 = executor.submit(key2, {
            startLatch.await()
            latch.countDown()
        })
        
        startLatch.countDown()
        f1.get()
        f2.get()

        then:
        task1FinishedFirst == false // Task 2 finished first because Task 1 slept, showing parallel execution

        cleanup:
        delegate.shutdown()
    }

    def "submit with Callable returns the expected result"() {
        given:
        def delegate = Executors.newFixedThreadPool(1)
        def executor = new OrderedExecutor(delegate)
        def key = "key"

        when:
        def future = executor.submit(key, { "hello" } as Callable)
        def result = future.get()

        then:
        result == "hello"

        cleanup:
        delegate.shutdown()
    }

    def "failing task does not strand the queue"() {
        given:
        def delegate = Executors.newFixedThreadPool(2)
        def executor = new OrderedExecutor(delegate)
        def executionOrder = new CopyOnWriteArrayList<Integer>()
        def key = "key"

        when:
        def f1 = executor.submit(key, {
            throw new RuntimeException("oops")
        })
        def f2 = executor.submit(key, {
            executionOrder.add(2)
        })

        try {
            f1.get()
        } catch (Exception e) {
            // expected
        }
        f2.get()

        then:
        executionOrder == [2]

        cleanup:
        delegate.shutdown()
    }
}
