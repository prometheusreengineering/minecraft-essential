package studio.dreamys.prometheus.util

import java.util.Locale.getDefault

enum class OS {
    Windows,
    MacOS,
    Linux;
    companion object {
        @JvmStatic
        val current: OS
        init {
            val osName = System.getProperty("os.name").lowercase(getDefault())
            current = if (listOf("linux", "nix", "sunos", "solaris", "bsd").any {it in osName}) {
                Linux
            } else if ("windows" in osName) {
                Windows
            } else {
                MacOS
            }
        }
        @JvmStatic
        fun isOnWindows(): Boolean {
            return current == Windows
        }
        @JvmStatic
        fun isOnPosix(): Boolean {
            return current != Windows
        }
    }
}
