package studio.dreamys.prometheus.essential.ext

import java.io.File
import java.nio.file.Path

// Constructors
fun File(parent: Path): File
    = parent.toFile()

fun File(parent: Path, name: String): File
    = File(parent.toFile(), name)

fun File(parent: Path, vararg path: String): File {
    var file = File(parent,path.first())
    path.stream().skip(1).forEach { p ->
        file = File(file, p)
    }
    return file
}
