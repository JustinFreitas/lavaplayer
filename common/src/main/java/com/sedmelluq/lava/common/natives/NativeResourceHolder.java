package com.sedmelluq.lava.common.natives;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract instance of a class which holds native resources that must be freed.
 */
public abstract class NativeResourceHolder implements AutoCloseable {
    private static final Cleaner cleaner = Cleaner.create();

    private final AtomicBoolean released = new AtomicBoolean();
    private Cleaner.Cleanable cleanable;

    /**
     * Registers the cleanup action to be run when this resource is closed or garbage collected.
     * This must be called in the constructor of the subclass, using a non-capturing Runnable (such
     * as a static inner class) to avoid memory leaks.
     *
     * @param cleanupAction The action to run to release native resources.
     */
    protected void registerCleanup(Runnable cleanupAction) {
        this.cleanable = cleaner.register(this, cleanupAction);
    }

    /**
     * Assert that the native resources have not been freed.
     */
    protected void checkNotReleased() {
        if (released.get()) {
            throw new IllegalStateException("Cannot use the resource after closing it.");
        }
    }

    /**
     * Free up native resources. Using other methods after this will throw IllegalStateException.
     */
    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            if (cleanable != null) {
                cleanable.clean();
            }
        }
    }
}
