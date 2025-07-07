package tally.scratchcodersmod.mixin;

import java.net.SocketAddress;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import tally.scratchcodersmod.SCODiscordAuth;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {
    @Inject(method = "checkCanJoin", at = @At("TAIL"), cancellable = true)
    private void banHammer_checkIfBanned(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Text> cir) {
        UUID uuid = profile.getId();
        var result = SCODiscordAuth.getMod().checkAuthentication(uuid);
        if (result instanceof String authCode) {
            Text message;
            if (authCode.contains("Use code ")) {
                message = Text.literal("You need to authenticate your account in Discord\n\nUse code ")
                        .append(Text.literal(authCode.replace("Use code ", "")).formatted(Formatting.GOLD,
                                Formatting.UNDERLINE))
                        .append(Text.literal(" in the pinned message in the #mc-talk channel."));
            } else {
                message = Text.literal(authCode);
            }
            cir.setReturnValue(message);
        } else if (result instanceof Boolean allowed && !allowed) {
            Text message = Text.literal("You are not allowed to join this server.").formatted(Formatting.RED);
            cir.setReturnValue(message);
        }
    }
}