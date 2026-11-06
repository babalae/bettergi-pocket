package com.bettergi.pocket.pathing.party

import com.bettergi.pocket.capture.CapturedBgrFrame
import com.bettergi.pocket.pathing.*
import com.bettergi.pocket.pathing.executor.PathingFrameSource
import com.bettergi.pocket.pathing.executor.PathingRuntime
import com.bettergi.pocket.pathing.input.GameInput
import com.bettergi.pocket.pathing.input.TouchClient
import com.bettergi.pocket.pathing.model.HandledException
import com.bettergi.pocket.pathing.model.PathAction
import com.bettergi.pocket.pathing.settings.PathingSettings
import com.bettergi.pocket.pathing.vision.UnavailablePathingVision
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.IntSize
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.CancellationException

class PathingPartyTest {
    @Test fun `multiple walking candidates skip absent characters and honor configured order`() {
        val game = FakeGame()
        val party = game.party(PathingSettings(mainAvatars = listOf("夜兰", "丽莎", "迪希雅")))
        party.prepare(emptySet())
        party.walk()
        assertEquals("丽莎", game.active)
    }

    @Test fun `multiple elemental candidates skip absent characters but explicit empty disables collection`() {
        val game = FakeGame()
        val party = game.party(PathingSettings(elementAvatars = mapOf("Hydro" to listOf("心海", "芭芭拉"))))
        party.prepare(setOf(PathAction.HydroCollect))
        party.collect(PathAction.HydroCollect)
        assertTrue(game.events.contains("attack:芭芭拉"))
        val disabled = FakeGame()
        assertThrows(HandledException::class.java) {
            disabled.party(PathingSettings(elementAvatars = mapOf("Hydro" to emptyList())))
                .prepare(setOf(PathAction.HydroCollect))
        }
    }

    @Test fun `guardian short candidates are ordered and long candidates are fallback`() {
        val game = FakeGame()
        val party = game.party(PathingSettings(guardianShortAvatars = listOf("七七", "丽莎", "芭芭拉"),
            guardianLongAvatars = listOf("钟离")))
        party.prepare(emptySet())
        assertTrue(party.renewGuardian())
        assertTrue(game.events.contains("skill:丽莎"))
        assertFalse(game.events.any { it.startsWith("down:skill") })
    }

    @Test fun `discovers hidden fourth member and follows reordered portrait positions`() {
        val game = FakeGame()
        val party = game.party(PathingSettings(mainAvatars = listOf("迪希雅")))
        party.prepare(emptySet())
        assertEquals("钟离", game.active)
        party.walk()
        assertEquals("迪希雅", game.active)
        val taps = game.events.count { it == "switch" }
        party.walk()
        assertEquals(taps, game.events.count { it == "switch" })
    }

    @Test fun `element collection chooses normal attack and restores walking role`() {
        val game = FakeGame()
        val party = game.party(PathingSettings(mainAvatars = listOf("迪希雅")))
        party.prepare(setOf(PathAction.HydroCollect))
        party.collect(PathAction.HydroCollect)
        assertTrue(game.events.contains("attack:芭芭拉"))
        assertFalse(game.events.any { it.startsWith("skill:") })
        assertEquals("迪希雅", game.active)
    }

    @Test fun `element skill waits for cooldown on repeated collection`() {
        val game = FakeGame()
        val party = game.party(PathingSettings())
        party.prepare(setOf(PathAction.PyroCollect))
        party.collect(PathAction.PyroCollect)
        val first = game.time
        party.collect(PathAction.PyroCollect)
        assertTrue(game.time - first >= 20_000)
        assertEquals(2, game.events.count { it == "skill:迪希雅" })
    }

    @Test fun `missing elemental character rejects route before elemental input`() {
        val game = FakeGame()
        val party = game.party(PathingSettings())
        assertThrows(HandledException::class.java) { party.prepare(setOf(PathAction.AnemoCollect)) }
        assertFalse(game.events.any { it.startsWith("skill:") || it.startsWith("attack:") })
    }

