package com.localai.app

import com.localai.app.data.automation.Automation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class AutomationTest {
    private fun at(h: Int, m: Int) = Calendar.getInstance().apply { set(2026, 10, 17, h, m, 0); set(Calendar.MILLISECOND, 0) }

    @Test fun digestRunsLaterTodayOrTomorrow() {
        assertEquals(90 * 60_000L, Automation.delayUntil(21, 0, at(19, 30)))           // later today
        assertEquals((24 * 60 - 30) * 60_000L, Automation.delayUntil(21, 0, at(21, 30))) // already passed → tomorrow
        assertEquals(24 * 3_600_000L, Automation.delayUntil(21, 0, at(21, 0)))           // exactly now → next day
    }
}
