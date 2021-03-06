package io.github.avivcarmis.javared.test;

import io.github.avivcarmis.javared.future.OpenRedFuture;
import io.github.avivcarmis.javared.future.RedFuture;
import io.github.avivcarmis.javared.future.RedFutureHub;
import io.github.avivcarmis.javared.future.callbacks.EmptyCallback;
import org.junit.Assert;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The context of an asynchronous test.
 * The test context is responsible for:
 * <ul>
 *     <li>Forking the test, allowing the flow test method to return
 *     without finishing the test, to enable waiting for async operations
 *     to finish. Each fork represents a single async operation that must
 *     be marked as finished or failed for the test to end.</li>
 *     <li>Assertions - since assertions may be performed on callbacks
 *     of any async operation, on any thread, assertion errors will be
 *     thrown, be JUnit won't be there to catch them. For that end,
 *     the {@link RedTestContext} instance exposes all of JUnit {@link Assert}
 *     interface, so that assertions can be made from any context, and still
 *     influence test results.</li>
 *     <li>Late scheduling - since scheduling late tasks and assertions
 *     is a common async test practice, the context instance introduces
 *     a simple utility scheduling method available at
 *     {@link #scheduleTask(long, TimeUnit, Runnable)}.</li>
 *     <li>Timing assertions - allowing simple and intuitive interface
 *     to validate whether certain periods of times passed or didn't pass
 *     from various points of the execution</li>
 * </ul>
 *
 * Note that all the context forks must be made before the test method returns.
 * See more at {@link #fork()}
 */
@SuppressWarnings("SameParameterValue")
public class RedTestContext {

    // Constants

    /**
     * Scheduler service for scheduling late callbacks
     */
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(1);

    // Fields

    /**
     * The context assertion interface
     */
    public final Assertions assertions;

    /**
     * The thread executing the test
     * When the test method returns, the executing thread sleeps until all
     * forks are finished. A reference to the thread is required for interrupting
     * it in case an assertion error is thrown, to prevent it from keep sleeping.
     */
    private final Thread _executingThread;

    /**
     * A {@link RedFutureHub} for managing the test forks
     * Each fork has an underlying future, managed by the hub.
     */
    private final RedFutureHub _hub;

    /**
     * Holds the instance of the first throwable thrown anywhere on the test context
     */
    private final AtomicReference<Throwable> _firstFailure;

    /**
     * Whether or not the test is still active.
     */
    private boolean _testActive;

    // Constructors

    RedTestContext(Thread executingThread) {
        assertions = new Assertions();
        _executingThread = executingThread;
        _hub = RedFuture.hub();
        _firstFailure = new AtomicReference<>(null);
        _testActive = true;
    }

    // Public

    /**
     * Forks the test, and returns the fork instance.
     * The returned instance must be held and completed, for the test to successful complete.
     *
     * NOTE that all the context forks must be made before the test method returns.
     * If, for example, we want to perform an async DB call, and when it's done, and only then,
     * we want to perform another one. Then in such a case we want to fork the context twice
     * at the beginning of the test method (or any other time before it returns), and NOT
     * to perform the second fork after the first one is completed.
     *
     * @return a fork instance
     */
    public Fork fork() {
        return new Fork(_hub.provideFuture());
    }

    /**
     * Fails the test.
     *
     * @param throwable cause
     */
    public void fail(Throwable throwable) {
        handleFailure(throwable);
    }

    /**
     * Fails the test.
     *
     * @param message reason
     */
    public void fail(String message) {
        handleFailure(new RuntimeException(message));
    }

    /**
     * Fails the test.
     */
    public void fail() {
        handleFailure(new RuntimeException());
    }

    /**
     * Schedules a late runnable to be executed.
     *
     * @param delay    the delay of the execution to apply
     * @param unit     the time unit the of given delay
     * @param runnable the runnable task to execute
     */
    public void scheduleTask(long delay, TimeUnit unit, Runnable runnable) {
        SCHEDULER.schedule(runnable, delay, unit);
    }

    /**
     * Schedules a late runnable to be executed with a given delay of milliseconds.
     *
     * @param delayMillis the delay of the execution to apply in milliseconds
     * @param runnable    the runnable task to execute
     */
    public void scheduleTask(long delayMillis, Runnable runnable) {
        scheduleTask(delayMillis, TimeUnit.MILLISECONDS, runnable);
    }

    /**
     * A {@link RedTestContext} provides a method for validating timing executions.
     * A {@link TimingValidator} may be created at a certain point at time, and later,
     * this {@link TimingValidator} instance may be used to validate certain period of
     * time has passed or not passed since it's creation.
     * @return a new instance of {@link TimingValidator}
     */
    public TimingValidator timingValidator() {
        return new TimingValidator();
    }

    /**
     * In some cases, in a late callback of an async operation,
     * it may be reasonable to validate whether or not the test is still active, or completed.
     * For example, you can create a fork, and then schedule to complete it in 10ms, and schedule
     * another callback to run in 100ms. The second callback will get executed,
     * but the test will already be finished at this point.
     *
     * @return whether or not the test is still pending results
     */
    public boolean isTestActive() {
        return _testActive;
    }

    // Private

    /**
     * Marks the test as completed.
     */
    void close() {
        _testActive = false;
    }

    /**
     * @return an optimistic union of all the forks futures.
     */
    RedFuture unite() {
        return _hub.uniteOptimistically();
    }

    /**
     * @throws Throwable any failure of the context that hasn't been thrown on the executing thread.
     */
    void checkAssertions() throws Throwable {
        if (_firstFailure.get() != null) {
            throw _firstFailure.get();
        }
    }

    /**
     * Executes a given callback and interrupts the executing thread
     * if a throwable is thrown during execution.
     * @param callback callback to execute
     */
    private void assertion(EmptyCallback callback) {
        try {
            callback.call();
        } catch (Throwable t) {
            handleFailure(t);
            throw t;
        }
    }

    /**
     * Marks the given throwable as the cause of failure for the test,
     * in case no prior throwable has taken place.
     * Then, interrupts the executing thread to prevent it from keep sleeping.
     * @param t throwable to handle
     */
    private void handleFailure(Throwable t) {
        _firstFailure.compareAndSet(null, t);
        _executingThread.interrupt();
    }

    /**
     * A fork of a {@link RedTestContext}
     *
     * see {@link RedTestContext#fork()}
     */
    public static class Fork {

        // Fields

        /**
         * The underlying future to resolve or fail
         */
        private final OpenRedFuture _future;

        // Constructors

        private Fork(OpenRedFuture future) {
            _future = future;
        }

        // Public

        /**
         * Mark the fork as completed, allowing the test to successfully complete
         */
        public void complete() {
            _future.resolve();
        }

        /**
         * Fails the entire test, calling this method is logically identical to
         * calling {@link RedTestContext#fail(Throwable)}
         * @param throwable cause of failure
         */
        public void fail(Throwable throwable) {
            _future.fail(throwable);
        }

        /**
         * Fails the entire test, calling this method is logically identical to
         * calling {@link RedTestContext#fail(String)}
         * @param message reason of failure
         */
        public void fail(String message) {
            _future.fail(new RuntimeException(message));
        }

        /**
         * Fails the entire test, calling this method is logically identical to
         * calling {@link RedTestContext#fail()}
         */
        public void fail() {
            _future.fail(new RuntimeException());
        }

    }

    /**
     * A Timing tester for a certain {@link RedTestContext}
     * May be used to validate expected periods of time to pass or not pass,
     * from the moment of it's creation.
     *
     * see {@link RedTestContext#timingValidator()}
     */
    public class TimingValidator {

        // Fields

        /**
         * The system time in nanoseconds at the moment of creation
         */
        private final long _startTimeNano;

        // Constructors

        private TimingValidator() {
            _startTimeNano = System.nanoTime();
        }

        // Public

        /**
         * Validates whether or not the given period of time has passed since
         * the creation of the {@link TimingValidator} instance.
         *
         * If the time has not passed, the test will automatically fail with
         * {@link NotPassedException}
         *
         * @param time period of time expected to have passed
         * @param unit time unit to use
         */
        public void validatePassed(long time, TimeUnit unit) {
            if (timePassedNanos() < unit.toNanos(time)) {
                fail(new NotPassedException(time, unit));
            }
        }

        /**
         * Validates whether or not the given period of time in milliseconds
         * has passed since the creation of the {@link TimingValidator} instance.
         *
         * If the time has not passed, the test will automatically fail with
         * {@link NotPassedException}
         *
         * @param timeMillis milliseconds expected to have passed
         */
        public void validatePassed(long timeMillis) {
            validatePassed(timeMillis, TimeUnit.MILLISECONDS);
        }

        /**
         * Validates whether or not the given period of time has not passed
         * since the creation of the {@link TimingValidator} instance.
         *
         * If the time has not passed, the test will automatically fail with
         * {@link PassedException}
         *
         * @param time period of time expected to not have passed
         * @param unit time unit to use
         */
        public void validateNotPassed(long time, TimeUnit unit) {
            if (timePassedNanos() >= unit.toNanos(time)) {
                fail(new PassedException(time, unit));
            }
        }

        /**
         * Validates whether or not the given period of time in milliseconds
         * has not passed since the creation of the {@link TimingValidator} instance.
         *
         * If the time has not passed, the test will automatically fail with
         * {@link PassedException}
         *
         * @param timeMillis milliseconds expected to not have passed
         */
        public void validateNotPassed(long timeMillis) {
            validateNotPassed(timeMillis, TimeUnit.MILLISECONDS);
        }

        // Private

        /**
         * @return time in nanoseconds passed since creation
         */
        private long timePassedNanos() {
            return System.nanoTime() - _startTimeNano;
        }

        /**
         * An exception thrown in case a {@link TimingValidator} not passed validation has failed
         */
        public class PassedException extends Exception {

            private PassedException(long expected, TimeUnit unit) {
                super(String.format("expected %d %s not to pass, but %d %s passed",
                        expected, unit.name(), unit.convert(timePassedNanos(), TimeUnit.NANOSECONDS), unit.name()));
            }

        }

        /**
         * An exception thrown in case a {@link TimingValidator} passed validation has failed
         */
        public class NotPassedException extends Exception {

            private NotPassedException(long expected, TimeUnit unit) {
                super(String.format("expected %d %s to pass, but only %d %s passed",
                        expected, unit.name(), unit.convert(timePassedNanos(), TimeUnit.NANOSECONDS), unit.name()));
            }

        }

    }

    /**
     * Exposes JUnit {@link Assert} interface,
     * wraps each assertion method with a call to {@link #assertion(EmptyCallback)}
     */
    public class Assertions {

        public void assertTrue(String message, boolean condition) {
            assertion(() -> Assert.assertTrue(message, condition));
        }

        public void assertTrue(boolean condition) {
            assertion(() -> Assert.assertTrue(condition));
        }

        public void assertFalse(String message, boolean condition) {
            assertion(() -> Assert.assertFalse(message, condition));
        }

        public void assertFalse(boolean condition) {
            assertion(() -> Assert.assertFalse(condition));
        }

        public void assertEquals(String message, Object expected, Object actual) {
            assertion(() -> Assert.assertEquals(message, expected, actual));
        }

        public void assertEquals(Object expected, Object actual) {
            assertion(() -> Assert.assertEquals(expected, actual));
        }

        public void assertNotEquals(String message, Object unexpected, Object actual) {
            assertion(() -> Assert.assertNotEquals(message, unexpected, actual));
        }

        public void assertNotEquals(Object unexpected, Object actual) {
            assertion(() -> Assert.assertNotEquals(unexpected, actual));
        }

        public void assertNotEquals(String message, long unexpected, long actual) {
            assertion(() -> Assert.assertNotEquals(message, unexpected, actual));
        }

        public void assertNotEquals(long unexpected, long actual) {
            assertion(() -> Assert.assertNotEquals(unexpected, actual));
        }

        public void assertNotEquals(String message, double unexpected, double actual, double delta) {
            assertion(() -> Assert.assertNotEquals(message, unexpected, actual, delta));
        }

        public void assertNotEquals(double unexpected, double actual, double delta) {
            assertion(() -> Assert.assertNotEquals(unexpected, actual, delta));
        }

        public void assertNotEquals(float unexpected, float actual, float delta) {
            assertion(() -> Assert.assertNotEquals(unexpected, actual, delta));
        }

        public void assertArrayEquals(String message, Object[] expected, Object[] actual) {
            assertion(() -> Assert.assertArrayEquals(message, expected, actual));
        }

        public void assertArrayEquals(Object[] expected, Object[] actual) {
            assertion(() -> Assert.assertArrayEquals(expected, actual));
        }

        public void assertArrayEquals(String message, boolean[] expected, boolean[] actual) {
            assertion(() -> Assert.assertArrayEquals(message, expected, actual));
        }

        public void assertArrayEquals(boolean[] expected, boolean[] actual) {
            assertion(() -> Assert.assertArrayEquals(expected, actual));
        }

        public void assertArrayEquals(String message, byte[] expected, byte[] actual) {
            assertion(() -> Assert.assertArrayEquals(message, expected, actual));
        }

        public void assertArrayEquals(byte[] expected, byte[] actual) {
            assertion(() -> Assert.assertArrayEquals(expected, actual));
        }

        public void assertArrayEquals(String message, char[] expected, char[] actual) {
            assertion(() -> Assert.assertArrayEquals(message, expected, actual));
        }

        public void assertArrayEquals(char[] expected, char[] actual) {
            assertion(() -> Assert.assertArrayEquals(expected, actual));
        }

        public void assertArrayEquals(String message, short[] expected, short[] actual) {
            assertion(() -> Assert.assertArrayEquals(message, expected, actual));
        }

        public void assertArrayEquals(short[] expected, short[] actual) {
            assertion(() -> Assert.assertArrayEquals(expected, actual));
        }

        public void assertArrayEquals(String message, int[] expected, int[] actual) {
            assertion(() -> Assert.assertArrayEquals(message, expected, actual));
        }

        public void assertArrayEquals(int[] expected, int[] actual) {
            assertion(() -> Assert.assertArrayEquals(expected, actual));
        }

        public void assertArrayEquals(String message, long[] expected, long[] actual) {
            assertion(() -> Assert.assertArrayEquals(message, expected, actual));
        }

        public void assertArrayEquals(long[] expected, long[] actual) {
            assertion(() -> Assert.assertArrayEquals(expected, actual));
        }

        public void assertArrayEquals(String message, double[] expected, double[] actual, double delta) {
            assertion(() -> Assert.assertArrayEquals(message, expected, actual, delta));
        }

        public void assertArrayEquals(double[] expected, double[] actual, double delta) {
            assertion(() -> Assert.assertArrayEquals(expected, actual, delta));
        }

        public void assertArrayEquals(String message, float[] expected, float[] actual, float delta) {
            assertion(() -> Assert.assertArrayEquals(message, expected, actual, delta));
        }

        public void assertArrayEquals(float[] expected, float[] actual, float delta) {
            assertion(() -> Assert.assertArrayEquals(expected, actual, delta));
        }

        public void assertEquals(String message, double expected, double actual, double delta) {
            assertion(() -> Assert.assertEquals(message, expected, actual, delta));
        }

        public void assertEquals(String message, float expected, float actual, float delta) {
            assertion(() -> Assert.assertEquals(message, expected, actual, delta));
        }

        public void assertNotEquals(String message, float unexpected, float actual, float delta) {
            assertion(() -> Assert.assertNotEquals(message, unexpected, actual, delta));
        }

        public void assertEquals(long expected, long actual) {
            assertion(() -> Assert.assertEquals(expected, actual));
        }

        public void assertEquals(String message, long expected, long actual) {
            assertion(() -> Assert.assertEquals(message, expected, actual));
        }

        public void assertEquals(double expected, double actual, double delta) {
            assertion(() -> Assert.assertEquals(expected, actual, delta));
        }

        public void assertEquals(float expected, float actual, float delta) {
            assertion(() -> Assert.assertEquals(expected, actual, delta));
        }

        public void assertNotNull(String message, Object object) {
            assertion(() -> Assert.assertNotNull(message, object));
        }

        public void assertNotNull(Object object) {
            assertion(() -> Assert.assertNotNull(object));
        }

        public void assertNull(String message, Object object) {
            assertion(() -> Assert.assertNull(message, object));
        }

        public void assertNull(Object object) {
            assertion(() -> Assert.assertNull(object));
        }

        public void assertSame(String message, Object expected, Object actual) {
            assertion(() -> Assert.assertSame(message, expected, actual));
        }

        public void assertSame(Object expected, Object actual) {
            assertion(() -> Assert.assertSame(expected, actual));
        }

        public void assertNotSame(String message, Object unexpected, Object actual) {
            assertion(() -> Assert.assertNotSame(message, unexpected, actual));
        }

        public void assertNotSame(Object unexpected, Object actual) {
            assertion(() -> Assert.assertNotSame(unexpected, actual));
        }

    }

}
