package com.sedmelluq.discord.lavaplayer.tools;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TestOrderedExecutor {
    public static void main(String[] args) throws Exception {
        ExecutorService delegate = Executors.newFixedThreadPool(4);
        OrderedExecutor executor = new OrderedExecutor(delegate);
        
        AtomicInteger counter = new AtomicInteger(0);
        int total = 10000;
        CountDownLatch latch = new CountDownLatch(total);
        
        Object key = new Object();
        for (int i = 0; i < total; i++) {
            executor.submit(key, () -> {
                counter.incrementAndGet();
                latch.countDown();
            });
        }
        
        if (latch.await(5, TimeUnit.SECONDS)) {
            System.out.println("All executed. Counter: " + counter.get());
        } else {
            System.out.println("Stranded! Executed: " + counter.get());
        }
        
        delegate.shutdown();
    }
}
