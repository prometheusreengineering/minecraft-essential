package studio.dreamys.prometheus.essential.fabric;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import studio.dreamys.prometheus.essential.PrometheusEssential;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PrometheusFabric implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        PrometheusEssential.initMixins();
    }
}
