package com.frontiermilitary.network;

import com.frontiermilitary.item.GunItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Клиент -> сервер: курок автоматического оружия нажат/отпущен. */
public class TriggerPacket {
    private final boolean pressed;

    public TriggerPacket(boolean pressed) {
        this.pressed = pressed;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(pressed);
    }

    public static TriggerPacket decode(FriendlyByteBuf buf) {
        return new TriggerPacket(buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            for (ItemStack stack : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
                if (stack.getItem() instanceof GunItem gun && gun.isAutomatic()) {
                    if (pressed) {
                        stack.getOrCreateTag().putBoolean(GunItem.TAG_TRIGGER, true);
                    } else if (stack.hasTag()) {
                        stack.getTag().remove(GunItem.TAG_TRIGGER);
                    }
                }
            }
        });
        context.setPacketHandled(true);
    }
}
