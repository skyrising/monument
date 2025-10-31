package de.skyrising.guardian.gen.mappings

import java.io.BufferedReader
import java.util.stream.Stream

interface MappingsParser {
    fun parse(reader: BufferedReader): MappingTree
}

interface MappingsWriter {
    fun write(mappings: MappingTree, writer: Appendable)
}

interface MappingsFormat : MappingsParser, MappingsWriter

abstract class LineBasedMappingFormat<T> : MappingsFormat {
    fun parse(initialState: T?, reader: BufferedReader): MappingTree {
        var state: T? = initialState
        reader.lineSequence().forEach {
            state = readLine(state, it)
        }
        return decodeState(state!!)
    }

    override fun parse(reader: BufferedReader) = parse(null, reader)

    override fun write(mappings: MappingTree, writer: Appendable) {
        getLines(mappings).forEach { writer.appendLine(it) }
    }

    abstract fun readLine(state: T?, line: String): T
    abstract fun decodeState(state: T): MappingTree
    abstract fun getLines(tree: MappingTree): Stream<String>
}

inline fun String.toDotted() = this.replace('/', '.')
inline fun String.toSlashed() = this.replace('.', '/')