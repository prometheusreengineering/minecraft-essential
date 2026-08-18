package studio.dreamys.prometheus.essential.forge;

import net.minecraftforge.fml.common.Mod;
import studio.dreamys.prometheus.essential.PrometheusEssential;

@Mod(
        // 1.13+
        value = "prometheus_essential",
        // 1.8-1.12
        modid = "prometheus_essential",
        useMetadata = true // read mcmod.info instead of this file.
)
public class PrometheusForge {
    public PrometheusForge() {
        PrometheusEssential.initMixins();
    }
}
