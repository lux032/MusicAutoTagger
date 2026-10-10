package com.lux032.musicautotagger.service;

import java.util.concurrent.locks.ReentrantLock;

/** Process-local disk transaction guard. Never hold this across metadata/network lookup. */
public final class LibraryWriteGuard {
    private static final ReentrantLock LOCK = new ReentrantLock(true);
    private LibraryWriteGuard() {}
    public static Scope acquire() { LOCK.lock(); return new Scope(); }
    public static Scope acquireInterruptibly() throws InterruptedException { LOCK.lockInterruptibly(); return new Scope(); }
    public static final class Scope implements AutoCloseable {
        private boolean closed;
        private Scope() {}
        public void close() { if (!closed) { closed = true; LOCK.unlock(); } }
    }
}
