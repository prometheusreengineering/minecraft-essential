package studio.dreamys.prometheus.essential.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Locale;

public enum OS {
    Windows(PathUtil.fromEnvOr("APPDATA", PathUtil.fromHomeFolder("AppData", "Roaming")).resolve(".minecraft")),
    MacOS(PathUtil.fromHomeFolder("Library", "Application Support")),
    Linux(PathUtil.fromEnvOr("XDG_DATA_HOME", PathUtil.fromHomeFolder(".local", "share")));

    OS(@NotNull Path configFolder) {
        this.configFolder = configFolder;
    }

    private final @NotNull Path configFolder;
    private static final @NotNull OS current;

    static {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            current = Windows;
        } else if (osName.contains("darwin") || osName.contains("mac")) {
            current = MacOS;
        } else {
            current = Linux;
        }
    }

    @Contract(value = "-> new", pure = true)
    public static @NotNull Path getConfigFolder() {
        // Absolute so it doesn't depend on the working directory the game was launched from
        return PathUtil.fromEnvOr("PROMETHEUS_FOLDER", current.configFolder.resolve("prometheus")).toAbsolutePath();
    }

    @Contract(pure = true)
    public static boolean isOnWindows() {
        return current == Windows;
    }
}
