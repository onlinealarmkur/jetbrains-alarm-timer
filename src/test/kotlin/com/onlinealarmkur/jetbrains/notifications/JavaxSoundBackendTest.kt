package com.onlinealarmkur.jetbrains.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.Clip
import javax.sound.sampled.Control
import javax.sound.sampled.FloatControl
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import kotlin.math.abs
import kotlin.math.sign

class JavaxSoundBackendTest {
    @Test
    fun `gain decibels follow the pinned percent to decibel curve`() {
        // 20 * log10(100 / 100.0) = 20 * 0 = 0
        assertEquals(0.0, gainDecibels(100), TIGHT_DELTA)
        // 20 * log10(50 / 100.0) = 20 * -0.301029995663981 = -6.020599913279624
        assertEquals(-6.0206, gainDecibels(50), 1e-3)
        // 20 * log10(1 / 100.0) = 20 * -2 = -40
        assertEquals(-40.0, gainDecibels(1), TIGHT_DELTA)
        // 20 * log10(70 / 100.0) = 20 * -0.154901959985743 = -3.098039199714864
        assertEquals(-3.0980392, gainDecibels(70), 1e-6)
        // 20 * log10(25 / 100.0) = 20 * -0.602059991327962 = -12.041199826559248
        assertEquals(-12.0411998, gainDecibels(25), 1e-6)
    }

    @Test
    fun `gain decibels clamp volumes outside one to one hundred`() {
        assertEquals(gainDecibels(1), gainDecibels(0))
        assertEquals(gainDecibels(1), gainDecibels(-5))
        assertEquals(gainDecibels(1), gainDecibels(Int.MIN_VALUE))
        assertEquals(gainDecibels(100), gainDecibels(150))
        assertEquals(gainDecibels(100), gainDecibels(Int.MAX_VALUE))
    }

    @Test
    fun `tone bytes keep the pinned half second buffer and one third second envelope`() {
        assertEquals(22_050, SAMPLE_RATE)
        val tone = toneBytes()

        assertEquals(SAMPLE_RATE / 2 * 2, tone.size)
        assertEquals(22_050, tone.size)
        assertEquals(11_025, tone.size / 2)

        val sustainSamples = SAMPLE_RATE / 3
        assertEquals(7_350, sustainSamples)
        assertEquals(sustainSamples - 1, tone.indexOfLast { it != ZERO_BYTE } / 2)

        val silence = tone.copyOfRange(sustainSamples * 2, tone.size)
        assertEquals(7_350, silence.size)
        assertTrue(silence.all { it == ZERO_BYTE }, "The envelope must silence every sample after the first third of a second.")

        val audible = (0 until sustainSamples).count { tone.sampleAt(it) != 0 }
        assertTrue(audible >= 7_300, "Expected a continuous tone in the sustain window, got $audible audible samples.")
    }

    @Test
    fun `tone bytes stay a little endian 880 hertz mono tone`() {
        val tone = toneBytes()

        // Sample 6 is 9808 (0x2650); little endian writes the low byte first.
        assertEquals(0x50.toByte(), tone[12])
        assertEquals(0x26.toByte(), tone[13])
        assertEquals(9_808, tone.sampleAt(6))

        // Sample 100 is -559 (0xFDD1) and pins the signed two's complement layout.
        assertEquals(0xD1.toByte(), tone[200])
        assertEquals(0xFD.toByte(), tone[201])
        assertEquals(-559, tone.sampleAt(100))

        assertEquals(0, tone.sampleAt(0))

        val sustainSamples = SAMPLE_RATE / 3
        val peak = (0 until sustainSamples).maxOf { abs(tone.sampleAt(it)) }
        assertEquals((Short.MAX_VALUE * 0.3).toInt(), peak)
        assertEquals(9_830, peak)

        // 880 Hz across the 1/3 second sustain is 293.33 cycles, so 586 sign changes.
        var signChanges = 0
        var previousSign = 0
        for (index in 0 until sustainSamples) {
            val currentSign = tone.sampleAt(index).sign
            if (currentSign == 0) continue
            if (previousSign != 0 && currentSign != previousSign) signChanges++
            previousSign = currentSign
        }
        assertEquals(586, signChanges)
    }

