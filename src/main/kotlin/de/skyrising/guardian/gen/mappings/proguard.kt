package de.skyrising.guardian.gen.mappings

import org.objectweb.asm.Type
import java.util.stream.Stream

object ProguardMappings : LineBasedMappingFormat<Pair<MappingTree, ClassMapping?>>() {
    override fun readLine(state: Pair<MappingTree, ClassMapping?>?, line: String): Pair<MappingTree, ClassMapping?> {
        val mappings = state?.first ?: MappingTree(arrayOf("named", "official"))
        var currentClass = state?.second
        when(line[0]) {
            '#' -> {}
            ' ' -> parseMember(currentClass, line)
            else -> {
                val cls = parseClass(line)
                currentClass = cls
                mappings.classes.add(cls)
            }
        }
        return Pair(mappings, currentClass)
    }

    override fun decodeState(state: Pair<MappingTree, ClassMapping?>) = state.first

    override fun getLines(tree: MappingTree): Stream<String> = tree.classes.stream().flatMap { cls ->
        Stream.of(
            Stream.of("${cls[0].toDotted()} -> ${cls[1].toDotted()}:"),
            cls.fields.stream().map { f ->
                "    ${getTypeName(f.defaultName.type)} ${f[0]} -> ${f[1]}"
            },
            cls.methods.stream().map { m ->
                val sb = StringBuilder("    ")
                if (m is ProguardMethodMapping) {
                    val lineFrom = m.lineFrom
                    val lineTo = m.lineTo
                    sb.append(lineFrom).append(':').append(lineTo).append(':')
                }
                val type = Type.getMethodType(m.defaultName.type)
                sb.append(getTypeName(type.returnType.descriptor)).append(' ')
                sb.append(m[0]).append('(')
                var first = true
                for (arg in type.argumentTypes) {
                    if (first) first = false
                    else sb.append(',')
                    sb.append(getTypeName(arg.descriptor))
                }
                sb.append(") -> ").append(m[1])
                sb.toString()
            }
        ).flatMap { it }
    }

    private fun parseClass(line: String): ClassMapping {
        val arrow = line.indexOf(" -> ")
        if (arrow < 0 || line[line.lastIndex] != ':') throw IllegalArgumentException("Expected '->' and ':' for class mapping")
        val from = line.substring(0, arrow).toSlashed()
        val to = line.substring(arrow + 4, line.lastIndex).toSlashed()
        return ClassMapping(arrayOf(from, to))
    }

    private fun parseMember(currentClass: ClassMapping?, line: String) {
        if (currentClass == null) throw IllegalStateException("Cannot parse class member without a class")
        if (!line.startsWith("    ")) throw IllegalArgumentException("Expected line to start with '    '")
        when (line[4]) {
            in '0' .. '9' -> parseMethodWithLines(currentClass, line)
            in 'a' .. 'z', in 'A' .. 'Z' -> {
                if (line.contains('(')) {
                    parseMethod(currentClass, line)
                } else {
                    parseField(currentClass, line)
                }
            }
            else -> throw IllegalArgumentException("Expected method or field mapping, got '$line'")
        }
    }

    private fun parseField(currentClass: ClassMapping, line: String) {
        val space = line.indexOf(' ', 4)
        val arrow = line.indexOf(" -> ", space + 1)
        if (space < 0 || arrow < 0) throw IllegalArgumentException("Invalid field mapping '$line'")
        val fieldType = getTypeDescriptor(line.substring(4, space))
        val from = line.substring(space + 1, arrow)
        val to = line.substring(arrow + 4)
        currentClass.fields.add(FieldMappingImpl(MemberDescriptor(from, fieldType), arrayOf(from, to)))
    }

    private fun parseMethod(currentClass: ClassMapping, line: String) {
        val space = line.indexOf(' ', 4)
        val openParen = line.indexOf('(', space + 1)
        val closeParenArrow = line.indexOf(") -> ", openParen + 1)
        if (space < 0 || openParen < 0 || closeParenArrow < 0) {
            throw IllegalArgumentException("Invalid method mapping '$line'")
        }
        val from = line.substring(space + 1, openParen)
        val desc = parseMethodDescriptor(line, 4, space, openParen, closeParenArrow)
        val to = line.substring(closeParenArrow + 5)
        currentClass.methods.add(MethodMappingImpl(MemberDescriptor(from, desc), arrayOf(from, to)))
    }

    private fun parseMethodWithLines(currentClass: ClassMapping, line: String) {
        val colon1 = line.indexOf(':', 4)
        val colon2 = line.indexOf(':', colon1 + 1)
        val space = line.indexOf(' ', colon2 + 1)
        val openParen = line.indexOf('(', space + 1)
        val closeParenArrow = line.indexOf(") -> ", openParen + 1)
        if (colon1 < 0 || colon2 < 0 || space < 0 || openParen < 0 || closeParenArrow < 0) {
            throw IllegalArgumentException("Invalid method mapping '$line'")
        }
        val lineFrom = line.substring(4, colon1).toInt()
        val lineTo = line.substring(colon1 + 1, colon2).toInt()
        val from = line.substring(space + 1, openParen)
        val desc = parseMethodDescriptor(line, colon2 + 1, space, openParen, closeParenArrow)
        val to = line.substring(closeParenArrow + 5)
        currentClass.methods.add(ProguardMethodMapping(lineFrom, lineTo, MemberDescriptor(from, desc), arrayOf(from, to)))
    }

    private fun parseMethodDescriptor(line: String, start: Int, space: Int, openParen: Int, closeParen: Int): String {
        val desc = StringBuilder("(")
        var i = openParen + 1
        while (i < closeParen) {
            var argEnd = line.indexOf(',', i)
            if (argEnd < 0) argEnd = closeParen
            desc.append(getTypeDescriptor(line.substring(i, argEnd)))
            i = argEnd + 1
        }
        desc.append(")").append(getTypeDescriptor(line.substring(start, space)))
        return desc.toString()
    }

    private fun getTypeDescriptor(type: String): String = when {
        type.endsWith("[]") -> "[" + getTypeDescriptor(type.substring(0, type.length - 2))
        type in PRIMITIVE_DESCRIPTORS -> PRIMITIVE_DESCRIPTORS[type]!!
        else -> "L" + type.toSlashed() + ";"
    }

    private fun getTypeName(type: String): String = when {
        type.startsWith("[") -> getTypeName(type.substring(1)) + "[]"
        type in PRIMITIVE_NAMES -> PRIMITIVE_NAMES[type]!!
        type.startsWith("L") -> type.substring(1, type.length - 1).toDotted()
        else -> throw IllegalArgumentException(type)
    }

    private val PRIMITIVE_DESCRIPTORS = mapOf(
        "byte" to "B",
        "char" to "C",
        "double" to "D",
        "float" to "F",
        "int" to "I",
        "long" to "J",
        "short" to "S",
        "void" to "V",
        "boolean" to "Z"
    )

    private val PRIMITIVE_NAMES = mapOf(
        "B" to "byte",
        "C" to "char",
        "D" to "double",
        "F" to "float",
        "I" to "int",
        "J" to "long",
        "S" to "short",
        "V" to "void",
        "Z" to "boolean"
    )
}

class ProguardMethodMapping(val lineFrom: Int, val lineTo: Int, defaultName: MemberDescriptor, names: Array<String>)
    : ArrayMemberMapping(defaultName, names), MethodMapping {
    override fun invert(size: Int, index: Int, tree: MappingTree): ProguardMethodMapping = ProguardMethodMapping(lineFrom, lineTo, invertDefaultName(index, tree), invertNames(size, index))
}