package de.skyrising.guardian.gen

import java.io.*
import java.util.*
import java.util.zip.GZIPInputStream
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.collections.LinkedHashMap
import kotlin.reflect.KClass


sealed class Tag {
    abstract fun write(out: DataOutput)
    abstract fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String)

    fun writeSnbt(writer: Writer) {
        val sb = StringBuilder()
        toString(sb, 0, LinkedList(), "    ")
        writer.write(sb.toString())
    }

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {
        const val END = 0
        const val BYTE = 1
        const val SHORT = 2
        const val INT = 3
        const val LONG = 4
        const val FLOAT = 5
        const val DOUBLE = 6
        const val BYTE_ARRAY = 7
        const val STRING = 8
        const val LIST = 9
        const val COMPOUND = 10
        const val INT_ARRAY = 11
        const val LONG_ARRAY = 12

        val NO_INDENT = setOf(
            listOf("{}", "size", "[]"),
            listOf("{}", "data", "[]", "{}"),
            listOf("{}", "palette", "[]", "{}"),
            listOf("{}", "entities", "[]", "{}")
        )

        private val idToTag = HashMap<Int, KClass<*>>()
        private val idToReader = HashMap<Int, (DataInput) -> Tag>()
        private val tagToId = HashMap<KClass<*>, Int>()

        private fun <T : Tag> register(cls: KClass<*>, id: Int, reader: (DataInput) -> T) {
            idToTag[id] = cls
            idToReader[id] = reader
            tagToId[cls] = id
        }

        fun getId(tag: Tag) = tagToId[tag::class]!!
        fun read(id: Int, din: DataInput) = getReader<Tag>(id)(din)
        @Suppress("UNCHECKED_CAST")
        fun <T : Tag> getReader(id: Int): (DataInput) -> T = idToReader[id] as ((DataInput) -> T)? ?: throw IllegalArgumentException(
            "Unknown tag type $id"
        )

        fun readCompressed(input: InputStream) = DataInputStream(BufferedInputStream(GZIPInputStream(input))).use {
            read(it)
        }

        fun read(input: DataInput): Tag {
            val id = input.readByte().toInt()
            if (id == END) return EndTag
            input.readUTF()
            return read(id, input)
        }

        init {
            register(EndTag::class, END, EndTag::read)
            register(ByteTag::class, BYTE, ByteTag.Companion::read)
            register(ShortTag::class, SHORT, ShortTag.Companion::read)
            register(IntTag::class, INT, IntTag.Companion::read)
            register(LongTag::class, LONG, LongTag.Companion::read)
            register(FloatTag::class, FLOAT, FloatTag.Companion::read)
            register(DoubleTag::class, DOUBLE, DoubleTag.Companion::read)
            register(ByteArrayTag::class, BYTE_ARRAY, ByteArrayTag.Companion::read)
            register(StringTag::class, STRING, StringTag.Companion::read)
            register(ListTag::class, LIST) { ListTag.read<Tag>(it) }
            register(CompoundTag::class, COMPOUND, CompoundTag.Companion::read)
            register(IntArrayTag::class, INT_ARRAY, IntArrayTag.Companion::read)
            register(LongArrayTag::class, LONG_ARRAY, LongArrayTag.Companion::read)
        }
    }
}

object EndTag : Tag() {
    override fun write(out: DataOutput) {}
    fun read(@Suppress("UNUSED_PARAMETER") din: DataInput) = EndTag
    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {}
}

data class ByteTag(val value: Byte) : Tag() {
    override fun write(out: DataOutput) = out.writeByte(value.toInt())
    companion object {
        fun read(din: DataInput) = ByteTag(din.readByte())
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append(value.toInt()).append('b')
    }
}

data class ShortTag(val value: Short) : Tag() {
    override fun write(out: DataOutput) = out.writeShort(value.toInt())
    companion object {
        fun read(din: DataInput) = ShortTag(din.readShort())
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append(value.toInt()).append('s')
    }
}

