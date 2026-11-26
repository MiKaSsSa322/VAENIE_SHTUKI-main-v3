package com.frontiermilitary.item;

import com.frontiermilitary.entity.BulletEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Общая логика огнестрельного оружия: магазин и перезарядка хранятся в NBT предмета,
 * выстрел создаёт {@link BulletEntity}. Автоматический огонь управляется флагом курка (см. TriggerPacket).
 */
public abstract class GunItem extends Item {
    public static final String TAG_AMMO = "Ammo";
    public static final String TAG_LAST_SHOT = "LastShot";
    public static final String TAG_RELOAD_START = "ReloadStart";
    public static final String TAG_TRIGGER = "Trigger";

    private final int magSize;
    private final int reloadTicks;
    private final int cooldown;
    private final boolean automatic;
    private final float bulletSpeed;
    private final float inaccuracy;
    private final float inaccuracySneak;
    private final float inaccuracySprint;
    private final Supplier<? extends Item> ammoItem;
    private final Supplier<Double> damage;
    private final Supplier<SoundEvent> shotSound;

    protected GunItem(Properties properties, int magSize, int reloadTicks, int cooldown, boolean automatic,
                      float bulletSpeed, float inaccuracy, float inaccuracySneak, float inaccuracySprint,
                      Supplier<? extends Item> ammoItem, Supplier<Double> damage, Supplier<SoundEvent> shotSound) {
        super(properties);
        this.magSize = magSize;
        this.reloadTicks = reloadTicks;
        this.cooldown = cooldown;
        this.automatic = automatic;
        this.bulletSpeed = bulletSpeed;
        this.inaccuracy = inaccuracy;
        this.inaccuracySneak = inaccuracySneak;
        this.inaccuracySprint = inaccuracySprint;
        this.ammoItem = ammoItem;
        this.damage = damage;
        this.shotSound = shotSound;
    }

    // ------------------------------------------------------------------ параметры для клиента
    public int magSize() { return magSize; }
    public int reloadTicks() { return reloadTicks; }
    public boolean isAutomatic() { return automatic; }

    /** Номер состояния 3D-модели (state_N.json): 0 покой, 1-2 отдача, 3 затвор открыт, 4-6 магазин, 7-8 затвор. */
    public abstract int modelState(ItemStack stack, long now);
    /** Поза рук (hands_N.json) для состояния модели. */
    public int handIndex(int state) {
        switch (state) {
            case 4: return 1;
            case 5: return 2;
            case 6: return 3;
            case 7: return 4;
            case 8: return 5;
            default: return 0;
        }
    }
    /** Звуки по тикам перезарядки. */
    protected abstract void reloadSounds(Level level, Entity holder, long elapsed);
    /** Отдача модели в руках: сдвиг на игрока (блоки) и подброс ствола (градусы). */
    public abstract float recoilBack();
    public abstract float recoilPitch();
    /** Подброс камеры за выстрел (градусы). */
    public abstract float cameraKick();

