package studio.dreamys.prometheus.essential.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public enum OS {
    Windows,
    Linux,
    MacOS;
    private static final @NotNull OS current;
    static {
        String name = System.getProperty("os.name").toLowerCase();
        if (name.contains("win"))
            current = Windows;
        else if (name.contains("mac") || name.contains("darwin"))
            current = MacOS;
        else current = Linux;
    }
    @Contract(pure = true)
    public static @NotNull OS getCurrent() {
        return current;
    }
    @Contract(pure = true)
    public static boolean isOnWindows() {
        return getCurrent() == Windows;
    }
}
