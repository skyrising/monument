/*
 * Copyright (c) 2016-2019 FabricMC
 * Copyright (c) 2021 skyrising
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.skyrising.guardian.gen

import net.fabricmc.stitch.util.StitchUtil
import net.fabricmc.stitch.util.StitchUtil.FileSystemDelegate
import net.fabricmc.stitch.util.SyntheticParameterClassVisitor
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors


fun mergeJars(client: Path, server: Path, merged: Path) = supplyAsync {
    Files.createDirectories(merged.parent)
    JarMerger(client.toFile(), server.toFile(), merged.toFile()).use { merger ->
        merger.enableSnowmanRemoval()
        println("Merging jars...")
        merger.merge()
    }
}

class JarMerger(inputClient: File?, inputServer: File?, output: File) :
    AutoCloseable {
    inner class Entry(val path: Path, val metadata: BasicFileAttributes, val data: ByteArray?)

    private var inputClientFs: FileSystemDelegate? = null
    private var inputServerFs: FileSystemDelegate? = null
    private val outputFs: FileSystemDelegate
    private val inputClient: Path
    private val inputServer: Path
    private val entriesClient: MutableMap<String, Entry?>
    private val entriesServer: MutableMap<String, Entry?>
    private val entriesAll: MutableSet<String>
    private var removeSnowmen = false
    private var offsetSyntheticsParams = false
    fun enableSnowmanRemoval() {
        removeSnowmen = true
    }

    fun enableSyntheticParamsOffset() {
        offsetSyntheticsParams = true
    }

    @Throws(IOException::class)
    override fun close() {
        inputClientFs!!.close()
        inputServerFs!!.close()
        outputFs.close()
    }

    private fun readToMap(map: MutableMap<String, Entry?>, input: Path, isServer: Boolean) {
        try {
            Files.walkFileTree(input, object : SimpleFileVisitor<Path>() {
                @Throws(IOException::class)
                override fun visitFile(path: Path, attr: BasicFileAttributes): FileVisitResult {
                    if (attr.isDirectory) {
                        return FileVisitResult.CONTINUE
                    }
                    if (!path.fileName.toString().endsWith(".class")) {
                        if (path.toString() == "/META-INF/MANIFEST.MF") {
                            map["META-INF/MANIFEST.MF"] = Entry(
                                path, attr,
                                "Manifest-Version: 1.0\nMain-Class: net.minecraft.client.Main\n".toByteArray(
                                    Charset.forName(
                                        "UTF-8"
                                    )
                                )
                            )
                        } else {
                            if (path.toString().startsWith("/META-INF/")) {
                                if (path.toString().endsWith(".SF") || path.toString().endsWith(".RSA")) {
                                    return FileVisitResult.CONTINUE
                                }
                            }
                            map[path.toString().substring(1)] = Entry(path, attr, null)
                        }
                        return FileVisitResult.CONTINUE
                    }
                    val output = Files.readAllBytes(path)
                    map[path.toString().substring(1)] = Entry(path, attr, output)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @Throws(IOException::class)
    private fun add(entry: Entry) {
        val outPath = outputFs.get().getPath(entry.path.toString())
        if (outPath.parent != null) {
            Files.createDirectories(outPath.parent)
        }
        if (entry.data != null) {
            Files.write(outPath, entry.data, StandardOpenOption.CREATE_NEW)
        } else {
            Files.copy(entry.path, outPath)
        }
        Files.getFileAttributeView(entry.path, BasicFileAttributeView::class.java)
            .setTimes(
                entry.metadata.creationTime(),
                entry.metadata.lastAccessTime(),
                entry.metadata.lastModifiedTime()
            )
    }

    @Throws(IOException::class)
    fun merge() {
        val service = Executors.newFixedThreadPool(2)
        service.submit { readToMap(entriesClient, inputClient, false) }
        service.submit { readToMap(entriesServer, inputServer, true) }
        service.shutdown()
        try {
            service.awaitTermination(1, TimeUnit.HOURS)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        entriesAll.addAll(entriesClient.keys)
        entriesAll.addAll(entriesServer.keys)
        val entries = entriesAll.parallelStream().map { entry: String ->
            val isClass = entry.endsWith(".class")
            val isMinecraft =
                entriesClient.containsKey(entry) || entry.startsWith("net/minecraft") || !entry.contains("/")
            var result: Entry?
            var side: String? = null
            val entry1 = entriesClient[entry]
            val entry2 = entriesServer[entry]
            if (entry1 != null && entry2 != null) {
                if (Arrays.equals(entry1.data, entry2.data)) {
                    result = entry1
                } else {
                    if (isClass) {
                        result = Entry(
                            entry1.path,
                            entry1.metadata,
                            CLASS_MERGER.merge(entry1.data, entry2.data)
                        )
                    } else {
                        // FIXME: More heuristics?
                        result = entry1
                    }
                }
            } else if (entry1.also { result = it } != null) {
                side = "CLIENT"
            } else if (entry2.also { result = it } != null) {
                side = "SERVER"
            }
            if (isClass && !isMinecraft && "SERVER" == side) {
                // Server bundles libraries, client doesn't - skip them
                return@map null
            }
            if (result != null) {
                if (isMinecraft && isClass) {
                    var data = result!!.data
                    val reader = ClassReader(data)
                    val writer = ClassWriter(0)
                    var visitor: ClassVisitor = writer
                    if (side != null) {
                        visitor = ClassMerger.SidedClassVisitor(StitchUtil.ASM_VERSION, visitor, side)
                    }
                    if (removeSnowmen) {
                        visitor = SnowmanClassVisitor(StitchUtil.ASM_VERSION, visitor)
                    }
                    if (offsetSyntheticsParams) {
                        visitor = SyntheticParameterClassVisitor(StitchUtil.ASM_VERSION, visitor)
                    }
                    if (visitor !== writer) {
                        reader.accept(visitor, 0)
                        data = writer.toByteArray()
                        result = Entry(result!!.path, result!!.metadata, data)
                    }
                }
                return@map result
            } else {
                return@map null
            }
        }.filter { it != null }.collect(Collectors.toList()) as List<Entry>
        for (e in entries) {
            add(e)
        }
    }

    companion object {
        private val CLASS_MERGER = ClassMerger()
    }

    init {
        if (output.exists()) {
            if (!output.delete()) {
                throw IOException("Could not delete " + output.name)
            }
        }
        this.inputClient =
            StitchUtil.getJarFileSystem(inputClient, false).also { inputClientFs = it }.get().getPath("/")
        this.inputServer =
            StitchUtil.getJarFileSystem(inputServer, false).also { inputServerFs = it }.get().getPath("/")
        outputFs = StitchUtil.getJarFileSystem(output, true)
        entriesClient = HashMap()
        entriesServer = HashMap()
        entriesAll = TreeSet()
    }
}


class ClassMerger {
    private abstract inner class Merger<T>(entriesClient: List<T>, entriesServer: List<T>) {
        private val entriesClient: MutableMap<String, T>
        private val entriesServer: MutableMap<String, T>
        private val entryNames: List<String>
        abstract fun getName(entry: T): String
        abstract fun applySide(entry: T, side: String)
        private fun toMap(entries: List<T>, map: MutableMap<String, T>): List<String> {
            val list: MutableList<String> = ArrayList(entries.size)
            for (entry in entries) {
                val name = getName(entry)
                map[name] = entry
                list.add(name)
            }
            return list
        }

        fun merge(list: MutableList<T?>) {
            for (s in entryNames) {
                val entryClient = entriesClient[s]
                val entryServer = entriesServer[s]
                if (entryClient != null && entryServer != null) {
                    list.add(entryClient)
                } else if (entryClient != null) {
                    applySide(entryClient, "CLIENT")
                    list.add(entryClient)
                } else {
                    applySide(entryServer!!, "SERVER")
                    list.add(entryServer)
                }
            }
        }

        init {
            this.entriesClient = LinkedHashMap()
            this.entriesServer = LinkedHashMap()
            val listClient = toMap(entriesClient, this.entriesClient)
            val listServer = toMap(entriesServer, this.entriesServer)
            entryNames = StitchUtil.mergePreserveOrder(listClient, listServer)
        }
    }

    class SidedClassVisitor(api: Int, cv: ClassVisitor?, private val side: String) :
        ClassVisitor(api, cv) {
        override fun visitEnd() {
            val av = cv.visitAnnotation(SIDED_DESCRIPTOR, true)
            visitSideAnnotation(av, side)
            super.visitEnd()
        }
    }

    fun merge(classClient: ByteArray?, classServer: ByteArray?): ByteArray {
        val readerC = ClassReader(classClient)
        val readerS = ClassReader(classServer)
        val writer = ClassWriter(0)
        val nodeC = ClassNode(StitchUtil.ASM_VERSION)
        readerC.accept(nodeC, 0)
        val nodeS = ClassNode(StitchUtil.ASM_VERSION)
        readerS.accept(nodeS, 0)
        val nodeOut = ClassNode(StitchUtil.ASM_VERSION)
        nodeOut.version = nodeC.version
        nodeOut.access = nodeC.access
        nodeOut.name = nodeC.name
        nodeOut.signature = nodeC.signature
        nodeOut.superName = nodeC.superName
        nodeOut.sourceFile = nodeC.sourceFile
        nodeOut.sourceDebug = nodeC.sourceDebug
        nodeOut.outerClass = nodeC.outerClass
        nodeOut.outerMethod = nodeC.outerMethod
        nodeOut.outerMethodDesc = nodeC.outerMethodDesc
        nodeOut.module = nodeC.module
        nodeOut.nestHostClass = nodeC.nestHostClass
        nodeOut.nestMembers = nodeC.nestMembers
        nodeOut.attrs = nodeC.attrs
        if (nodeC.invisibleAnnotations != null) {
            nodeOut.invisibleAnnotations = ArrayList<AnnotationNode>()
            nodeOut.invisibleAnnotations.addAll(nodeC.invisibleAnnotations)
        }
        if (nodeC.invisibleTypeAnnotations != null) {
            nodeOut.invisibleTypeAnnotations = ArrayList<TypeAnnotationNode>()
            nodeOut.invisibleTypeAnnotations.addAll(nodeC.invisibleTypeAnnotations)
        }
        if (nodeC.visibleAnnotations != null) {
            nodeOut.visibleAnnotations = ArrayList<AnnotationNode>()
            nodeOut.visibleAnnotations.addAll(nodeC.visibleAnnotations)
        }
        if (nodeC.visibleTypeAnnotations != null) {
            nodeOut.visibleTypeAnnotations = ArrayList<TypeAnnotationNode>()
            nodeOut.visibleTypeAnnotations.addAll(nodeC.visibleTypeAnnotations)
        }
        val itfs = StitchUtil.mergePreserveOrder(nodeC.interfaces, nodeS.interfaces)
        nodeOut.interfaces = ArrayList<String>()
        val clientItfs: MutableList<String> = ArrayList()
        val serverItfs: MutableList<String> = ArrayList()
        for (s in itfs) {
            val nc: Boolean = nodeC.interfaces.contains(s)
            val ns: Boolean = nodeS.interfaces.contains(s)
            nodeOut.interfaces.add(s)
            if (nc && !ns) {
                clientItfs.add(s)
            } else if (ns && !nc) {
                serverItfs.add(s)
            }
        }
        if (clientItfs.isNotEmpty() || serverItfs.isNotEmpty()) {
            val envInterfaces: AnnotationVisitor =
                nodeOut.visitAnnotation(ITF_LIST_DESCRIPTOR, false)
            val eiArray = envInterfaces.visitArray("value")
            if (clientItfs.isNotEmpty()) {
                visitItfAnnotation(eiArray, "CLIENT", clientItfs)
            }
            if (serverItfs.isNotEmpty()) {
                visitItfAnnotation(eiArray, "SERVER", serverItfs)
            }
            eiArray.visitEnd()
            envInterfaces.visitEnd()
        }
        object : ClassMerger.Merger<InnerClassNode>(nodeC.innerClasses, nodeS.innerClasses) {
            override fun getName(entry: InnerClassNode): String {
                return entry.name
            }

            override fun applySide(entry: InnerClassNode, side: String) {}
        }.merge(nodeOut.innerClasses)
        object : ClassMerger.Merger<FieldNode>(nodeC.fields, nodeS.fields) {
            override fun getName(entry: FieldNode): String {
                return entry.name + ";;" + entry.desc
            }

            override fun applySide(entry: FieldNode, side: String) {
                val av: AnnotationVisitor = entry.visitAnnotation(SIDED_DESCRIPTOR, false)
                visitSideAnnotation(av, side)
            }
        }.merge(nodeOut.fields)
        object : ClassMerger.Merger<MethodNode>(nodeC.methods, nodeS.methods) {
            override fun getName(entry: MethodNode): String {
                return entry.name + entry.desc
            }

            override fun applySide(entry: MethodNode, side: String) {
                val av: AnnotationVisitor = entry.visitAnnotation(SIDED_DESCRIPTOR, false)
                visitSideAnnotation(av, side)
            }
        }.merge(nodeOut.methods)
        nodeOut.accept(writer)
        return writer.toByteArray()
    }

    companion object {
        private const val SIDE_DESCRIPTOR = "Lnet/fabricmc/api/EnvType;"
        private const val ITF_DESCRIPTOR = "Lnet/fabricmc/api/EnvironmentInterface;"
        private const val ITF_LIST_DESCRIPTOR = "Lnet/fabricmc/api/EnvironmentInterfaces;"
        private const val SIDED_DESCRIPTOR = "Lnet/fabricmc/api/Environment;"
        private fun visitSideAnnotation(av: AnnotationVisitor, side: String) {
            av.visitEnum("value", SIDE_DESCRIPTOR, side.toUpperCase(Locale.ROOT))
            av.visitEnd()
        }

        private fun visitItfAnnotation(av: AnnotationVisitor, side: String, itfDescriptors: List<String>) {
            for (itf in itfDescriptors) {
                val avItf = av.visitAnnotation(null, ITF_DESCRIPTOR)
                avItf.visitEnum("value", SIDE_DESCRIPTOR, side.toUpperCase(Locale.ROOT))
                avItf.visit("itf", Type.getType("L$itf;"))
                avItf.visitEnd()
            }
        }
    }
}


class SnowmanClassVisitor(api: Int, cv: ClassVisitor?) :
    ClassVisitor(api, cv) {
    class SnowmanMethodVisitor(api: Int, methodVisitor: MethodVisitor?) :
        MethodVisitor(api, methodVisitor) {
        override fun visitParameter(name: String?, access: Int) {
            if (name == null || isSnowman(name)) {
                super.visitParameter(null, access)
            } else {
                super.visitParameter(name, access)
            }
        }

        override fun visitLocalVariable(
            name: String?,
            descriptor: String,
            signature: String?,
            start: Label,
            end: Label,
            index: Int
        ) {
            var newName = name
            if (name == null || isSnowman(name)) {
                newName = "lvt$index"
            }
            super.visitLocalVariable(newName, descriptor, signature, start, end, index)
        }
    }

    override fun visitSource(source: String?, debug: String?) {
        // Don't trust the obfuscation on this.
        super.visitSource(null, null)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor {
        return SnowmanMethodVisitor(api, super.visitMethod(access, name, descriptor, signature, exceptions))
    }
}

fun isSnowman(name: String) = name.startsWith("\u2603") || name == "\u00e2\u02dc\u0192"
