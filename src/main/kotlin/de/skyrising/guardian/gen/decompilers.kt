package de.skyrising.guardian.gen

import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.util.getopt.OptionsImpl
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

interface Decompiler {
    val name: String
    fun decompile(version: String, jar: Path, outputDir: Path, cp: List<Path>? = null): CompletableFuture<Unit>

    companion object {
        val CFR = object : CommonDecompiler("cfr") {
            override fun decompile(version: String, jar: Path, outputDir: Path, cp: List<Path>?) = supplyAsync {
                val options = mutableMapOf(
                    OptionsImpl.SHOW_CFR_VERSION.name to "false",
                    OptionsImpl.SILENT.name to "false",
                    OptionsImpl.DECOMPILER_COMMENTS.name to "false",
                    OptionsImpl.OUTPUT_PATH.name to outputDir.toAbsolutePath().toString()
                )
                if (cp != null && cp.isNotEmpty()) options[OptionsImpl.EXTRA_CLASS_PATH.name] = formatClassPath(cp)
                val driver = CfrDriver.Builder().withOptions(options).build()
                outputTo(version) {
                    driver.analyse(listOf(jar.toAbsolutePath().toString()))
                }
            }
        }
    }
}

abstract class CommonDecompiler(override val name: String) : Decompiler {
    override fun toString() = "CommonDecompiler($name)"
}

private fun formatClassPath(cp: List<Path>) = cp.joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }