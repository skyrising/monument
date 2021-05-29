package de.skyrising.guardian.gen

import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class DownloadProgress(val length: Long, val progress: Long)

private val DOWNLOADS = ConcurrentHashMap<URL, CompletableFuture<Unit>>()

fun download(url: URL, file: Path, listener: ((DownloadProgress) -> Unit)? = null) = DOWNLOADS.computeIfAbsent(url) {
    startDownload(it, file, listener)
}

private fun startDownload(url: URL, file: Path, listener: ((DownloadProgress) -> Unit)? = null) = supplyAsync {
    if (Files.exists(file)) return@supplyAsync
    println("Downloading $url")
    Files.createDirectories(file.parent)
    val conn = url.openConnection() as HttpURLConnection
    conn.connect()
    val len = conn.getHeaderFieldLong("Content-Length", -1)
    BufferedInputStream(conn.inputStream).use { input ->
        Files.newOutputStream(file).use { output ->
            val buf = ByteArray(4096)
            var progress = 0L
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                output.write(buf, 0, read)
                progress += read
                if (listener != null) listener(DownloadProgress(len, progress))
            }
        }
    }
}

fun rmrf(path: Path) {
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

fun copy(path: Path, to: Path, vararg options: CopyOption) {
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes?): FileVisitResult {
            val dest = to.resolve(path.relativize(dir).toString())
            Files.createDirectories(dest)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val dest = to.resolve(path.relativize(file).toString())
            Files.copy(file, dest, *options)
            return FileVisitResult.CONTINUE
        }
    })
}

fun getJarFileSystem(jar: Path): FileSystem {
    val uri = jar.toUri()
    val fsUri = URI("jar:${uri.scheme}", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment)
    return FileSystems.newFileSystem(fsUri, mapOf<String, Any>())
}

interface PostProcessor {
    fun matches(path: Path): Boolean
    fun process(path: Path, content: ByteArray): Pair<Path, ByteArray>
}

val STRUCTURE_PROCESSOR = object : PostProcessor {
    private val structurePath = Paths.get("data", "minecraft", "structures")

    override fun matches(path: Path) = path.startsWith(structurePath.toString()) && path.fileName.toString().endsWith(".nbt")

    override fun process(path: Path, content: ByteArray): Pair<Path, ByteArray> {
        val nbtName = path.fileName.toString()
        val snbtName = nbtName.substring(0, nbtName.length - 4) + ".snbt"
        val snbtOut = path.resolveSibling(snbtName)
        val tag = Tag.readCompressed(Files.newInputStream(path))
        convertStructure(tag)
        return Pair(snbtOut, tag.toSnbt().toByteArray())
    }
}

val SOURCE_PROCESSOR = object : PostProcessor {
    override fun matches(path: Path) = path.fileName.toString().endsWith(".java")

    override fun process(path: Path, content: ByteArray): Pair<Path, ByteArray> {
        val source = String(content).split("\n").toMutableList()
        var startingComment = source[0].startsWith("/*")
        var comment = false
        val it = source.listIterator()
        while (it.hasNext()) {
            val line = it.next()
            if (startingComment) {
                val index = line.indexOf("*/")
                when {
                    index < 0 -> it.remove()
                    index == line.length - 2 -> {
                        it.remove()
                        startingComment = false
                    }
                    else -> {
                        it.set(line.substring(index + 2))
                        startingComment = false
                    }
                }
                continue
            }
            if (line.contains("/*") && !line.contains("*/")) {
                comment = true
                continue
            }
            if (comment && line.contains("at ")) {
                if (line.contains("de.skyrising.guardian.gen.")) {
                    it.remove()
                } else {
                    it.set(line.replace(Regex("\\(.*\\.(java|kt):\\d+\\)"), ""))
                }
            }
            if (line.contains("*/")) {
                comment = false
                continue
            }
        }
        return Pair(path, source.joinToString("\n", postfix="\n").toByteArray())
    }
}

