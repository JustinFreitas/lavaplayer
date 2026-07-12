package com.sedmelluq.discord.lavaplayer.tools

import spock.lang.Specification

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class ExceptionToolsSpec extends Specification {

    // --- rethrowErrors ---

    def "rethrowErrors rethrows an Error"() {
        when:
        ExceptionTools.rethrowErrors(new OutOfMemoryError("boom"))

        then:
        thrown(OutOfMemoryError)
    }

    def "rethrowErrors does nothing for a non-Error throwable"() {
        when:
        ExceptionTools.rethrowErrors(new RuntimeException("not an error"))

        then:
        noExceptionThrown()
    }

    // --- wrapUnfriendlyExceptions(message, severity, throwable) ---

    def "wrapUnfriendlyExceptions returns the same instance when already a FriendlyException"() {
        given:
        def original = new FriendlyException("already friendly", FriendlyException.Severity.COMMON, null)

        expect:
        ExceptionTools.wrapUnfriendlyExceptions("new message", FriendlyException.Severity.FAULT, original).is(original)
    }

    def "wrapUnfriendlyExceptions wraps a non-friendly throwable"() {
        given:
        def original = new IllegalStateException("bad state")

        when:
        def wrapped = ExceptionTools.wrapUnfriendlyExceptions("new message", FriendlyException.Severity.SUSPICIOUS, original)

        then:
        wrapped.message == "new message"
        wrapped.severity == FriendlyException.Severity.SUSPICIOUS
        wrapped.cause.is(original)
    }

    // --- wrapUnfriendlyExceptions(throwable) ---

    def "wrapUnfriendlyExceptions(throwable) returns the same instance when already a FriendlyException"() {
        given:
        def original = new FriendlyException("already friendly", FriendlyException.Severity.COMMON, null)

        expect:
        ExceptionTools.wrapUnfriendlyExceptions(original as Throwable).is(original)
    }

    def "wrapUnfriendlyExceptions(throwable) wraps a non-friendly throwable in a RuntimeException"() {
        given:
        def original = new IllegalStateException("bad state")

        when:
        def wrapped = ExceptionTools.wrapUnfriendlyExceptions(original as Throwable)

        then:
        !(wrapped instanceof FriendlyException)
        wrapped.cause.is(original)
    }

    // --- toRuntimeException ---

    def "toRuntimeException returns the same instance for a RuntimeException"() {
        given:
        def original = new IllegalArgumentException("bad arg")

        expect:
        ExceptionTools.toRuntimeException(original).is(original)
    }

    def "toRuntimeException wraps a checked exception"() {
        given:
        def original = new java.io.IOException("io failure")

        when:
        def wrapped = ExceptionTools.toRuntimeException(original)

        then:
        wrapped.class == RuntimeException
        wrapped.cause.is(original)
    }

    // --- findDeepException ---

    def "findDeepException finds a direct match"() {
        given:
        def exception = new IllegalStateException("top")

        expect:
        ExceptionTools.findDeepException(exception, IllegalStateException).is(exception)
    }

    def "findDeepException walks the cause chain to find a match"() {
        given:
        def root = new java.io.IOException("root cause")
        def middle = new RuntimeException("middle", root)
        def top = new IllegalStateException("top", middle)

        expect:
        ExceptionTools.findDeepException(top, java.io.IOException).is(root)
    }

    def "findDeepException matches a subtype via isAssignableFrom"() {
        given:
        def exception = new FriendlyException("friendly", FriendlyException.Severity.COMMON, null)

        expect:
        ExceptionTools.findDeepException(exception, RuntimeException).is(exception)
    }

    def "findDeepException returns null when nothing in the chain matches"() {
        given:
        def top = new IllegalStateException("top", new RuntimeException("middle"))

        expect:
        ExceptionTools.findDeepException(top, java.io.IOException) == null
    }

    // --- keepInterrupted ---

    def "keepInterrupted sets the thread's interrupted flag for an InterruptedException"() {
        when:
        ExceptionTools.keepInterrupted(new InterruptedException())

        then:
        Thread.currentThread().isInterrupted()

        cleanup:
        Thread.interrupted() // clear the flag so it doesn't leak into other tests
    }

    def "keepInterrupted does not set the interrupted flag for other throwables"() {
        when:
        ExceptionTools.keepInterrupted(new RuntimeException("not interrupted"))

        then:
        !Thread.currentThread().isInterrupted()
    }

    // --- encodeException / decodeException ---

    private static byte[] encode(FriendlyException exception) {
        def buffer = new ByteArrayOutputStream()
        ExceptionTools.encodeException(new DataOutputStream(buffer), exception)
        return buffer.toByteArray()
    }

    private static FriendlyException decode(byte[] bytes) {
        return ExceptionTools.decodeException(new DataInputStream(new ByteArrayInputStream(bytes)))
    }

    def "encodeException/decodeException round-trips a simple exception with no cause"() {
        given:
        def original = new FriendlyException("top level message", FriendlyException.Severity.SUSPICIOUS, null)

        when:
        def decoded = decode(encode(original))

        then:
        decoded.message == "top level message"
        decoded.severity == FriendlyException.Severity.SUSPICIOUS
        decoded.cause == null
    }

    def "encodeException/decodeException round-trips the stack trace of the top-level exception"() {
        given:
        def original = new FriendlyException("top level", FriendlyException.Severity.FAULT, null)
        original.setStackTrace([new StackTraceElement("com.example.Foo", "bar", "Foo.java", 42)] as StackTraceElement[])

        when:
        def decoded = decode(encode(original))

        then:
        decoded.stackTrace.length == 1
        decoded.stackTrace[0].className == "com.example.Foo"
        decoded.stackTrace[0].methodName == "bar"
        decoded.stackTrace[0].fileName == "Foo.java"
        decoded.stackTrace[0].lineNumber == 42
    }

    def "encodeException/decodeException round-trips a null file name in the stack trace"() {
        given:
        def original = new FriendlyException("top level", FriendlyException.Severity.FAULT, null)
        original.setStackTrace([new StackTraceElement("com.example.Foo", "bar", null, -1)] as StackTraceElement[])

        when:
        def decoded = decode(encode(original))

        then:
        decoded.stackTrace[0].fileName == null
    }

    def "encodeException/decodeException round-trips a cause chain, preserving order and class names"() {
        given:
        def root = new java.io.IOException("root cause message")
        def middle = new IllegalStateException("middle cause message", root)
        def original = new FriendlyException("top level message", FriendlyException.Severity.COMMON, middle)

        when:
        def decoded = decode(encode(original)) as FriendlyException

        then:
        decoded.message == "top level message"

        def decodedMiddle = decoded.cause as DecodedException
        decodedMiddle.className == IllegalStateException.name
        decodedMiddle.originalMessage == "middle cause message"

        def decodedRoot = decodedMiddle.cause as DecodedException
        decodedRoot.className == java.io.IOException.name
        decodedRoot.originalMessage == "root cause message"
        decodedRoot.cause == null
    }

    def "encodeException/decodeException round-trips a cause with a null message"() {
        given:
        def cause = new RuntimeException((String) null)
        def original = new FriendlyException("top level message", FriendlyException.Severity.COMMON, cause)

        when:
        def decoded = decode(encode(original)) as FriendlyException

        then:
        (decoded.cause as DecodedException).originalMessage == null
    }
}
