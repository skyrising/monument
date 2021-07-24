package de.skyrising.guardian.gen.mappings

import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.*

class MappingsTest {
    private fun checkCommonTree(tree: MappingTree) {
        val cls1 = tree.classes["pkg/Class1"]
        assertNotNull(cls1)
        assertEquals("pkg/Class1", cls1[0])
        assertEquals("a", cls1[1])
        val intField = cls1.fields["IntField", "I"]
        assertNotNull(intField)
        assertEquals("IntField", intField[0])
        assertEquals("a", intField[1])
        val floatField = cls1.fields["FloatField", "F"]
        assertNotNull(floatField)
        assertEquals("FloatField", floatField[0])
        assertEquals("b", floatField[1])
        val cls2Field = cls1.fields["Cls2Field", "Lpkg/Class2;"]
        assertNotNull(cls2Field)
        assertEquals("Cls2Field", cls2Field[0])
        assertEquals("c", cls2Field[1])
        val initMethod = cls1.methods["<init>", "()V"]
        assertNotNull(initMethod)
        assertEquals("<init>", initMethod[0])
        assertEquals("<init>", initMethod[1])
        val method1 = cls1.methods["method1", "([Lpkg/Class1;I)Lpkg/Class2;"]
        assertNotNull(method1)
        assertEquals("method1", method1[0])
        assertEquals("a", method1[1])
        assertEquals(3, cls1.fields.size)
        assertEquals(2, cls1.methods.size)
        val cls2 = tree.classes["pkg/Class2"]
        assertNotNull(cls2)
        assertEquals("pkg/Class2", cls2[0])
        assertEquals("b", cls2[1])
        assertEquals(0, cls2.fields.size)
        assertEquals(0, cls2.methods.size)
    }

    @Test
    fun parseProguard() {
        val tree = MappingsTest::class.java.getResourceAsStream("/test_proguard.txt")?.reader()?.buffered().use {
            assertNotNull(it)
            ProguardMappings.parse(BufferedReader(it))
        }
        checkCommonTree(tree)
        val cls1 = tree.classes["pkg/Class1"]!!
        val initMethod = cls1.methods["<init>", "()V"]!!
        assertTrue(initMethod is ProguardMethodMapping)
        assertEquals(11, initMethod.lineFrom)
        assertEquals(14, initMethod.lineTo)
        val method1 = cls1.methods["method1", "([Lpkg/Class1;I)Lpkg/Class2;"]!!
        assertFalse(method1 is ProguardMethodMapping)
    }

    @Test
    fun testTinyV1() {
        MappingsTest::class.java.getResourceAsStream("/test_tiny_v1.tiny")?.reader()?.buffered().use {
            assertNotNull(it)
            it.mark(8192)
            val v1 = TinyMappingsV1.parse(it)
            checkCommonTree(v1)
            it.reset()
            val generic = GenericTinyReader.parse(it)
            assertEquals(v1, generic)
        }
    }

    @Test
    fun testTinyV2() {
        MappingsTest::class.java.getResourceAsStream("/test_tiny_v2.tiny")?.reader()?.buffered().use {
            assertNotNull(it)
            it.mark(8192)
            val v2 = TinyMappingsV2.parse(it)
            checkCommonTree(v2)
            it.reset()
            val generic = GenericTinyReader.parse(it)
            assertEquals(v2, generic)
        }
    }

    @Test
    fun testInvert() {
        val reader = MappingsTest::class.java.getResourceAsStream("/test_proguard.txt")?.reader()
        assertNotNull(reader)
        val tree = ProguardMappings.parse(BufferedReader(reader)).invert(1)
        val cls1 = tree.classes["a"]
        assertNotNull(cls1)
        assertEquals("a", cls1[0])
        assertEquals("pkg/Class1", cls1[1])
        val intField = cls1.fields["a", "I"]
        assertNotNull(intField)
        assertEquals("a", intField[0])
        assertEquals("IntField", intField[1])
        val floatField = cls1.fields["b", "F"]
        assertNotNull(floatField)
        assertEquals("b", floatField[0])
        assertEquals("FloatField", floatField[1])
        val cls2Field = cls1.fields["c", "Lb;"]
        assertNotNull(cls2Field)
        assertEquals("c", cls2Field[0])
        assertEquals("Cls2Field", cls2Field[1])
        val initMethod = cls1.methods["<init>", "()V"]
        assertNotNull(initMethod)
        assertTrue(initMethod is ProguardMethodMapping)
        assertEquals(11, initMethod.lineFrom)
        assertEquals(14, initMethod.lineTo)
        assertEquals("<init>", initMethod[0])
        assertEquals("<init>", initMethod[1])
        val method1 = cls1.methods["a", "([La;I)Lb;"]
        assertNotNull(method1)
        assertFalse(method1 is ProguardMethodMapping)
        assertEquals("a", method1[0])
        assertEquals("method1", method1[1])
        assertEquals(3, cls1.fields.size)
        assertEquals(2, cls1.methods.size)
        val cls2 = tree.classes["b"]
        assertNotNull(cls2)
        assertEquals("b", cls2[0])
        assertEquals("pkg/Class2", cls2[1])
        assertEquals(0, cls2.fields.size)
        assertEquals(0, cls2.methods.size)
    }

    @Test
    fun writeProguard() {
        val txt = MappingsTest::class.java.getResourceAsStream("/test_proguard.txt")?.readBytes()?.toString(Charsets.UTF_8)
        assertNotNull(txt)
        val tree = ProguardMappings.parse(BufferedReader(StringReader(txt)))
        val result = tree.toString(ProguardMappings)
        assertEquals(txt.split('\n').let { it.subList(1, it.size) }, result.split('\n'))
    }
}