fun postProcessFile(path: Path, relative: Path, postProcessors: List<PostProcessor>): Pair<Path, ByteArray?> {
    var outRelative = relative
    var content: ByteArray? = null
    val appliedPostProcessors = mutableSetOf<PostProcessor>()
    outer@while (appliedPostProcessors.size < postProcessors.size) {
        for (processor in postProcessors) {
            if (processor in appliedPostProcessors) continue
            if (processor.matches(outRelative)) {
                if (content == null) content = Files.readAllBytes(path)
                try {
                    val result = processor.process(outRelative, content!!)
                    outRelative = result.first
                    content = result.second
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                appliedPostProcessors.add(processor)
                continue@outer
            }
        }
        break
    }
    return Pair(outRelative, content)
}

fun postProcessSources(srcTmpDir: Path, srcDir: Path, postProcessors: List<PostProcessor>) = supplyAsync {
    if (Files.exists(srcDir)) rmrf(srcDir)
    Files.createDirectories(srcDir)
    Files.walk(srcTmpDir).forEach { path ->
        if (Files.isDirectory(path)) return@forEach
        val relative = srcTmpDir.relativize(path)
        val (outRelative, content) = postProcessFile(path, relative, postProcessors)
        val fileOut = srcDir.resolve(outRelative.toString())
        Files.createDirectories(fileOut.parent)
        if (content != null) {
            Files.write(fileOut, content)
        } else {
            Files.copy(path, fileOut)
        }
    }
    if (srcTmpDir.fileSystem != FileSystems.getDefault()) {
        srcTmpDir.fileSystem.close()
    }
}

fun extractResources(jar: Path, out: Path, postProcessors: List<PostProcessor>) = supplyAsync {
    getJarFileSystem(jar).use { fs ->
        val root = fs.getPath("/")
        Files.walk(root).forEach { path ->
            if (Files.isDirectory(path) || path.fileName.toString().endsWith(".class")) return@forEach
            val relative = root.relativize(path)
            val (outRelative, content) = postProcessFile(path, relative, postProcessors)
            val fileOut = out.resolve(outRelative.toString())
            Files.createDirectories(fileOut.parent)
            if (content == null) {
                Files.copy(path, fileOut, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.write(fileOut, content)
            }
        }
    }
}

fun convertStructure(tag: Tag) {
    if (tag !is CompoundTag) return
    tag.remove("DataVersion")
    val paletteTag = tag["palette"]
    val blocksTag = tag["blocks"]
    if (paletteTag !is ListTag<*> || blocksTag !is ListTag<*>) return
    val palette = mutableListOf<String>()
    tag["palette"] = ListTag(ArrayList(paletteTag.map { e ->
        if (e !is CompoundTag) throw IllegalArgumentException("palette entry should be a CompoundTag: $e")
        val str = tagToBlockStateString(e)
        palette.add(str)
        StringTag(str)
    }))
    tag["data"] = ListTag(ArrayList(blocksTag.map { block ->
        if (block !is CompoundTag) throw IllegalArgumentException("block should be a CompoundTag: $block")
        val stateId = block["state"].let { if (it is IntTag) it else null } ?: return@map block
        block["state"] = StringTag(palette[stateId.value])
        block
    }))
    tag.remove("blocks")
    return
}

fun tagToBlockStateString(tag: CompoundTag): String {
    val name = tag["Name"].let { if (it is StringTag) it.value else "minecraft:air" }
    val props = tag["Properties"].let { if (it is CompoundTag) it else null } ?: return name
    val sb = StringBuilder(name).append('{')
    var first = true
    for ((k, v) in props) {
        if (v !is StringTag) continue
        if (!first) sb.append(',')
        first = false
        sb.append(k).append(':').append(v.value)
    }
    return sb.append('}').toString()
}

fun useResourceFileSystem(cls: Class<*>, fn: (Path) -> Unit) {
    val root = cls.getResource("/.resourceroot") ?: throw IllegalStateException("Could not find resource root")
    val uri = root.toURI()
    when (uri.scheme) {
        "file" -> fn(Paths.get(uri).parent)
        "jar" -> {
            FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use {
                fn(it.getPath("/"))
            }
        }
        else -> throw IllegalStateException("Cannot get file system for scheme '${uri.scheme}'")
    }
}

fun getMavenArtifact(mvnArtifact: MavenArtifact): CompletableFuture<URL> {
    val artifact = mvnArtifact.artifact
    val id = artifact.id
    val version = artifact.version
    val classifier = artifact.classifier?.let { "-$it" } ?: ""
    val path = "${artifact.group.replace('.', '/')}/$id/$version/$id-$version$classifier.jar"
    val filePath = JARS_DIR.resolve("libraries").resolve(path)
    if (Files.exists(filePath)) return CompletableFuture.completedFuture(filePath.toUri().toURL())
    val url = mvnArtifact.mavenUrl.resolve(path).toURL()
    return download(url, filePath).thenApply { filePath.toUri().toURL() }
}

private object Dummy

fun extractGradle(id: String, out: Path): CompletableFuture<Unit> = supplyAsync {
    useResourceFileSystem(Dummy::class.java) {
        copy(it.resolve("gradle_env"), out, StandardCopyOption.REPLACE_EXISTING)
    }
}.thenCompose {
    generateGradleBuild(id, out)
}

fun getMonumentClassRoot(): Path? {
    val dummyClass = Dummy::class.java
    val dummyFileName = "/" + dummyClass.name.replace('.', '/') + ".class"
    val dummyUrl = dummyClass.getResource(dummyFileName)
        ?: return null
    val uri = dummyUrl.toURI()
    return when (uri.scheme) {
        "file" -> {
            val p = Paths.get(uri).toString()
            Paths.get(p.substring(0, p.indexOf(dummyFileName)))
        }
        "jar" -> Paths.get(uri.schemeSpecificPart.substring(5, uri.schemeSpecificPart.indexOf('!')))
        else -> null
    }
}