data class IntTag(val value: Int) : Tag() {
    override fun write(out: DataOutput) = out.writeInt(value)
    companion object {
        fun read(din: DataInput) = IntTag(din.readInt())
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append(value)
    }
}

data class LongTag(val value: Long) : Tag() {
    override fun write(out: DataOutput) = out.writeLong(value)
    companion object {
        fun read(din: DataInput) = LongTag(din.readLong())
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append(value).append('L')
    }
}

data class FloatTag(val value: Float) : Tag() {
    override fun write(out: DataOutput) = out.writeFloat(value)
    companion object {
        fun read(din: DataInput) = FloatTag(din.readFloat())
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append(value).append('f')
    }
}

data class DoubleTag(val value: Double) : Tag() {
    override fun write(out: DataOutput) = out.writeDouble(value)
    companion object {
        fun read(din: DataInput) = DoubleTag(din.readDouble())
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append(value).append('d')
    }
}

data class ByteArrayTag(val value: ByteArray) : Tag() {
    override fun write(out: DataOutput) {
        out.writeInt(value.size)
        out.write(value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ByteArrayTag
        if (!value.contentEquals(other.value)) return false
        return true
    }

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append("[B;")
        for (i in value.indices) {
            if (i > 0) sb.append(',')
            sb.append(' ').append(value[i]).append('B')
        }
        sb.append(']')
    }

    companion object {
        fun read(din: DataInput): ByteArrayTag {
            val value = ByteArray(din.readInt())
            din.readFully(value)
            return ByteArrayTag(value)
        }
    }
}

data class StringTag(val value: String) : Tag() {
    override fun write(out: DataOutput) = out.writeUTF(value)

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        escape(sb, value)
    }

    companion object {
        val SIMPLE = Regex("[A-Za-z0-9._+-]+")

        fun read(din: DataInput) = StringTag(din.readUTF())

        fun escape(sb: StringBuilder, s: String) {
            val start = sb.length
            sb.append(' ')
            var quoteChar = 0.toChar()
            for (c in s) {
                if (c == '\\') sb.append('\\')
                else if (c == '"' || c == '\'') {
                    if (quoteChar == 0.toChar()) {
                        quoteChar = if (c == '"') '\'' else '"'
                    }
                    if (quoteChar == c) sb.append('\\')
                }
                sb.append(c)
            }
            if (quoteChar == 0.toChar()) quoteChar = '"'
            sb[start] = quoteChar
            sb.append(quoteChar)
        }
    }
}

data class ListTag<T : Tag>(val value: MutableList<T>) : Tag(), MutableList<T> by value {
    init {
        verify()
    }

    private fun verify(): Int {
        var id = 0
        for (elem in value) {
            val elemId = getId(elem)
            if (id == 0) id = elemId
            else if (elemId != id) throw IllegalStateException("Conflicting types in ListTag")
        }
        return id
    }

