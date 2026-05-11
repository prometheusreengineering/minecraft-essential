package studio.dreamys.prometheus.fabric;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import studio.dreamys.prometheus.serial.EssentialCosmeticsManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PrometheusFabric implements PreLaunchEntrypoint {
    private static final Logger logger = Logger.getLogger("Prometheus");

    @Override
    public void onPreLaunch() {
        // Initialize our files *before* loading and patching Essential.
        EssentialCosmeticsManager.downloadCosmeticsList();
        MixinBootstrap.init();
        Mixins.addConfiguration("prometheus.essential.mixins.json");
        try {
            chainLoadMixins();
        } catch (ReflectiveOperationException e) {
            logger.log(Level.SEVERE, "Failed to chain load mixins", e);
        }
    }

    //https://github.com/EssentialGG/EssentialLoader/blob/master/stage2/fabric/src/main/java/gg/essential/loader/stage2/EssentialLoader.java#L180
    public static void chainLoadMixins() throws ReflectiveOperationException {
        if (Mixins.getUnvisitedCount() != 0) {
            logger.warning(String.format("Mixins.getUnvisitedCount() = %s", Mixins.getUnvisitedCount()));
            MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
            Object transformer = environment.getActiveTransformer();

            Field processorField = transformer.getClass().getDeclaredField("processor");
            processorField.setAccessible(true);

            Object processor = processorField.get(transformer);

            Method select = processor.getClass().getDeclaredMethod("select", MixinEnvironment.class);
            select.setAccessible(true);
            select.invoke(processor, environment);
        }
    }
}
