package com.onlinealarmkur.jetbrains.notifications

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.SequentialTaskExecutor
import com.onlinealarmkur.jetbrains.service.AlarmSound
import java.awt.GraphicsEnvironment
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

internal class SoundPlayer(
    private val backend: AudioBackend = JavaxSoundBackend(),
    private val commandExecutor: ExecutorService = SequentialTaskExecutor.createSequentialApplicationPoolExecutor(
        "Alarm & Timer audio",
        AppExecutorUtil.getAppExecutorService(),
    ),
) : AlarmSound {
    private val disposed = AtomicBoolean()

    override fun play(volumePercent: Int) {
        submit("start alarm sound") {
            if (!disposed.get()) backend.play(volumePercent)
        }
    }

    override fun stop() {
        submit("stop alarm sound") {
            if (!disposed.get()) backend.stop()
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        try {
            commandExecutor.execute { runBackend("close alarm sound") { backend.stop() } }
        } catch (error: RejectedExecutionException) {
            LOG.warn("Could not queue final alarm sound cleanup.", error)
        } finally {
            commandExecutor.shutdown()
        }
    }

    private fun submit(operation: String, command: () -> Unit) {
        if (disposed.get()) return
        try {
            commandExecutor.execute { runBackend(operation, command) }
        } catch (error: RejectedExecutionException) {
            if (!disposed.get()) LOG.warn("Could not queue operation to $operation.", error)
        }
    }

    private fun runBackend(operation: String, command: () -> Unit) {
        try {
            command()
        } catch (error: Throwable) {
            if (error is ControlFlowException || error is CancellationException || error is Error) throw error
            LOG.warn("Failed to $operation; the alarm will continue without sound.", error)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SoundPlayer::class.java)
    }
}

internal interface AudioBackend {
    fun play(volumePercent: Int)
    fun stop()
}

internal class JavaxSoundBackend(
    private val headless: () -> Boolean = { GraphicsEnvironment.isHeadless() },
    private val clipFactory: () -> Clip = { AudioSystem.getClip() },
) : AudioBackend {
    private var clip: Clip? = null

    override fun play(volumePercent: Int) {
        stop()
        if (headless() || volumePercent <= 0) return
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val audio = toneBytes()
        val stream = AudioInputStream(ByteArrayInputStream(audio), format, (audio.size / format.frameSize).toLong())
        val next = clipFactory()
        try {
            stream.use(next::open)
            if (next.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                val control = next.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                val gain = gainDecibels(volumePercent).toFloat()
                control.value = gain.coerceIn(control.minimum, control.maximum)
            }
            next.loop(Clip.LOOP_CONTINUOUSLY)
            clip = next
        } catch (error: Throwable) {
            next.runCatching { close() }
            throw error
        }
    }

    override fun stop() {
        val previous = clip
        clip = null
        if (previous != null) {
            try {
                previous.stop()
            } finally {
                previous.close()
            }
        }
    }
}

internal fun gainDecibels(volumePercent: Int): Double = 20.0 * log10(volumePercent.coerceIn(1, 100) / 100.0)

internal fun toneBytes(): ByteArray {
    val samples = SAMPLE_RATE / 2
    val result = ByteArray(samples * 2)
    repeat(samples) { index ->
        val envelope = if (index < SAMPLE_RATE / 3) 1.0 else 0.0
        val sample = (sin(2 * PI * 880 * index / SAMPLE_RATE) * Short.MAX_VALUE * 0.3 * envelope).toInt().toShort()
        result[index * 2] = (sample.toInt() and 0xff).toByte()
        result[index * 2 + 1] = (sample.toInt() shr 8 and 0xff).toByte()
    }
    return result
}

internal const val SAMPLE_RATE = 22_050
