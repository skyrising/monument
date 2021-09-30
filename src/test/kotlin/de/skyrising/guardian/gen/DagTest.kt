package de.skyrising.guardian.gen;

import kotlin.test.Test
import kotlin.test.assertEquals

private data class TestDagNode(val id: String, override val parents: List<TestDagNode> = emptyList()) : DagNode<TestDagNode> {
    override fun toString() = id
}

class DagTest {
    @Test
    fun testFindPredecessors() {
        val a = TestDagNode("a")
        val b = TestDagNode("b", listOf(a))
        val c = TestDagNode("c", listOf(b))
        val d = TestDagNode("d", listOf(a))
        val e = TestDagNode("e", listOf(d))
        val f = TestDagNode("f", listOf(c, e))
        assertEquals(setOf(a, b, c, d, e, f), findPredecessors(f, null))
        assertEquals(setOf(a, b, c, d, e, f), findPredecessors(f, a))
        assertEquals(listOf(a, b, c), findPredecessors(c, a).toList())
        assertEquals(listOf(b, c, f), findPredecessors(f, b).toList())
        assertEquals(emptySet(), findPredecessors(e, c))
        assertEquals(setOf(a, c, d, e, f), findPredecessors(f, a) {
            it != b
        })
        assertEquals(setOf(b, c, d, e, f), findPredecessors(f, null) {
            it != a
        })
    }
}
