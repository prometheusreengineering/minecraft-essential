package studio.dreamys.prometheus.essential.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class PathUtil {
    @NotNull
    @Contract(value = "_ -> new", pure = true)
    public static Path fromHomeFolder(@NotNull String ...paths) {
        return FileSystems.getDefault().getPath(System.getProperty("user.home"), paths);
    }

    @NotNull
    @Contract(value = "_ -> new", pure = true)
    public static Path fromString(@NotNull String p) {
        return new File(p).toPath();
    }

    @NotNull
    @Contract(value = "null, _ -> param2", pure = true)
    // Passing null to env is the same as passing a non-existent key (and will return the fallback).
    public static Path fromEnvOr(@Nullable String env, @NotNull Path d) {
        try {
            String path = System.getenv(env);
            // jump to the not found logic
            if (path == null || path.isEmpty()) throw new NullPointerException();
            return fromString(path);
        } catch (NullPointerException e) {
            return d;
        }
    }

    // mkdir -p $(dirname dst) && cp -r src dst
    // Makes all the folders required for dst, then copes src to dst
    public static void copyRecursively(@NotNull Path src, @NotNull Path dst) throws IOException {
        File source = src.toFile();
        if (source.isDirectory()) {
            Files.createDirectories(dst);
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child.toPath(), dst.resolve(child.getName()));
                }
            }
        } else {
            Path parent = dst.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // rm -rf path
    // Deletes a folder and all subfolders
    public static void deleteRecursively(@NotNull Path path) throws IOException {
        File file = path.toFile();
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child.toPath());
                }
            }
        }
        Files.deleteIfExists(path);
    }

    @Contract(value = "null -> true", pure = true)
    public static boolean isEmptyOrNull(@Nullable File dir) {
        if (dir == null) return true;
        File[] files = dir.listFiles();
        return files == null || files.length == 0;
    }
}
