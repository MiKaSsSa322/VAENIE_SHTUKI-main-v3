package com.frontiermilitary.client;

import com.frontiermilitary.FrontierMilitary;
import com.frontiermilitary.item.GunItem;
import com.frontiermilitary.item.MedkitItem;
import com.frontiermilitary.network.ModNetwork;
import com.frontiermilitary.network.ReloadPacket;
import com.frontiermilitary.network.TriggerPacket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Клиентские события (FORGE-шина): перезарядка, курок автоматического оружия, отдача камеры
 * и собственная отрисовка рук с оружием от первого лица. Для леворуких игроков остаётся ванильная отрисовка.
 */
@Mod.EventBusSubscriber(modid = FrontierMilitary.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientForgeEvents {
    private static boolean triggerDown = false;
    private static long lastSeenShot = Long.MIN_VALUE;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ItemStack gunStack = player == null ? ItemStack.EMPTY : heldGun(player);

        while (ModKeys.RELOAD.consumeClick()) {
            if (!gunStack.isEmpty() && mc.screen == null) {
                ModNetwork.CHANNEL.sendToServer(new ReloadPacket());
            }
        }

        // курок автоматического оружия: отправляем только изменение состояния
        boolean down = !gunStack.isEmpty() && ((GunItem) gunStack.getItem()).isAutomatic()
                && mc.screen == null && mc.options.keyUse.isDown();
        if (down != triggerDown) {
            triggerDown = down;
            ModNetwork.CHANNEL.sendToServer(new TriggerPacket(down));
        }

        // отдача в камеру: реагируем на новый выстрел, подтверждённый сервером
        if (!gunStack.isEmpty() && mc.level != null) {
            CompoundTag tag = gunStack.getTag();
            if (tag != null && tag.contains(GunItem.TAG_LAST_SHOT)) {
                long shot = tag.getLong(GunItem.TAG_LAST_SHOT);
                if (shot != lastSeenShot) {
                    lastSeenShot = shot;
                    if (mc.level.getGameTime() - shot <= 3) {
                        float kick = ((GunItem) gunStack.getItem()).cameraKick() * (player.isShiftKeyDown() ? 0.6F : 1.0F);
                        player.setXRot(Math.max(-90.0F, player.getXRot() - kick));
                        player.setYRot(player.getYRot() + (player.getRandom().nextFloat() - 0.5F) * kick * 0.5F);
                    }
                }
            }
        }
    }

    private static ItemStack heldGun(LocalPlayer player) {
        if (player.getMainHandItem().getItem() instanceof GunItem) {
            return player.getMainHandItem();
        }
        if (player.getOffhandItem().getItem() instanceof GunItem) {
            return player.getOffhandItem();
        }
        return ItemStack.EMPTY;
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.getItem() instanceof MedkitItem) {
            renderMedkit(event, stack);
            return;
        }
        if (!(stack.getItem() instanceof GunItem gun)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        boolean rightSide = (event.getHand() == InteractionHand.MAIN_HAND) == (player.getMainArm() == HumanoidArm.RIGHT);
        if (!rightSide) {
            return; // леворукий режим — стандартная отрисовка предмета
        }

        event.setCanceled(true);
        PoseStack ps = event.getPoseStack();
        float pt = event.getPartialTick();
        long now = mc.level.getGameTime();

        ps.pushPose();
        // базовая позиция предмета в руке (как в ванили) + опускание при доставании
        ps.translate(0.56F, -0.52F + event.getEquipProgress() * -0.6F, -0.72F);
        applyAnimation(ps, gun, stack, now, pt);

        ItemRenderer renderer = mc.getItemRenderer();
        ItemStack hands = stack.copy();
        hands.getOrCreateTag().putBoolean(ClientSetup.TAG_FP_HANDS, true);

        renderer.renderStatic(player, hands, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, false, ps,
                event.getMultiBufferSource(), mc.level, event.getPackedLight(), OverlayTexture.NO_OVERLAY, 0);
        renderer.renderStatic(player, stack, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, false, ps,
                event.getMultiBufferSource(), mc.level, event.getPackedLight(), OverlayTexture.NO_OVERLAY, 0);
        ps.popPose();
    }

    /** Перевязка: пока идёт использование аптечки, рисуем руки с бинтом (стадии модели меняются по времени). */
    private static void renderMedkit(RenderHandEvent event, ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !player.isUsingItem() || player.getUsedItemHand() != event.getHand()) {
            return; // не используется — обычная ванильная отрисовка предмета
        }
        if ((event.getHand() == InteractionHand.MAIN_HAND) != (player.getMainArm() == HumanoidArm.RIGHT)) {
            return;
        }
        event.setCanceled(true);
        PoseStack ps = event.getPoseStack();
        float t = player.getTicksUsingItem() + event.getPartialTick();
        ps.pushPose();
        ps.translate(0.56F, -0.52F + event.getEquipProgress() * -0.6F, -0.72F);
        ps.translate(0.0F, 0.006F * Mth.sin(t * 1.3F), 0.0F); // лёгкое покачивание при перематывании
        ItemStack hands = stack.copy();
        hands.getOrCreateTag().putBoolean(ClientSetup.TAG_FP_HANDS, true);
        mc.getItemRenderer().renderStatic(player, hands, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, false, ps,
                event.getMultiBufferSource(), mc.level, event.getPackedLight(), OverlayTexture.NO_OVERLAY, 0);
        ps.popPose();
    }

    private static void applyAnimation(PoseStack ps, GunItem gun, ItemStack stack, long now, float pt) {
        // --- отдача: рывок на игрока и подброс ствола, затухает за ~8 тиков
        long since = gun.sinceShot(stack, now);
        if (since >= 0 && since < 12) {
            float t = since + pt;
            float k = Math.max(0.0F, 1.0F - t / (gun.isAutomatic() ? 4.0F : 8.0F));
            k = k * k;
            ps.translate(0.0F, 0.012F * k, gun.recoilBack() * k);
            ps.mulPose(Axis.XP.rotationDegrees(gun.recoilPitch() * k));
        }

        // --- перезарядка: оружие чуть опускается и кренится, пока руки меняют магазин
        long reload = gun.reloadElapsed(stack, now);
        if (reload >= 0 && reload < gun.reloadTicks()) {
            float p = Mth.clamp((reload + pt) / (float) gun.reloadTicks(), 0.0F, 1.0F);
            float s = Math.min(1.0F, Mth.sin(p * (float) Math.PI) * 1.6F);
            ps.translate(-0.02F * s, -0.08F * s, 0.02F * s);
            ps.mulPose(Axis.XP.rotationDegrees(-9.0F * s));
            ps.mulPose(Axis.ZP.rotationDegrees(-13.0F * s));
            ps.mulPose(Axis.YP.rotationDegrees(7.0F * s));
        }
    }

    private ClientForgeEvents() {}
}
