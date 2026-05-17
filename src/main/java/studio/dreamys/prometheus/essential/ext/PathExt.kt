package studio.dreamys.prometheus.essential.ext

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

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