    @Test
    fun `play opens gains and loops a clip that stop closes`() {
        val events = mutableListOf<String>()
        val control = FakeGainControl(minimum = -80f, maximum = 6f)
        val clip = FakeClip("clip", events, gainControl = control)
        var created = 0
        val backend = JavaxSoundBackend(
            headless = { false },
            clipFactory = {
                created++
                clip
            },
        )

        backend.play(70)

        assertEquals(1, created)
        assertEquals(listOf("clip:open", "clip:isControlSupported", "clip:getControl", "clip:loop:-1"), events)
        assertEquals(-1, Clip.LOOP_CONTINUOUSLY)
        assertEquals(gainDecibels(70).toFloat(), control.value)
        assertEquals(-3.0980392f, control.value, 1e-6f)

        assertEquals(1, clip.openedStreams.size)
        val stream = clip.openedStreams[0]
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, stream.format.encoding)
        assertEquals(22_050f, stream.format.sampleRate)
        assertEquals(16, stream.format.sampleSizeInBits)
        assertEquals(1, stream.format.channels)
        assertEquals(2, stream.format.frameSize)
        assertFalse(stream.format.isBigEndian)
        assertEquals(11_025L, stream.frameLength)

        events.clear()
        backend.stop()
        assertEquals(listOf("clip:stop", "clip:close"), events)

