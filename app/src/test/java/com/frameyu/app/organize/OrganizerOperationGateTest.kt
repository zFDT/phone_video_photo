package com.frameyu.app.organize

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizerOperationGateTest {
    @Test
    fun `同一时刻只允许一次扫描或删除操作`() {
        val gate = OrganizerOperationGate()

        assertTrue(gate.tryStart(OrganizerOperation.EXACT_SCAN))
        assertTrue(gate.isRunning())
        assertFalse(gate.tryStart(OrganizerOperation.EXACT_SCAN))
        assertFalse(gate.tryStart(OrganizerOperation.SIMILAR_SCAN))
        assertFalse(gate.tryStart(OrganizerOperation.DELETION))

        gate.finish(OrganizerOperation.SIMILAR_SCAN)
        assertFalse(gate.tryStart(OrganizerOperation.DELETION))
        gate.finish(OrganizerOperation.EXACT_SCAN)
        assertFalse(gate.isRunning())
        assertTrue(gate.tryStart(OrganizerOperation.DELETION))
    }
}