    // ------------------------------------------------------------------ состояние в NBT
    /** Предмет без тега (выданный командой) считается заряженным. */
    public int getAmmo(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_AMMO)) {
            return magSize;
        }
        return Math.max(0, Math.min(magSize, tag.getInt(TAG_AMMO)));
    }

    public void setAmmo(ItemStack stack, int ammo) {
        stack.getOrCreateTag().putInt(TAG_AMMO, Math.max(0, Math.min(magSize, ammo)));
    }

    public boolean isReloading(ItemStack stack, long now) {
        long e = reloadElapsed(stack, now);
        return e >= 0 && e < reloadTicks;
    }

    public long reloadElapsed(ItemStack stack, long now) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_RELOAD_START)) {
            return -1;
        }
        return Math.max(0, now - tag.getLong(TAG_RELOAD_START));
    }

    public long sinceShot(ItemStack stack, long now) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_LAST_SHOT)) {
            return -1;
        }
        return Math.max(0, now - tag.getLong(TAG_LAST_SHOT));
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = super.getDefaultInstance();
        setAmmo(stack, magSize);
        return stack;
    }

    // ------------------------------------------------------------------ стрельба
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (automatic) {
            return InteractionResultHolder.pass(stack); // автоматическим оружием управляет курок (TriggerPacket)
        }
        long now = level.getGameTime();
        if (isReloading(stack, now)) {
            return InteractionResultHolder.fail(stack);
        }
        if (getAmmo(stack) <= 0) {
            if (!level.isClientSide) {
                onEmptyTrigger(player, stack, level);
                player.getCooldowns().addCooldown(this, 12);
            }
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide) {
            serverFire((ServerLevel) level, player, stack, hand, now);
            player.getCooldowns().addCooldown(this, cooldown);
        }
        return InteractionResultHolder.consume(stack);
    }

    /** Один выстрел (только сервер). */
    protected void serverFire(ServerLevel level, Player player, ItemStack stack, InteractionHand hand, long now) {
        setAmmo(stack, getAmmo(stack) - 1);
        stack.getOrCreateTag().putLong(TAG_LAST_SHOT, now);

        Vec3 look = player.getLookAngle();
        float spread = player.isSprinting() ? inaccuracySprint : (player.isShiftKeyDown() ? inaccuracySneak : inaccuracy);

        BulletEntity bullet = new BulletEntity(level, player, damage.get().floatValue());
        bullet.setPos(player.getEyePosition().add(look.scale(0.6D)).add(0.0D, -0.1D, 0.0D));
        bullet.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, bulletSpeed, spread);
        level.addFreshEntity(bullet);

        // эффекты у дульного среза: смещён вправо-вперёд от глаз
        Vec3 right = look.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        boolean rightHand = (hand == InteractionHand.MAIN_HAND) == (player.getMainArm() == HumanoidArm.RIGHT);
        double side = rightHand ? 1.0D : -1.0D;
        Vec3 muzzle = player.getEyePosition().add(look.scale(1.05D)).add(right.scale(0.16D * side)).add(0.0D, -0.14D, 0.0D);
        level.sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 2, 0.01D, 0.01D, 0.01D, 0.01D);
        level.sendParticles(ParticleTypes.SMOKE, muzzle.x, muzzle.y, muzzle.z, 3, 0.03D, 0.03D, 0.03D, 0.012D);
        playAt(level, player, shotSound.get(), 1.0F, 0.95F + level.random.nextFloat() * 0.1F);

        stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(hand));
    }

    /** Курок нажат при пустом магазине: автоперезарядка или сухой щелчок. */
    protected void onEmptyTrigger(Player player, ItemStack stack, Level level) {
        if (hasReserve(player)) {
            startReload(player, stack, level);
        } else {
            player.displayClientMessage(Component.translatable("message.frontiermilitary.no_ammo"), true);
            playAt(level, player, com.frontiermilitary.registry.ModSounds.PISTOL_DRY.get(), 0.8F, 1.0F);
        }
    }

    // ------------------------------------------------------------------ перезарядка
    /** Запускает перезарядку (только сервер). Для любого GunItem в руках игрока. */
    public static boolean startReload(Player player, ItemStack stack, Level level) {
        if (level.isClientSide || !(stack.getItem() instanceof GunItem gun)) {
            return false;
        }
        long now = level.getGameTime();
        if (gun.isReloading(stack, now) || gun.getAmmo(stack) >= gun.magSize) {
            return false;
        }
        if (!gun.hasReserve(player)) {
            player.displayClientMessage(Component.translatable("message.frontiermilitary.no_ammo"), true);
            return false;
        }
        stack.getOrCreateTag().putLong(TAG_RELOAD_START, now);
        stack.getOrCreateTag().remove(TAG_TRIGGER);
        return true;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || !(entity instanceof LivingEntity living)) {
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        InteractionHand hand = living.getMainHandItem() == stack ? InteractionHand.MAIN_HAND
                : (living.getOffhandItem() == stack ? InteractionHand.OFF_HAND : null);
        long now = level.getGameTime();

        // --- автоматический огонь
        if (automatic && tag.getBoolean(TAG_TRIGGER)) {
            if (hand == null || !(entity instanceof Player player)) {
                tag.remove(TAG_TRIGGER);
            } else if (!isReloading(stack, now)) {
                if (getAmmo(stack) <= 0) {
                    onEmptyTrigger(player, stack, level);
                    tag.remove(TAG_TRIGGER);
                } else {
                    long since = sinceShot(stack, now);
                    if (since < 0 || since >= cooldown) {
                        serverFire((ServerLevel) level, player, stack, hand, now);
                    }
                }
            }
        }

        // --- перезарядка
        if (!tag.contains(TAG_RELOAD_START)) {
            return;
        }
        long elapsed = now - tag.getLong(TAG_RELOAD_START);
        if (hand == null || elapsed < 0) {
            tag.remove(TAG_RELOAD_START); // оружие убрали из рук — перезарядка отменена
            return;
        }
        reloadSounds(level, entity, elapsed);
        if (elapsed >= reloadTicks) {
            if (entity instanceof Player player) {
                int need = magSize - getAmmo(stack);
                int taken = player.getAbilities().instabuild ? need : consumeAmmo(player, need);
                setAmmo(stack, getAmmo(stack) + taken);
            }
            tag.remove(TAG_RELOAD_START);
        }
    }

    public boolean hasReserve(Player player) {
        return player.getAbilities().instabuild || countAmmo(player) > 0;
    }

    public int countAmmo(Player player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.is(ammoItem.get())) {
                total += s.getCount();
            }
        }
        return total;
    }

    private int consumeAmmo(Player player, int wanted) {
        int taken = 0;
        for (int i = 0; i < player.getInventory().getContainerSize() && taken < wanted; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.is(ammoItem.get())) {
                int n = Math.min(wanted - taken, s.getCount());
                s.shrink(n);
                taken += n;
            }
        }
        return taken;
    }

    protected static void playAt(Level level, Entity at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.getX(), at.getY(), at.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
    }

    // ------------------------------------------------------------------ прочее
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged; // изменения NBT (патроны, анимация) не перезапускают доставание оружия
    }

    @Override
    public boolean shouldCauseBlockBreakReset(ItemStack oldStack, ItemStack newStack) {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.frontiermilitary.gun.ammo", getAmmo(stack), magSize).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(automatic ? "tooltip.frontiermilitary.gun.hint_auto" : "tooltip.frontiermilitary.gun.hint_semi")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Третье лицо: двуручный хват (руки подняты вперёд). */
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return HumanoidModel.ArmPose.CROSSBOW_HOLD;
            }
        });
    }
}
