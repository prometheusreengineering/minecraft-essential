package studio.dreamys.prometheus.essential.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;

public class PathUtil {
    @NotNull
    @Contract(value = "_ -> new", pure = true)
    public static Path fromHomeFolder(@NotNull String ...paths) {
        return FileSystems.getDefault().getPath(System.getProperty("user.home"), paths);
    }

    @NotNull
    @Contract(value = "null, _ -> param2", pure = true)
    // Passing null to env is the same as passing a non-existent key (and will return the fallback).
    public static Path fromEnvOr(@Nullable String env, @NotNull Path d) {
        if (env == null) return d;
        String path = System.getenv(env);
        if (path == null || path.isEmpty()) return d;
        return Paths.get(path);
    }

    // mkdir -p $(dirname dst) && cp -r src dst
    // Makes all the folders required for dst, then copies src to dst
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
