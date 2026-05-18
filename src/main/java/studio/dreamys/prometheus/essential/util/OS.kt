package studio.dreamys.prometheus.essential.util

import java.nio.file.Path
import kotlin.io.path.Path
import studio.dreamys.prometheus.essential.ext.*

enum class OS(val configFolder: Path) {
    Windows(Path(".")),
    // ~/.local/share is $XDG_DATA_HOME
    Linux(if (System.getenv("XDG_DATA_HOME").isNullOrBlank()) {
            Path(System.getenv("XDG_DATA_HOME")) 
        } else {
            Path(System.getProperty("user.home"), ".local", "share")
        }
    ),
    MacOS(Path(System.getProperty("user.home"), "Library", "Application Support"));
    companion object {
        val current: OS = run {
            val name = System.getProperty("os.name").lowercase()
            return@run if ("win" in name) {
                Windows
            } else if ("mac" in name || "darwin" in name) {
                MacOS
            } else {
                Linux
            }
        }
        fun isOnWindows(): Boolean {
            return current == Windows
        }
    }
}