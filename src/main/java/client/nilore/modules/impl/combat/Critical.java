package client.nilore.modules.impl.combat;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;

public class Critical extends Module {
    public static Critical INSTANCE;

    /* ======================== Settings ======================== */

    public final ModeSetting mode = new ModeSetting("Mode", "Packet", "Legit").withDefault("Packet");
    public final NumberSetting delay = new NumberSetting("Delay", 1.0, 1.0, 3.0, 1.0);
    public final BooleanSetting fallCheck = new BooleanSetting("Fall Check", false);
    public final NumberSetting hurtTime = new NumberSetting("Hurt Time", 2.0, 0.1, 3.0, 0.1);

    /* ======================== State ======================== */

    private boolean waitingForAttack;
    private boolean sentMovement;
    private int tickCounter;

    public Critical() {
        super("Critical", Category.COMBAT);
        INSTANCE = this;
    }

    /* ======================== Lifecycle ======================== */

    @Override
    public void onEnable() {
        this.resetSequence();
    }

    @Override
    public void onDisable() {
        this.resetSequence();
    }

    /* ======================== Events ======================== */

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null) {
            this.resetSequence();
            return;
        }

        if (this.shouldSkip()) {
            this.resetSequence();
            return;
        }

        if (!this.mode.is("Packet")) return;

        this.tickCounter++;
        if (this.tickCounter >= this.delay.getValue().intValue()) {
            if (!this.sentMovement) {
                PacketUtil.send(new ServerboundMovePlayerPacket.StatusOnly(mc.player.onGround()));
                this.sentMovement = true;
            } else {
                this.sentMovement = false;
            }
            this.tickCounter = 0;
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.isIncoming() || mc.player == null || mc.getConnection() == null) return;
        if (!this.mode.is("Packet")) return;
        if (this.shouldSkip()) {
            this.resetSequence();
            return;
        }

        net.minecraft.network.protocol.Packet<?> packet = event.getPacket();

        // Movement packet resets the sequence
        if (packet instanceof ServerboundMovePlayerPacket) {
            this.waitingForAttack = false;
            this.tickCounter = 0;
            return;
        }

        // Only intercept attack / use-on packets
        if (!(packet instanceof ServerboundInteractPacket)
                && !(packet instanceof ServerboundUseItemOnPacket)) return;

        if (this.waitingForAttack) return;

        // Cancel original attack packet
        event.setCancelled(true);

        // Build a fake position packet with a slight y-offset so the server
        // registers a fall, granting critical-hit damage.
        Rotation rotation = this.getRotation();
        float offset = (float) (0.002 + Math.random() * 0.002);
        float yaw = rotation.getYaw() + (Math.random() < 0.5 ? -offset : offset);
        float pitch = rotation.getPitch() - offset;
        float fixedYaw = client.nilore.ClientBase.yaw
                + net.minecraft.util.Mth.wrapDegrees(yaw - client.nilore.ClientBase.yaw);

        PacketUtil.sendQueued(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                fixedYaw, pitch, mc.player.onGround()));
        client.nilore.ClientBase.yaw = fixedYaw;

        this.sentMovement = true;
        PacketUtil.sendQueued((net.minecraft.network.protocol.Packet) packet);
    }

    /* ======================== Public API ======================== */

    /**
     * Called by KillAura to check whether this entity qualifies for a
     * critical hit under the current mode. Returns false if crit is
     * impossible (in water, on ground, effects active, etc.).
     */
    public boolean canCrit(Entity target) {
        if (!this.isEnabled()) return false;
        if (!this.mode.is("Legit")) return false;
        if (mc.player == null) return false;

        if (mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()
                || mc.player.onClimbable() || mc.player.isPassenger()
                || mc.player.getAbilities().flying
                || mc.player.hasEffect(MobEffects.BLINDNESS)
                || mc.player.isFallFlying()) return false;
        if (this.hasBlockAbove()) return false;
        if (target instanceof LivingEntity living
                && living.hurtTime > 0
                && living.invulnerableTime > 0) return false;

        double motionY = mc.player.getDeltaMovement().y;
        return motionY >= -0.08;
    }

    /* ======================== Internal ======================== */

    private boolean shouldSkip() {
        if (mc.player == null) return true;
        if (!KillAuraIsTargeting()) return true;

        if (mc.player.isFallFlying() || mc.player.onClimbable()
                || mc.player.isInWater() || mc.player.isInLava()
                || mc.player.isPassenger() || mc.player.getAbilities().flying
                || mc.player.isSpectator()
                || mc.player.hasEffect(MobEffects.BLINDNESS)) return true;

        if (Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled()) return true;

        Entity target = KillAura.target;
        if (target == null) return true;
        if (mc.player.distanceToSqr(target) > 9.0) return true; // 3.0 range

        if (this.fallCheck.getValue() && !this.canVanillaCrit()) return true;

        return mc.player.hurtTime > this.hurtTime.getValue().intValue();
    }

    private boolean canVanillaCrit() {
        if (mc.player == null) return false;
        return mc.player.fallDistance > 0.0f
                && !mc.player.onGround()
                && !mc.player.onClimbable()
                && !mc.player.isInWater()
                && !mc.player.isInLava()
                && !mc.player.hasEffect(MobEffects.BLINDNESS)
                && !mc.player.isPassenger();
    }

    private boolean hasBlockAbove() {
        if (mc.player == null || mc.level == null) return false;
        BlockPos pos = mc.player.blockPosition();
        int top = (int) Math.ceil(mc.player.getY() + mc.player.getBbHeight());
        for (int y = top; y < top + 2; y++) {
            if (!mc.level.getBlockState(new BlockPos(pos.getX(), y, pos.getZ())).isAir()) {
                return true;
            }
        }
        return false;
    }

    private boolean KillAuraIsTargeting() {
        return KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled()
                && KillAura.INSTANCE.getTarget() != null;
    }

    private Rotation getRotation() {
        if (RotationHandler.sentRotation != null) return RotationHandler.sentRotation;
        return new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    private void resetSequence() {
        this.waitingForAttack = true;
        this.sentMovement = true;
        this.tickCounter = (int) this.delay.getValue().doubleValue();
    }
}
