package com.fundamentum.thrown;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.damage.DamageClasses;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;

/**
 * The example boomerang and throwing knife of the thrown weapon test (-Pthrowntest only; this folder never goes into a
 * jar). Fundamentum itself registers no items: a mod registers its own and gives them stats with
 * {@link ThrownWeapons#register} or data.
 */
public final class ThrownExamples {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("fundamentum");

    /** Called by {@link ThrownWeapons#init} when this class is on the class path. */
    public static void init(IEventBus modBus) {
        ThrownWeapons.EXAMPLE_BOOMERANG = ITEMS.registerItem("example_boomerang", ThrownWeaponItem::new, p -> p.stacksTo(1).durability(250));
        ThrownWeapons.EXAMPLE_KNIFE = ITEMS.registerItem("example_knife", ThrownWeaponItem::new, p -> p.stacksTo(16));
        ITEMS.register(modBus);
        ThrownWeapons.register(ThrownWeapons.EXAMPLE_BOOMERANG.getId(), ThrownStats.BOOMERANG);
        ThrownWeapons.register(ThrownWeapons.EXAMPLE_KNIFE.getId(), new ThrownStats(1.6, 0.03, 0, 3, 1, true, false, true, 200, null));
    }

    private ThrownExamples() {}
}
