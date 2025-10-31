package de.skyrising.guardian.gen.mappings

import org.objectweb.asm.Type
import java.io.StringWriter

open class MappingTree(val namespaces: Array<String>) {
    val classes = IndexedMemberList<String, ClassMapping>()

    fun invert(namespace: String) = invert(this.namespaces.indexOf(namespace))
    fun invert(index: Int): MappingTree {
        val size = namespaces.size
        val inverted = MappingTree(Array(size) {
            when {
                it == 0 -> namespaces[index]
                it > index -> namespaces[it]
                else -> namespaces[it - 1]
            }
        })
        for (cls in classes) {
            inverted.classes.add(cls.invert(size, index, this))
        }
        return inverted
    }

    fun mapType(name: String, index: Int) = classes[name]?.getName(index)
    fun mapType(type: Type, index: Int): Type = when(type.sort) {
        Type.ARRAY -> {
            val remappedDesc = StringBuilder()
            repeat(type.dimensions) { remappedDesc.append('[') }
            remappedDesc.append(mapType(type.elementType, index).descriptor)
            Type.getType(remappedDesc.toString())
        }
        Type.OBJECT -> {
            val mapped = mapType(type.internalName, index)
            if (mapped != null) Type.getObjectType(mapped) else type
        }
        Type.METHOD -> {
            val retType = mapType(type.returnType, index)
            val argTypes = type.argumentTypes.map { mapType(it, index) }.toTypedArray()
            Type.getMethodType(retType, *argTypes)
        }
        else -> type
    }

    fun merge(other: MappingTree): MappingTree {
        if (!namespaces.contentEquals(other.namespaces)) throw IllegalArgumentException("Incompatible namespaces, cannot merge mappings")
        val result = MappingTree(namespaces)
        val allClasses = linkedSetOf<String>()
        allClasses.addAll(classes.keys)
        allClasses.addAll(other.classes.keys)
        for (c in allClasses) {
            val a = classes[c]
            val b = other.classes[c]
            if (a == null || b == null) {
                result.classes.add(a ?: b!!)
                continue
            }
            result.classes.add(a.merge(b))
        }
        return result
    }

    fun toString(format: MappingsFormat): String {
        val writer = StringWriter()
        format.write(this, writer)
        return writer.toString()
    }

    override fun toString() = "MappingTree$classes"
    override fun equals(other: Any?) = other is MappingTree && other.namespaces.contentEquals(namespaces) && other.classes == classes
    override fun hashCode() = namespaces.contentHashCode() * 31 + classes.hashCode()
}

class ClassMapping(private val names: Array<String>) : Mapping<String> {
    val methods = IndexedMemberList<MemberDescriptor, MethodMapping>()
    val fields = IndexedMemberList<MemberDescriptor, FieldMapping>()

    override val size get() = names.size
    override val defaultName get() = names[0]
    override fun getRawName(index: Int) = names[index]

    override fun invertDefaultName(index: Int, tree: MappingTree) = names[index]
    override fun invert(size: Int, index: Int, tree: MappingTree): ClassMapping {
        val inverted = ClassMapping(invertNames(size, index))
        for (m in methods) inverted.methods.add(m.invert(size, index, tree))
        for (f in fields) inverted.fields.add(f.invert(size, index, tree))
        return inverted
    }

    fun merge(other: ClassMapping): ClassMapping {
        if (!names.contentEquals(other.names)) throw IllegalArgumentException("Cannot merge class mappings for $defaultName: conflicting names")
        val allMethods = linkedSetOf<MemberDescriptor>()
        allMethods.addAll(methods.keys)
        allMethods.addAll(other.methods.keys)
        val allFields = linkedSetOf<MemberDescriptor>()
        allFields.addAll(fields.keys)
        allFields.addAll(other.fields.keys)
        if (allMethods.size == methods.size && allFields.size == fields.size) return this
        val result = ClassMapping(names)
        if (allMethods.size == methods.size) {
            result.methods.addAll(methods)
        } else {
            for (m in allMethods) result.methods.add(methods[m] ?: other.methods[m]!!)
        }
        if (allFields.size == fields.size) {
            result.fields.addAll(fields)
        } else {
            for (f in allFields) result.fields.add(fields[f] ?: other.fields[f]!!)
        }
        return result
    }

    override fun equals(other: Any?) =
        other is ClassMapping && names.contentEquals(other.names) && fields == other.fields && methods == other.methods
    override fun hashCode() = names.contentHashCode()
    override fun toString() = "ClassMapping[$defaultName,fields=$fields,methods=$methods]"
}

data class MemberDescriptor(val name: String, val type: String) {
    override fun toString() = "$name:$type"
}

