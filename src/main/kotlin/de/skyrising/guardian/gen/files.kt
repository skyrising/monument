package de.skyrising.guardian.gen

import net.fabricmc.stitch.merge.JarMerger
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

data class DownloadProgress(val length: Long, val progress: Long)

fun download(url: URL, file: Path, listener: ((DownloadProgress) -> Unit)? = null) = supplyAsync {
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
            val dest = to.resolve(path.relativize(dir))
            Files.createDirectories(dest)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val dest = to.resolve(path.relativize(file))
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

fun extractResources(jar: Path, out: Path, processStructures: Boolean) = supplyAsync {
    getJarFileSystem(jar).use { fs ->
        val root = fs.getPath("/")
        val structurePath = Paths.get("data", "minecraft", "structures")
        Files.walk(root).forEach { path ->
            if (Files.isDirectory(path) || path.fileName.toString().endsWith(".class")) return@forEach
            val relative = root.relativize(path)
            val fileOut = out.resolve(relative.toString())
            Files.createDirectories(fileOut.parent)
            if (processStructures && relative.startsWith(structurePath.toString()) && relative.fileName.toString().endsWith(".nbt")) {
                val nbtName = relative.fileName.toString()
                val snbtName = nbtName.substring(0, nbtName.length - 4) + ".snbt"
                val snbtOut = fileOut.resolveSibling(snbtName)
                try {
                    val tag = Tag.readCompressed(Files.newInputStream(path))
                    convertStructure(tag)
                    Files.newBufferedWriter(snbtOut, StandardCharsets.UTF_8).use {
                        tag.writeSnbt(it)
                    }
                    return@forEach
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            Files.copy(path, fileOut, StandardCopyOption.REPLACE_EXISTING)
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

fun mergeJars(client: Path, server: Path, merged: Path) = supplyAsync {
    Files.createDirectories(merged.parent)
    JarMerger(client.toFile(), server.toFile(), merged.toFile()).use { merger ->
        merger.enableSnowmanRemoval()
        println("Merging jars...")
        merger.merge()
    }
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

private object Dummy

fun extractGradle(id: String, out: Path) = supplyAsync {
    useResourceFileSystem(Dummy::class.java) {
        copy(it.resolve("gradle_env"), out, StandardCopyOption.REPLACE_EXISTING)
    }
}.thenCompose {
    generateGradleBuild(id, out)
}