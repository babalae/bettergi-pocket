package com.bettergi.pocket.pathing.executor

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointPickupTest {
    @Test
    fun `waits for a delayed item list and lets the final tap finish`() {
        var now = 0L
        var availableAt = 900L
        var remaining = 2
        var pendingTapAt: Long? = null
        EndpointPickup.collect(
            delay = { ms ->
                val until = now + ms
                pendingTapAt?.takeIf { it <= until }?.let {
                    remaining--
                    availableAt = it + 350L
                    pendingTapAt = null
                }
                now = until
            },
            pickup = {
                if (remaining > 0 && now >= availableAt && pendingTapAt == null) {
                    pendingTapAt = now + 50L
                }
            },
        )
        assertEquals(0, remaining)
        assertNull(pendingTapAt)
    }

    @Test
    fun `user cancellation aborts the endpoint sweep without more taps`() {
        var now = 0L
        var clicks = 0
        assertThrows(CancellationException::class.java) {
            EndpointPickup.collect(
                delay = { ms ->
                    now += ms
                    if (now >= 500L) throw CancellationException()
                },
                pickup = { clicks++ },
            )
        }
        assertEquals(1, clicks)
    }
}
