package de.skyrising.guardian.gen.mappings

import de.skyrising.guardian.gen.*
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

class AsmRemapper(private val tree: MappingTree, private val superClasses: Map<String, Set<String>>, private val namespace: Int = tree.namespaces.size - 1) : Remapper() {
    override fun map(internalName: String?): String? {
        if (internalName == null) return null
        return tree.mapType(internalName, namespace)
    }

    override fun mapFieldName(owner: String?, name: String?, descriptor: String?): String? {
        if (owner == null || name == null || descriptor == null) return null
        return mapFieldName0(tree.classes[owner], MemberDescriptor(name, descriptor)) ?: name
    }

    private fun mapFieldName0(owner: ClassMapping?, member: MemberDescriptor): String? =
        mapMember(owner, member, { o, f -> o.fields[f] }, ::mapFieldName0)

    override fun mapMethodName(owner: String?, name: String?, descriptor: String?): String? {
        if (owner == null || name == null || descriptor == null) return null
        return mapMethodName0(tree.classes[owner], MemberDescriptor(name, descriptor)) ?: name
    }

    private fun mapMethodName0(owner: ClassMapping?, member: MemberDescriptor): String? =
        mapMember(owner, member, { o, m -> o.methods[m] }, ::mapMethodName0)

    private inline fun mapMember(owner: ClassMapping?, member: MemberDescriptor,
                                 getMember: (ClassMapping, MemberDescriptor) -> MemberMapping?,
                                 recurse: (ClassMapping?, MemberDescriptor) -> String?
    ): String? {
        if (owner == null) return null
        val mapped = getMember(owner, member)
        if (mapped != null) return mapped.getName(namespace)
        val supers = superClasses[owner.defaultName] ?: return null
        for (superClass in supers) {
            val superMapped = recurse(tree.classes[superClass], member)
            if (superMapped != null) return superMapped
        }
        return null
    }
}

fun getMappedJarOutput(provider: String, input: Path): Path = JARS_MAPPED_DIR.resolve(provider).resolve(JARS_DIR.relativize(input))

fun mapJar(version: String, input: Path, mappings: MappingTree, provider: String, namespace: Int = mappings.namespaces.size - 1): CompletableFuture<Path> {
    val output = getMappedJarOutput(provider, input)
    return mapJar(version, input, output, mappings, namespace).thenApply { output }
}

fun mapJar(version: String, input: Path, output: Path, mappings: MappingTree, namespace: Int = mappings.namespaces.size - 1) = supplyAsync {
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
                val remapper = AsmRemapper(mappings, superClasses, namespace)
                for ((className, classNode) in classNodes) {
                    val remappedNode = ClassNode()
                    val classRemapper = ClassRemapper(remappedNode, remapper)
                    classNode.accept(classRemapper)
                    fixBridgeMethods(remappedNode)
                    val classWriter = ClassWriter(0)
                    remappedNode.accept(classWriter)
                    val remappedName = remapper.map(className) ?: throw IllegalArgumentException("$className could not be remapped")
                    val outPath = outRoot.resolve("$remappedName.class")
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