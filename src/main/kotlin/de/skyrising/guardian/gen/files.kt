package de.skyrising.guardian.gen

import net.fabricmc.stitch.merge.JarMerger
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
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
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

fun copy(path: Path, to: Path) {
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes?): FileVisitResult {
            val dest = to.resolve(path.relativize(dir))
            Files.createDirectories(dest)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val dest = to.resolve(path.relativize(file))
            Files.copy(file, dest)
            return FileVisitResult.CONTINUE
        }
    })
}

fun extractResources(jar: Path, out: Path) = supplyAsync {
    val uri = jar.toUri()
    val fsUri = URI("jar:${uri.scheme}", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment)
    FileSystems.newFileSystem(fsUri, mapOf<String, Any>()).use { fs ->
        val root = fs.getPath("/")
        Files.walk(root).forEach { path ->
            if (Files.isDirectory(path) || path.fileName.toString().endsWith(".class")) return@forEach
            val fileOut = out.resolve(root.relativize(path).toString())
            Files.createDirectories(fileOut.parent)
            Files.copy(path, fileOut, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

fun mergeJars(client: Path, server: Path, merged: Path) = supplyAsync {
    Files.createDirectories(merged.parent)
    JarMerger(client.toFile(), server.toFile(), merged.toFile()).use { merger ->
        merger.enableSnowmanRemoval()
        println("Merging jars...")
        merger.merge()
    }
}