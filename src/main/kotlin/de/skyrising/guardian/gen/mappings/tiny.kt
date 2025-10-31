package de.skyrising.guardian.gen.mappings

import java.io.BufferedReader
import java.util.stream.Stream

object GenericTinyReader : MappingsParser {
    override fun parse(reader: BufferedReader): MappingTree {
        val headerLine = reader.readLine() ?: throw IllegalStateException("Tiny file is empty")
        val header = readHeader(headerLine)
        return when(header.major) {
            1 -> {
                if (header.minor != 0) throw UnsupportedOperationException("Tiny version ${header.major}.${header.minor} not supported")
                TinyMappingsV1.parse(TinyStateV1(header), reader)
            }
            2 -> {
                if (header.minor != 0) throw UnsupportedOperationException("Tiny version ${header.major}.${header.minor} not supported")
                TinyMappingsV2.parse(TinyStateV2(header), reader)
            }
            else -> throw UnsupportedOperationException("Tiny version ${header.major}.${header.minor} not supported")
        }
    }

    fun readHeader(line: String): TinyHeader {
        val parts = line.split("\t")
        if (parts.isEmpty()) throw IllegalArgumentException("Invalid tiny header: empty")
        return when (parts[0]) {
            "v1" -> TinyHeader(1, 0, parts.subList(1, parts.size))
            "tiny" -> TinyHeader(parts[1].toInt(), parts[2].toInt(), parts.subList(3, parts.size))
            else -> throw IllegalArgumentException("Invalid tiny header: '${parts[0]}'")
        }
    }
}

object TinyMappingsV1 : LineBasedMappingFormat<TinyStateV1>() {
    override fun readLine(state: TinyStateV1?, line: String): TinyStateV1 {
        if (state == null) {
            val header = GenericTinyReader.readHeader(line)
            if (header.major != 1 || header.minor != 0) throw UnsupportedOperationException("Tiny version ${header.major}.${header.minor} not supported")
            return TinyStateV1(header)
        }
        val parts = line.split("\t")
        if (parts.isEmpty()) return state
        val tree = state.tree
        when (parts[0]) {
            "CLASS" -> {
                tree.classes.add(ClassMapping(parts.subList(1, parts.size).toTypedArray()))
            }
            "FIELD" -> {
                val classMapping = tree.classes[parts[1]] ?: throw IllegalStateException("Class ${parts[1]} for field ${parts[3]} not defined")
                classMapping.fields.add(FieldMappingImpl(MemberDescriptor(parts[3], parts[2]), parts.subList(3, parts.size).toTypedArray()))
            }
            "METHOD" -> {
                val classMapping = tree.classes[parts[1]] ?: throw IllegalStateException("Class ${parts[1]} for method ${parts[3]} not defined")
                classMapping.methods.add(MethodMappingImpl(MemberDescriptor(parts[3], parts[2]), parts.subList(3, parts.size).toTypedArray()))
            }
        }
        return state
    }

    override fun decodeState(state: TinyStateV1) = state.tree

    override fun getLines(tree: MappingTree): Stream<String> {
        TODO("Not yet implemented")
    }
}

open class TinyState<T : MappingTree>(val header: TinyHeader, val tree: T)
class TinyStateV1(header: TinyHeader) : TinyState<MappingTree>(header, MappingTree(header.namespaces.toTypedArray()))

object TinyMappingsV2 : LineBasedMappingFormat<TinyStateV2>() {
    override fun readLine(state: TinyStateV2?, line: String): TinyStateV2 {
        if (state == null) {
            val header = GenericTinyReader.readHeader(line)
            if (header.major != 2 || header.minor != 0) throw UnsupportedOperationException("Tiny version ${header.major}.${header.minor} not supported")
            return TinyStateV2(header)
        }
        val parts = line.split('\t')
        var indent = 0
        while (parts[indent] == "") indent++
        if (indent > state.indent + 1) throw IllegalArgumentException("Invalid indent level")
        if (indent == 0) state.currentClass = null
        if (indent <= 1) state.currentMember = null
        when (parts[indent]) {
            "c" -> {
                if (indent == 0) {
                    val names = parts.subList(1, parts.size).map(state::unescape)
                    val c = ClassMapping(names.toTypedArray())
                    state.currentClass = c
                    if (state.tree.classes.containsKey(c.defaultName)) {
                        throw IllegalArgumentException("Duplicate class name ${c.defaultName}")
                    }
                    state.tree.classes.add(c)
                }
            }
            "f" -> {
                if (indent == 1) {
                    val type = parts[2]
                    val names = parts.subList(3, parts.size).map(state::unescape)
                    val f = FieldMappingImpl(MemberDescriptor(names[0], type), names.toTypedArray())
                    state.currentMember = f
                    val c = state.currentClass ?: throw IllegalStateException("Field without a class")
                    if (c.fields.containsKey(f.defaultName)) {
                        throw IllegalArgumentException("Duplicate field name ${f.defaultName}")
                    }
                    c.fields.add(f)
                }
            }
            "m" -> {
                if (indent == 1) {
                    val type = parts[2]
                    val names = parts.subList(3, parts.size).map(state::unescape)
                    val m = MethodMappingImpl(MemberDescriptor(names[0], type), names.toTypedArray())
                    state.currentMember = m
                    val c = state.currentClass ?: throw IllegalStateException("Method without a class")
                    if (c.methods.containsKey(m.defaultName)) {
                        throw IllegalArgumentException("Duplicate method name ${m.defaultName}")
                    }
                    c.methods.add(m)
                }
            }
            else -> println(parts)
        }
        return state
    }

    override fun decodeState(state: TinyStateV2) = state.tree

    override fun getLines(tree: MappingTree): Stream<String> {
        TODO("Not yet implemented")
    }

    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var escaped = false
        for (c in s) {
            if (escaped) {
                sb.append(when (c) {
                  '\\' -> '\\'
                  'n' -> '\n'
                  'r' -> '\r'
                  't' -> '\t'
                  '0' -> '\u0000'
                  else -> throw IllegalArgumentException("Invalid escape code\\$c")
                })
                escaped = false
            } else {
                if (c == '\\') {
                    escaped = true
                    continue
                }
                sb.append(c)
            }
        }
        if (escaped) throw IllegalArgumentException("Unterminated escape code")
        return sb.toString()
    }
}

class TinyStateV2(header: TinyHeader) : TinyState<TinyMappingTreeV2>(header, TinyMappingTreeV2(header.namespaces.toTypedArray())) {
    var currentClass: ClassMapping? = null
    var currentMember: MemberMapping? = null
    var indent = 0

    fun unescape(s: String) = if (tree.properties.containsKey("escaped-names")) TinyMappingsV2.unescape(s) else s
}

data class TinyHeader(val major: Int, val minor: Int, val namespaces: List<String>)

class TinyMappingTreeV2(namespaces: Array<String>) : MappingTree(namespaces) {
    val properties = linkedMapOf<String, String>()
}