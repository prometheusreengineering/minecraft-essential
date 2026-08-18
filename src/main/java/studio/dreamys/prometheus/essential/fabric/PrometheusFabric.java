package studio.dreamys.prometheus.essential.fabric;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import studio.dreamys.prometheus.essential.PrometheusEssential;

public class PrometheusFabric implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        PrometheusEssential.initMixins();
    }
}
