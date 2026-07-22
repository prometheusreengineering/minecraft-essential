package studio.dreamys.prometheus.essential.util;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public enum OS {
    Windows(PathUtil.fromString(".")),
    MacOS(PathUtil.fromHomeFolder("Library", "Application Support")),
    Linux(PathUtil.fromEnvOr("XDG_DATA_HOME", PathUtil.fromHomeFolder(".local", "share")))
    ;

    OS(@NotNull Path configFolder) {
        this.configFolder = configFolder;
    }
    private final @NotNull Path configFolder;
    private static final @NotNull OS current;
    static {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            current = Windows;
        } else if (osName.contains("darwin") || osName.contains("mac")) {
            current = MacOS;
        } else {
            current = Linux;
        }
    }

    public static Path getConfigFolder() {
        return PathUtil.fromEnvOr("PROMETHEUS_FOLDER", current.configFolder.resolve("prometheus"));
    }

    public static boolean isOnWindows() {
        return current == Windows;
    }
}