        events.clear()
        backend.stop()
        assertTrue(events.isEmpty(), "A second stop must not touch a clip that was already released.")
    }

    @Test
    fun `a second play stops and closes the previous clip before opening the next`() {
        val events = mutableListOf<String>()
        val clips = listOf(
            FakeClip("first", events, gainControl = FakeGainControl(minimum = -80f, maximum = 6f)),
            FakeClip("second", events, gainControl = FakeGainControl(minimum = -80f, maximum = 6f)),
        )
        var created = 0
        val backend = JavaxSoundBackend(headless = { false }, clipFactory = { clips[created++] })

        backend.play(70)
        backend.play(40)

        assertEquals(2, created)
        assertEquals(
            listOf(
                "first:open",
                "first:isControlSupported",
                "first:getControl",
                "first:loop:-1",
                "first:stop",
                "first:close",
                "second:open",
                "second:isControlSupported",
                "second:getControl",
                "second:loop:-1",
            ),
            events,
        )
    }

    @Test
    fun `a failing open closes the clip rethrows and retains nothing`() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("no audio line")
        val clip = FakeClip("clip", events, gainControl = FakeGainControl(minimum = -80f, maximum = 6f), openFailure = failure)
        val backend = JavaxSoundBackend(headless = { false }, clipFactory = { clip })

        val thrown = assertThrows(IllegalStateException::class.java) { backend.play(70) }

        assertSame(failure, thrown)
        assertEquals(listOf("clip:open", "clip:close"), events)

        backend.stop()
        assertEquals(listOf("clip:open", "clip:close"), events)
    }

    @Test
    fun `a headless environment never creates a clip`() {
        val events = mutableListOf<String>()
        var created = 0
        val backend = JavaxSoundBackend(
            headless = { true },
            clipFactory = {
                created++
                FakeClip("clip", events)
            },
        )

        backend.play(70)
        backend.stop()

        assertEquals(0, created)
        assertTrue(events.isEmpty(), "A headless IDE must stay silent.")
    }

    @Test
    fun `a volume of zero or less never creates a clip`() {
        val events = mutableListOf<String>()
        var created = 0
        val backend = JavaxSoundBackend(
            headless = { false },
            clipFactory = {
                created++
                FakeClip("clip", events)
            },
        )

        backend.play(0)
        backend.play(-5)
        backend.stop()

        assertEquals(0, created)
        assertTrue(events.isEmpty(), "A muted alarm must not open an audio line.")
    }

    @Test
    fun `a clip without a master gain control still loops`() {
        val events = mutableListOf<String>()
        val clip = FakeClip("clip", events)
        val backend = JavaxSoundBackend(headless = { false }, clipFactory = { clip })

        backend.play(70)

        assertEquals(listOf("clip:open", "clip:isControlSupported", "clip:loop:-1"), events)
        assertFalse(events.contains("clip:getControl"))
    }

    @Test
    fun `the gain is clamped into the range the control reports`() {
        val events = mutableListOf<String>()
        val control = FakeGainControl(minimum = -1.5f, maximum = 6f)
        val clip = FakeClip("clip", events, gainControl = control)
        val backend = JavaxSoundBackend(headless = { false }, clipFactory = { clip })

        backend.play(50)

        assertTrue(
            gainDecibels(50) < control.minimum.toDouble(),
            "This case only proves clamping while 50% sits below the control minimum.",
        )
        assertEquals(-1.5f, control.value)
        assertEquals(listOf("clip:open", "clip:isControlSupported", "clip:getControl", "clip:loop:-1"), events)
    }

    private fun ByteArray.sampleAt(index: Int): Int =
        ((this[index * 2 + 1].toInt() shl 8) or (this[index * 2].toInt() and 0xff)).toShort().toInt()

    /** A master gain control with a settable range, so clamping can be observed. */
    private class FakeGainControl(minimum: Float, maximum: Float) : FloatControl(
        FloatControl.Type.MASTER_GAIN,
        minimum,
        maximum,
        0.01f,
        1,
        maximum,
        "dB",
    )

    /**
     * Records the [Clip] calls the backend makes. Every method the backend must
     * not touch throws, so an accidental new dependency on the audio line fails
     * the test instead of passing silently.
     */
    private class FakeClip(
        private val name: String,
        private val events: MutableList<String>,
        private val gainControl: FloatControl? = null,
        private val openFailure: Throwable? = null,
    ) : Clip {
        val openedStreams = mutableListOf<AudioInputStream>()

        override fun open(stream: AudioInputStream) {
            events += "$name:open"
            openedStreams += stream
            val failure = openFailure
            if (failure != null) throw failure
        }

        override fun isControlSupported(control: Control.Type): Boolean {
            events += "$name:isControlSupported"
            return gainControl != null && control == FloatControl.Type.MASTER_GAIN
        }

        override fun getControl(control: Control.Type): Control {
            events += "$name:getControl"
            val supported = gainControl
            if (supported == null || control != FloatControl.Type.MASTER_GAIN) {
                throw IllegalArgumentException("FakeClip does not support the control $control.")
            }
            return supported
        }

        override fun loop(count: Int) {
            events += "$name:loop:$count"
        }

        override fun stop() {
            events += "$name:stop"
        }

        override fun close() {
            events += "$name:close"
        }

        // Every remaining Clip, DataLine and Line method is inert on purpose.
        override fun open(format: AudioFormat, data: ByteArray, offset: Int, bufferSize: Int) {
            unsupported()
        }

        override fun open() {
            unsupported()
        }

        override fun getFrameLength(): Int = unsupported()

        override fun getMicrosecondLength(): Long = unsupported()

        override fun setFramePosition(frames: Int) {
            unsupported()
        }

        override fun setMicrosecondPosition(microseconds: Long) {
            unsupported()
        }

        override fun setLoopPoints(start: Int, end: Int) {
            unsupported()
        }

        override fun drain() {
            unsupported()
        }

        override fun flush() {
            unsupported()
        }

        override fun start() {
            unsupported()
        }

        override fun isRunning(): Boolean = unsupported()

        override fun isActive(): Boolean = unsupported()

        override fun getFormat(): AudioFormat = unsupported()

        override fun getBufferSize(): Int = unsupported()

        override fun available(): Int = unsupported()

        override fun getFramePosition(): Int = unsupported()

        override fun getLongFramePosition(): Long = unsupported()

        override fun getMicrosecondPosition(): Long = unsupported()

        override fun getLevel(): Float = unsupported()

        override fun getLineInfo(): Line.Info = unsupported()

        override fun isOpen(): Boolean = unsupported()

        override fun getControls(): Array<Control> = unsupported()

        override fun addLineListener(listener: LineListener) {
            unsupported()
        }

        override fun removeLineListener(listener: LineListener) {
            unsupported()
        }

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("$name was asked for an audio operation the alarm must not use.")
    }

    private companion object {
        const val ZERO_BYTE: Byte = 0
        const val TIGHT_DELTA = 1e-9
    }
}
