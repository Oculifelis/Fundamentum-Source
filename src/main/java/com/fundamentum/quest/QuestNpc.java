package com.fundamentum.quest;

import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The generic quest-giver NPC, {@code fundamentum:quest_giver}. Its look, name and behaviour come from its NPC type
 * ({@link NpcDef}); using it hands in finished quests and lists the quests it offers. Spawn one with
 * {@code /summon fundamentum:quest_giver ~ ~ ~ {npc:"mymod:aldric"}}, {@code /fundamentum npc spawn mymod:aldric}
 * or {@link QuestNpcs#spawn}.
 */
public class QuestNpc extends PathfinderMob {
    private static final EntityDataAccessor<String> TYPE = SynchedEntityData.defineId(QuestNpc.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> TEXTURE = SynchedEntityData.defineId(QuestNpc.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> SLIM = SynchedEntityData.defineId(QuestNpc.class, EntityDataSerializers.BOOLEAN);

    private NpcDef applied;

    public QuestNpc(EntityType<? extends QuestNpc> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.5);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(TYPE, "");
        builder.define(TEXTURE, NpcDef.DEFAULT_TEXTURE);
        builder.define(SLIM, false);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.5) {
            @Override
            public boolean canUse() {
                return applied != null && applied.wander() && super.canUse();
            }
        });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    /** The NPC type, or null. */
    public FId npcType() {
        String s = entityData.get(TYPE);
        return s.isEmpty() ? null : FId.tryOf(s);
    }

    /** Sets the NPC type and applies its look and behaviour now. */
    public void setNpcType(FId type) {
        entityData.set(TYPE, type == null ? "" : type.toString());
        applied = null;
        apply();
    }

    /** The skin texture id, as synced to clients. */
    public String texture() {
        return entityData.get(TEXTURE);
    }

    public boolean slim() {
        return entityData.get(SLIM);
    }

    /** Applies the type's current definition, when it changed (after a data reload, say). */
    private void apply() {
        FId t = npcType();
        NpcDef d = t == null ? null : QuestNpcs.get(t);
        if (d == null || d == applied) return;
        applied = d;
        entityData.set(TEXTURE, d.texture().isEmpty() ? NpcDef.vanillaSkin(getUUID().hashCode(), d.slim()) : d.texture());
        entityData.set(SLIM, d.slim());
        setInvulnerable(d.invulnerable());
        if (!d.name().isEmpty()) {
            setCustomName(Component.literal(d.name()));
            setCustomNameVisible(true);
        }
        FId held = FId.tryOf(d.held());
        Item item = held == null ? null : FVanilla.item(held);
        setItemSlot(EquipmentSlot.MAINHAND, item == null ? ItemStack.EMPTY : new ItemStack(item));
        setDropChance(EquipmentSlot.MAINHAND, 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && tickCount % 40 == 0) apply();
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (player instanceof ServerPlayer sp && !Quests.talk(sp, this)) sp.sendSystemMessage(QuestText.npcIdle(getDisplayName()));
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString("npc", entityData.get(TYPE));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        String s = input.getStringOr("npc", "");
        setNpcType(s.isEmpty() ? null : FId.tryOf(s));
    }
}