    override fun write(out: DataOutput) {
        out.writeByte(verify())
        out.writeInt(value.size)
        for (tag in value) tag.write(out)
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        if (value.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append('[')
        path.addLast("[]")
        val indent = indentString.isNotEmpty() && !NO_INDENT.contains(path)
        var first = true
        for (e in value) {
            if (!first) sb.append(',')
            if (indent) {
                sb.append('\n')
                for (i in 0 .. depth) sb.append(indentString)
            } else if (!first) {
                sb.append(' ')
            }
            first = false
            e.toString(sb, depth + 1, path, if (indent) indentString else "")
        }
        path.removeLast()
        if (indent) {
            sb.append('\n')
            for (i in 0 until depth) sb.append("    ")
        }
        sb.append(']')
    }

    companion object {
        fun <T : Tag> read(din: DataInput): ListTag<T> {
            val reader = getReader<T>(din.readByte().toInt())
            val size = din.readInt()
            val value = ArrayList<T>(size)
            for (i in 0 until size) {
                value.add(reader(din))
            }
            return ListTag(value)
        }
    }
}

data class CompoundTag(val value: MutableMap<String, Tag>) : Tag(), MutableMap<String, Tag> by value {
    override fun write(out: DataOutput) {
        for ((k, v) in value) {
            if (v is EndTag) throw IllegalStateException("EndTag in CompoundTag")
            out.writeByte(getId(v))
            out.writeUTF(k)
            v.write(out)
        }
        out.write(END)
    }

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        if (value.isEmpty()) {
            sb.append("{}")
            return
        }
        sb.append('{')
        path.addLast("{}")
        val indent = indentString.isNotEmpty() && !NO_INDENT.contains(path)
        var first = true
        for (k in getOrderedKeys(path)) {
            val v = value[k]!!
            if (!first) sb.append(',')
            if (indent) {
                sb.append('\n')
                for (i in 0 .. depth) sb.append("    ")
            } else if (!first) {
                sb.append(' ')
            }
            first = false
            if (StringTag.SIMPLE.matches(k)) {
                sb.append(k)
            } else {
                StringTag.escape(sb, k)
            }
            sb.append(": ")
            path.addLast(k)
            v.toString(sb, depth + 1, path, if (indent) indentString else "")
            path.removeLast()
        }
        path.removeLast()
        if (indent) {
            sb.append('\n')
            for (i in 0 until depth) sb.append("    ")
        }
        sb.append('}')
    }

    private fun getOrderedKeys(path: List<String>): List<String> {
        var set = keys
        val ordered = mutableListOf<String>()
        KEY_ORDER[path]?.let {
            set = HashSet(set)
            for (key in it) {
                if (set.remove(key)) ordered.add(key)
            }
        }
        ordered.addAll(set.sorted())
        return ordered
    }

    companion object {
        private val KEY_ORDER = mapOf(
            listOf("{}") to listOf("DataVersion", "author", "size", "data", "entities", "palette", "palettes"),
            listOf("{}", "data", "[]", "{}") to listOf("pos", "state", "nbt"),
            listOf("{}", "entities", "[]", "{}") to listOf("blockPos", "pos")
        )

        fun read(din: DataInput): CompoundTag {
            val map = LinkedHashMap<String, Tag>()
            while (true) {
                val id = din.readByte().toInt()
                if (id == 0) break
                map[din.readUTF()] = read(id, din)
            }
            return CompoundTag(map)
        }
    }
}

data class IntArrayTag(val value: IntArray) : Tag() {
    override fun write(out: DataOutput) {
        out.writeInt(value.size)
        for (i in value) out.writeInt(i)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IntArrayTag
        if (!value.contentEquals(other.value)) return false
        return true
    }

    override fun hashCode() = value.contentHashCode()

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append("[I;")
        for (i in value.indices) {
            if (i > 0) sb.append(',')
            sb.append(' ').append(value[i])
        }
        sb.append(']')
    }

    companion object {
        fun read(din: DataInput): IntArrayTag {
            val value = IntArray(din.readInt())
            for (i in value.indices) value[i] = din.readInt()
            return IntArrayTag(value)
        }
    }
}

data class LongArrayTag(val value: LongArray) : Tag() {
    override fun write(out: DataOutput) {
        out.writeInt(value.size)
        for (l in value) out.writeLong(l)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LongArrayTag
        if (!value.contentEquals(other.value)) return false
        return true
    }

    override fun hashCode() = value.contentHashCode()

    override fun toString(sb: StringBuilder, depth: Int, path: LinkedList<String>, indentString: String) {
        sb.append("[L;")
        for (i in value.indices) {
            if (i > 0) sb.append(',')
            sb.append(' ').append(value[i]).append('L')
        }
        sb.append(']')
    }

    companion object {
        fun read(din: DataInput): LongArrayTag {
            val value = LongArray(din.readInt())
            for (i in value.indices) value[i] = din.readLong()
            return LongArrayTag(value)
        }
    }
}