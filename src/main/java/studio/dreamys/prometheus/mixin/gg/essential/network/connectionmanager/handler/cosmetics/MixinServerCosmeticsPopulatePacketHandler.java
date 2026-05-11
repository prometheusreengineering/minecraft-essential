package studio.dreamys.prometheus.mixin.gg.essential.network.connectionmanager.handler.cosmetics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import gg.essential.connectionmanager.common.packet.cosmetic.ServerCosmeticsPopulatePacket;
import gg.essential.cosmetics.model.Cosmetic;
import gg.essential.network.connectionmanager.ConnectionManager;
import gg.essential.network.connectionmanager.cosmetics.CosmeticsManager;
import gg.essential.network.connectionmanager.handler.cosmetics.ServerCosmeticsPopulatePacketHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import studio.dreamys.prometheus.serial.EssentialCosmeticsList;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

@Mixin(value = ServerCosmeticsPopulatePacketHandler.class, remap = false)
public class MixinServerCosmeticsPopulatePacketHandler {
    @Unique
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Unique
    private static final Logger logger = Logger.getLogger("Prometheus");

    @SuppressWarnings("rawtypes")
    @Inject(method = "onHandle(Lgg/essential/network/connectionmanager/ConnectionManager;Lgg/essential/connectionmanager/common/packet/cosmetic/ServerCosmeticsPopulatePacket;)V", at = @At(value = "INVOKE", target = "Lgg/essential/cosmetics/model/Cosmetic;getType()Ljava/lang/String;"), locals = LocalCapture.CAPTURE_FAILSOFT)
    public void onHandle(ConnectionManager connectionManager, ServerCosmeticsPopulatePacket packet, CallbackInfo ci, CosmeticsManager cosmeticsManager, Iterator var4, Cosmetic cosmetic) {
        try {
            logger.fine(String.format("Saving cosmetic %s\n%s", cosmetic.getId(), gson.toJson(cosmetic)));
            String directoryPath = "prometheus/dumps/essential/" + cosmetic.getType();
            Path directory = Paths.get(directoryPath);
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
            }

            String fileName = cosmetic.getId() + ".json";
            Path filePath = directory.resolve(fileName);
            if (Files.exists(filePath)) {
                logger.fine(String.format("Cosmetic file %s already exists, overwriting", filePath));
                Files.deleteIfExists(filePath);
            }

            BufferedWriter writer = Files.newBufferedWriter(filePath, StandardOpenOption.CREATE);
            writer.write(gson.toJson(cosmetic));
            writer.close();
        } catch (Exception e) {
            logger.log(Level.SEVERE, String.format("Failed to save cosmetic %s", cosmetic.getId()), e);
        }
        EssentialCosmeticsList.addCosmetic(cosmetic.getId());
    }
}