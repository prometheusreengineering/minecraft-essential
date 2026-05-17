package studio.dreamys.prometheus.essential.ext

import java.io.InputStream

/**
 * Reads and closes an [InputStream].
 */
fun InputStream.consume(): String {
    return this.reader().use { it.readText() }
}
