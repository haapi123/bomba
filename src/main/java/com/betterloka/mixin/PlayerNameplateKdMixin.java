package com.betterloka.mixin;

import com.betterloka.BetterLokaClient;
import com.betterloka.gui.GuiTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Appends a player's Conquest K/D to the nameplate drawn above their head.
 *
 * <p>Hooks the label at its source — {@link EntityRenderer#getDisplayName} is what every renderer
 * asks for the text above an entity — so the badge inherits the game's own nameplate placement,
 * scaling and occlusion, and there is no second text pass.
 */
@Mixin(EntityRenderer.class)
public abstract class PlayerNameplateKdMixin {

    @Inject(method = "getDisplayName(Lnet/minecraft/entity/Entity;)Lnet/minecraft/text/Text;",
            at = @At("RETURN"), cancellable = true)
    private void betterloka$appendKillDeath(Entity entity, CallbackInfoReturnable<Text> cir) {
        if (!(entity instanceof PlayerEntity) || !BetterLokaClient.isNameplateKdEnabled()) {
            return;
        }
        Text original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        String account = accountNameOf(entity);
        if (account == null) {
            return;
        }
        OptionalDouble ratio = BetterLokaClient.nameplateKd().killDeathOf(account);
        if (ratio.isEmpty()) {
            return;
        }
        cir.setReturnValue(original.copy().append(badge(ratio.getAsDouble())));
    }

    /**
     * The player's Mojang account name.
     *
     * <p>Deliberately not the nameplate text or {@code getNameForScoreboard}: Loka decorates players
     * with a rank ("Duelist Rezorie"), and looking that up in a stats service finds nothing. The
     * player list carries the undecorated profile, which is what the stats sites are keyed on.
     */
    private static String accountNameOf(Entity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayNetworkHandler network = client == null ? null : client.getNetworkHandler();
        if (network != null) {
            PlayerListEntry listed = network.getPlayerListEntry(entity.getUuid());
            if (listed != null && listed.getProfile() != null) {
                String name = listed.getProfile().name();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        String fallback = entity.getNameForScoreboard();
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    /** {@code  | 3.30}, coloured by how good the ratio is. */
    private static MutableText badge(double ratio) {
        return Text.literal(" | ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(String.format(Locale.ROOT, "%.2f", ratio))
                        .withColor(GuiTheme.nameplateRatioColor(ratio)));
    }
}
