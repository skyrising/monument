package de.skyrising.guardian.gen

import kotlin.test.Test
import kotlin.test.assertEquals

class MergerTest {
    @Test
    fun testMergePreserveOrder() {
        val a = linkedSetOf("a", "b", "d", "f", "c")
        val b = linkedSetOf("b", "c", "d", "e", "f")
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), mergePreserveOrder(a, b).toList())
    }
}