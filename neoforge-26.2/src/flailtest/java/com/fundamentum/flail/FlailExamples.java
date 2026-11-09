package com.fundamentum.flail;

import com.fundamentum.compat.BetterCombat;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.trigger.Triggers;
import com.fundamentum.whip.WhipTargets;
import com.fundamentum.whip.WhipVec;
import com.fundamentum.whip.Whips;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The example flails (one per physics model) and scythe of the flail test (-Pflailtest only; this folder never goes into
 * a jar). Fundamentum itself registers no items: a mod's own items become flails and scythes through data.
 */
public final class FlailExamples {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("fundamentum");
    /** The example flails, one per physics model, and a scythe (their data is in this test folder). */
    public static final List<DeferredItem<Item>> EXAMPLES = new ArrayList<>();
    public static final DeferredItem<Item> EXAMPLE_SCYTHE;

    static {
        if (!FMLEnvironment.isProduction()) {
            for (FlailPhysics p : FlailPhysics.values()) {
                EXAMPLES.add(ITEMS.registerSimpleItem("example_flail_" + p.id(), props -> props.stacksTo(1).durability(500)));
            }
            EXAMPLE_SCYTHE = ITEMS.registerSimpleItem("example_scythe", props -> props.stacksTo(1).durability(500));
        } else {
            EXAMPLE_SCYTHE = null;
        }
    }

    /** Called by {@link Flails#init} when this class is on the class path. */
    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private FlailExamples() {}
}
