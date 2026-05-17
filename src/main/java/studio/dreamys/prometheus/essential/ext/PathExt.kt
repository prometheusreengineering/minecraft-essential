package studio.dreamys.prometheus.essential.ext

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyToRecursively

// Constructor
fun Path(base: Path, subpath: String): Path
    = Path(base.absolutePathString(), subpath)

/**
 * @see Path.resolve
 */
fun Path.resolve(vararg subpath: String): Path {
    var path = this.resolve(subpath.first())
    subpath.stream().skip(1).forEach { p ->
        path = path.resolve(p)
    }
    return path
}

/**
 * Unlike [copyToRecursively], this function will create all directories along the way.
 * @see [kotlin.io.copyRecursively]
 */
fun Path.copyRecursively(target: Path, overwrite: Boolean = false)
    = this.toFile().copyRecursively(target.toFile(), overwrite)