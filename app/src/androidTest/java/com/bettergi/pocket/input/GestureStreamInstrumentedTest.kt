package com.bettergi.pocket.input

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettergi.pocket.debug.GestureProbeActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 需要已连接无障碍；真实经过系统输入派发，40 次点击必须全部送达测试界面。 */
@RunWith(AndroidJUnit4::class)
class GestureStreamInstrumentedTest {
    @Test
    fun sustainedMovementAndRepeatedClicksDeliverEveryTouchAndReleaseAll() {
        InputAccessibilityService.attach(InstrumentationRegistry.getInstrumentation().targetContext)
        val connectedDeadline = SystemClock.uptimeMillis() + 5000L
        while (!InputAccessibilityService.isConnected() && SystemClock.uptimeMillis() < connectedDeadline) SystemClock.sleep(50)
        assertTrue("请先连接无障碍服务", InputAccessibilityService.isConnected())
        assertTrue(InputAccessibilityService.releaseAllTouchesAndWait(3000L))
        ActivityScenario.launch(GestureProbeActivity::class.java).use { scenario ->
            SystemClock.sleep(500)
            try {
                assertTrue(InputAccessibilityService.padHold(200, 500))
                SystemClock.sleep(100)
                assertTrue(InputAccessibilityService.dragBegin(600, 500))
                SystemClock.sleep(100)
                repeat(40) { index ->
                    assertTrue(InputAccessibilityService.tap(900, 500, 25L))
                    SystemClock.sleep(100)
                    assertTrue(InputAccessibilityService.dragStep(600 + index % 2 * 4, 500, 30L))
                    SystemClock.sleep(100)
                    if (index % 5 == 4) {
                        assertTrue(InputAccessibilityService.dragEnd())
                        SystemClock.sleep(80)
                        assertTrue(InputAccessibilityService.dragBegin(600, 500))
                        SystemClock.sleep(80)
                    }
                }
            } finally {
                assertTrue("测试结束必须实际抬手", InputAccessibilityService.releaseAllTouchesAndWait(3000L))
            }
            SystemClock.sleep(200)
            scenario.onActivity {
                assertEquals("不能把手势提交成功当作实际送达", 40, it.tapDowns)
                assertTrue(it.errors.joinToString(), it.errors.isEmpty())
                assertTrue("退出后仍有触点按下", it.activePointers.isEmpty())
            }
        }
    }
}