interface Mapping<T> {
    val size: Int
    val defaultName: T
    fun getName(index: Int): String {
        if (size <= 0) throw IllegalStateException("At least one name is required")
        return getRawName(minOf(index, size - 1))
    }

    fun getRawName(index: Int): String
    fun invert(size: Int, index: Int, tree: MappingTree): Mapping<T>
    fun invertDefaultName(index: Int, tree: MappingTree): T
    fun invertNames(size: Int, index: Int) = Array(size) {
        when {
            it == 0 -> getName(index)
            it > index -> getName(it)
            else -> getName(it - 1)
        }
    }

    operator fun get(index: Int) = getName(index)
}


interface MemberMapping : Mapping<MemberDescriptor> {
    override fun invertDefaultName(index: Int, tree: MappingTree): MemberDescriptor {
        val newName = getName(index)
        val newType = tree.mapType(Type.getType(defaultName.type), index).descriptor
        return MemberDescriptor(newName, newType)
    }
}
interface MethodMapping : MemberMapping {
    override fun invert(size: Int, index: Int, tree: MappingTree): MethodMapping
}
interface FieldMapping : MemberMapping {
    override fun invert(size: Int, index: Int, tree: MappingTree): FieldMapping
}

abstract class ArrayMemberMapping(override val defaultName: MemberDescriptor, private val names: Array<String>) : MemberMapping {
    override val size get() = names.size
    override fun getRawName(index: Int) = names[index]

    override fun equals(other: Any?): Boolean {
        if (other !is MemberMapping) return false
        if (other.size != size || other.defaultName != defaultName) return false
        for (i in 0 until size) {
            if (other.getRawName(i) != names[i]) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = defaultName.hashCode()
        result = 31 * result + names.contentHashCode()
        return result
    }

    override fun toString() = "${javaClass.simpleName}[$defaultName, ${names.joinToString()}]"
}

open class MethodMappingImpl(defaultName: MemberDescriptor, names: Array<String>) : ArrayMemberMapping(defaultName, names), MethodMapping {
    override fun invert(size: Int, index: Int, tree: MappingTree): MethodMapping = MethodMappingImpl(invertDefaultName(index, tree), invertNames(size, index))

    override fun equals(other: Any?) = other is MethodMapping && super.equals(other)
    override fun hashCode() = super.hashCode()
}
open class FieldMappingImpl(defaultName: MemberDescriptor, names: Array<String>) : ArrayMemberMapping(defaultName, names), FieldMapping {
    override fun invert(size: Int, index: Int, tree: MappingTree): FieldMapping = FieldMappingImpl(invertDefaultName(index, tree), invertNames(size, index))

    override fun equals(other: Any?) = other is FieldMapping && super.equals(other)
    override fun hashCode() = super.hashCode()
}


class IndexedMemberList<T, M: Mapping<T>> : AbstractMutableSet<M>() {
    private val members: MutableSet<M> = linkedSetOf()
    private val index = mutableMapOf<T, M>()
    override val size get() = members.size
    override fun contains(element: M) = members.contains(element)
    override fun containsAll(elements: Collection<M>) = members.containsAll(elements)
    override fun isEmpty() = members.isEmpty()
    override fun iterator(): MutableIterator<M> {
        val iter = members.iterator()
        return object : MutableIterator<M> {
            private var prev: M? = null
            override fun hasNext() = iter.hasNext()
            override fun next(): M {
                prev = iter.next()
                return prev!!
            }

            override fun remove() {
                if (prev == null) throw IllegalStateException()
                iter.remove()
                index.remove(prev!!.defaultName)
            }
        }
    }

    override fun add(element: M): Boolean {
        index[element.defaultName] = element
        return members.add(element)
    }

    override fun addAll(elements: Collection<M>): Boolean {
        for (element in elements) {
            this.index[element.defaultName] = element
        }
        return members.addAll(elements)
    }

    override fun clear() {
        index.clear()
        members.clear()
    }

    override fun remove(element: M): Boolean {
        index.remove(element.defaultName)
        return members.remove(element)
    }

    override fun removeAll(elements: Collection<M>): Boolean {
        for (element in elements) {
            index.remove(element.defaultName)
        }
        return members.removeAll(elements)
    }

    override fun retainAll(elements: Collection<M>): Boolean {
        index.clear()
        for (element in elements) {
            this.index[element.defaultName] = element
        }
        return members.retainAll(elements)
    }

    operator fun get(key: T) = index[key]
    fun containsKey(key: T) = index.containsKey(key)
    val keys get() = index.keys
}

inline operator fun IndexedMemberList<MemberDescriptor, *>.get(name: String, type: String) = get(MemberDescriptor(name, type))