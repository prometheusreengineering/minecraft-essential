package studio.dreamys.prometheus.essential.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public enum OS {
    Windows(Paths.get(".")),
    MacOS(PathUtil.fromHomeFolder("Library", "Application Support")),
    Linux(PathUtil.fromEnvOr("XDG_DATA_HOME", PathUtil.fromHomeFolder(".local", "share")))
    ;

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
        return PathUtil.fromEnvOr("PROMETHEUS_FOLDER", current.configFolder.resolve("prometheus"));
    }

    @Contract(pure = true)
    public static boolean isOnWindows() {
        return current == Windows;
    }

    @Contract(pure = true)
    public static @NotNull String getName() {
        return current.toString();
    }
}
