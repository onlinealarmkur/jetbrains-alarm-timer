package com.onlinealarmkur.jetbrains.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class SoundPlayerTest {
    @Test
    fun `rapid commands are serialized away from the EDT`() {
        val backend = RecordingBackend(expectedCalls = 4)
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sound-test-worker") }
        val player = SoundPlayer(backend, executor)
        lateinit var edtThread: Thread

        SwingUtilities.invokeAndWait {
            assertTrue(SwingUtilities.isEventDispatchThread())
            edtThread = Thread.currentThread()
            player.play(10)
            player.play(20)
            player.stop()
        }
        assertTrue(backend.awaitCalls(3))
        player.dispose()

        assertTrue(backend.awaitCalls(4))
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf("play:10", "play:20", "stop", "stop"), backend.events)
        assertTrue(backend.threads.all { it !== edtThread })
    }

    @Test
    fun `dispose skips queued playback closes backend and rejects later commands`() {
        val backend = RecordingBackend(expectedCalls = 1)
        val executor = ManualExecutorService()
        val player = SoundPlayer(backend, executor)

        player.play(10)
        player.play(20)
        player.dispose()
        player.play(30)
        player.stop()
        executor.runAll()

        assertEquals(listOf("stop"), backend.events)
        assertTrue(executor.isShutdown)
        assertTrue(executor.isTerminated)
    }

    private class RecordingBackend(expectedCalls: Int) : AudioBackend {
        val events = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Thread>()
        private val firstThreeCalls = CountDownLatch(minOf(3, expectedCalls))
        private val allCalls = CountDownLatch(expectedCalls)

        override fun play(volumePercent: Int) {
            events += "play:$volumePercent"
            threads += Thread.currentThread()
            firstThreeCalls.countDown()
            allCalls.countDown()
        }

        override fun stop() {
            events += "stop"
            threads += Thread.currentThread()
            firstThreeCalls.countDown()
            allCalls.countDown()
        }

        fun awaitCalls(expectedCompleted: Int): Boolean {
            val latch = if (expectedCompleted <= 3) firstThreeCalls else allCalls
            return latch.await(5, TimeUnit.SECONDS)
        }
    }

    private class ManualExecutorService : AbstractExecutorService() {
        private val commands = ArrayDeque<Runnable>()
        private var shutdown = false

        override fun execute(command: Runnable) {
            check(!shutdown) { "Executor is shut down" }
            commands.addLast(command)
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf<Runnable>().also { remaining ->
                while (commands.isNotEmpty()) remaining += commands.removeFirst()
            }
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && commands.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated

        fun runAll() {
            while (commands.isNotEmpty()) commands.removeFirst().run()
        }
    }
}
