package ing.prometheusreengineer.essential.fabric;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import ing.prometheusreengineer.essential.PrometheusEssential;

public class PrometheusFabric implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        PrometheusEssential.initMixins();
    }
}