    @Test fun `failed switch aborts instead of treating unchanged portraits as success`() {
        val game = FakeGame().apply { switchingEnabled = false }
        assertThrows(HandledException::class.java) { game.party(PathingSettings()).prepare(emptySet()) }
        assertEquals(3, game.events.count { it == "switch" })
    }

    @Test fun `guardian long skill respects interval and restores walker`() {
        val game = FakeGame()
        val party = game.party(PathingSettings(mainAvatars = listOf("迪希雅"), guardianShortAvatars = emptyList(), guardianLongAvatars = listOf("钟离"), guardianIntervalSeconds = 12.0))
        party.prepare(emptySet())
        party.walk()
        assertTrue(party.renewGuardian())
        assertTrue(game.events.contains("down:skill:钟离"))
        assertTrue(game.events.contains("up:skill"))
        assertEquals("迪希雅", game.active)
        assertFalse(party.renewGuardian())
        game.time += 12_000
        assertTrue(party.renewGuardian())
    }

    @Test fun `cancellation releases held elemental skill`() {
        val game = FakeGame()
        val party = game.party(PathingSettings(guardianShortAvatars = emptyList(), guardianLongAvatars = listOf("钟离")))
        party.prepare(emptySet())
        game.cancelOnSkill = true
        assertThrows(CancellationException::class.java) { party.renewGuardian() }
        assertTrue(game.events.contains("up:skill"))
    }

    private class FakeGame : TouchClient {
        val team = listOf(
            PartyAvatar("迪希雅", "Dehya", "Pyro", false, true, 20.0, 0.0),
            PartyAvatar("钟离", "Zhongli", "Geo", false, false, 4.0, 12.0),
            PartyAvatar("芭芭拉", "Barbara", "Hydro", true, true, 32.0, 0.0),
            PartyAvatar("丽莎", "Lisa", "Electro", true, true, 1.0, 16.0),
        )
        var active = "迪希雅"
        var time = 1_000L
        var switchingEnabled = true
        var cancelOnSkill = false
        val events = ArrayList<String>()
        private val visible get() = team.filter { it.name != active }
        private val source = object : PathingFrameSource {
            override fun isCapturing() = true
            override fun acquireNewer(afterSequence: Long, timeoutMs: Long) =
                CapturedBgrFrame(1, 1, Mat(1, 1, CvType.CV_8UC3), afterSequence + 1)
        }
        val runtime = PathingRuntime(source, sleep = { time += it }, nanoTime = { time * 1_000_000 })
        fun party(settings: PathingSettings): PathingParty {
            val layout = PathingLayout(IntSize(500, 500), null,
                joystick = JoystickLayout(100, 400, 40),
                buttons = mapOf(GameButton.Attack to ControlPoint(300, 300), GameButton.Skill to ControlPoint(400, 400)))
            return PathingParty(runtime, GameInput(layout, 500, 500, this), PartyRecognizer {
                PartySnapshot(visible.mapIndexed { index, avatar -> VisibleAvatar(avatar, IntRect(10, index * 30, 10, 10), 1f) })
            }, settings, UnavailablePathingVision, {}, { time })
        }
        override fun tap(x: Int, y: Int, durationMs: Long): Boolean {
            when {
                x < 100 -> { events += "switch"; if (switchingEnabled) active = visible[y / 30].name }
                x == 300 -> events += "attack:$active"
                x == 400 -> events += "skill:$active"
            }
            return true
        }
        override fun buttonDown(id: String, x: Int, y: Int): Boolean {
            events += "down:$id:$active"
            if (cancelOnSkill) runtime.cancel()
            return true
        }
        override fun buttonUp(id: String): Boolean { events += "up:$id"; return true }
        override fun padHold(x: Int, y: Int) = true
        override fun padRelease() = true
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
        override fun dragBegin(x: Int, y: Int) = true
        override fun dragStep(x: Int, y: Int, durationMs: Long) = true
        override fun dragEnd() = true
        override fun releaseAll() = true
    }

    companion object {
        @JvmStatic @BeforeClass fun openCv() = PathingTestData.ensureOpenCv()
    }
}
