package de.skyrising.guardian.gen

import cuchaz.enigma.translation.mapping.EntryMapping
import cuchaz.enigma.translation.mapping.tree.EntryTree
import cuchaz.enigma.translation.representation.MethodDescriptor
import cuchaz.enigma.translation.representation.TypeDescriptor
import cuchaz.enigma.translation.representation.entry.ClassEntry
import cuchaz.enigma.translation.representation.entry.FieldEntry
import cuchaz.enigma.translation.representation.entry.MethodEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class AsmRemapper(private val tree: EntryTree<EntryMapping>, private val superClasses: Map<String, Set<String>>) : Remapper() {
    override fun map(internalName: String?): String? {
        if (internalName == null) return null
        val mapped = tree.get(ClassEntry(internalName))?.targetName ?: return internalName
        val dollar = internalName.lastIndexOf('$')
        if (dollar >= 0) {
            val outerMapped = map(internalName.substring(0, dollar)) ?: return internalName
            return "$outerMapped$$mapped"
        }
        return mapped
    }

    override fun mapFieldName(owner: String?, name: String?, descriptor: String?): String? {
        if (owner == null || name == null || descriptor == null) return null
        return mapFieldName0(ClassEntry(owner), name, TypeDescriptor(descriptor)) ?: name
    }

    private fun mapFieldName0(owner: ClassEntry, name: String, descriptor: TypeDescriptor): String? {
        val mapped = tree.findNode(FieldEntry(owner, name, descriptor))?.value?.targetName
        if (mapped != null) return mapped
        val supers = superClasses[owner.fullName] ?: return null
        for (superClass in supers) {
            val superMapped = mapFieldName0(ClassEntry(superClass), name, descriptor)
            if (superMapped != null) return superMapped
        }
        return null
    }

    override fun mapMethodName(owner: String?, name: String?, descriptor: String?): String? {
        if (owner == null || name == null || descriptor == null) return null
        return mapMethodName0(ClassEntry(owner), name, MethodDescriptor(descriptor)) ?: name
    }

    private fun mapMethodName0(owner: ClassEntry, name: String, descriptor: MethodDescriptor): String? {
        val mapped = tree.findNode(MethodEntry(owner, name, descriptor))?.value?.targetName
        if (mapped != null) return mapped
        val supers = superClasses[owner.fullName] ?: return null
        for (superClass in supers) {
            val superMapped = mapMethodName0(ClassEntry(superClass), name, descriptor)
            if (superMapped != null) return superMapped
        }
        return null
    }
}

fun mapJar(version: String, input: Path, mappings: EntryTree<EntryMapping>, provider: String): CompletableFuture<Path> {
    val output = JARS_MAPPED_DIR.resolve(provider).resolve(JARS_DIR.relativize(input))
    return mapJar(version, input, output, mappings).thenApply { output }
}

fun mapJar(version: String, input: Path, output: Path, mappings: EntryTree<EntryMapping>) = supplyAsync {
    getJarFileSystem(input).use { inFs ->
        createJarFileSystem(output).use { outFs ->
            val inRoot = inFs.getPath("/")
            val outRoot = outFs.getPath("/")
            val classNodes = linkedMapOf<String, ClassNode>()
            val superClasses = mutableMapOf<String, MutableSet<String>>()
            Timer(version, "remapJarIndex").use {
                Files.walk(inRoot).forEach {
                    if (it.parent != null) {
                        if (it.parent != null && !Files.isDirectory(it)) {
                            val inRel = inRoot.relativize(it).toString()
                            if (inRel.endsWith(".class")) {
                                val className = inRel.substring(0, inRel.length - 6)
                                val classReader = ClassReader(Files.readAllBytes(it))
                                val classNode = ClassNode()
                                classReader.accept(classNode, 0)
                                classNodes[className] = classNode
                                val supers = linkedSetOf<String>()
                                val superName = classNode.superName
                                if (superName != null) supers.add(superName)
                                supers.addAll(classNode.interfaces)
                                superClasses[className] = supers
                            }
                        }
                    }
                }
            }
            Timer(version, "remapJarWrite").use {
                val classNames = superClasses.keys
                for (supers in superClasses.values) supers.retainAll(classNames)
                val remapper = AsmRemapper(mappings, superClasses)
                for ((className, classNode) in classNodes) {
                    val remappedNode = ClassNode()
                    val classRemapper = ClassRemapper(remappedNode, remapper)
                    classNode.accept(classRemapper)
                    fixBridgeMethods(remappedNode)
                    val classWriter = ClassWriter(0)
                    remappedNode.accept(classWriter)
                    val outPath = outRoot.resolve("${remapper.map(className)}.class")
                    Files.createDirectories(outPath.parent)
                    Files.write(outPath, classWriter.toByteArray())
                }
            }
        }
    }
}

private fun fixBridgeMethods(node: ClassNode) {
    for (m in node.methods) {
        val synthetic = (m.access and Opcodes.ACC_SYNTHETIC) != 0
        val bridge = (m.access and Opcodes.ACC_BRIDGE) != 0
        if (!synthetic || bridge) continue
        val args = Type.getArgumentTypes(m.desc)
        var callsSpecialized = false
        var callsOthers = false
        for (insn in m.instructions) {
            if (insn !is MethodInsnNode) continue
            if (isProbableBridgeCall(node, m, args, insn)) {
                callsSpecialized = true
            } else {
                callsOthers = true
                break
            }
        }
        if (callsSpecialized && !callsOthers) {
            m.access = m.access or Opcodes.ACC_BRIDGE
        }
    }
}

private fun isProbableBridgeCall(node: ClassNode, bridge: MethodNode, bridgeArgs: Array<Type>, insn: MethodInsnNode): Boolean {
    if (insn.itf || insn.name != bridge.name || !isInHierarchy(node, insn.owner)) return false
    val targetArgs = Type.getArgumentTypes(insn.desc)!!
    // TODO: check argument castability, possibly not decidable
    return targetArgs.size == bridgeArgs.size
}
private fun isInHierarchy(node: ClassNode, name: String) = name == node.name || name == node.superName || name in node.interfaces