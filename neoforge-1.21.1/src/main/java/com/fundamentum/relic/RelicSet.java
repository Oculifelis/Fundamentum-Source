package com.fundamentum.relic;

import com.fundamentum.port.FId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import org.jetbrains.annotations.Nullable;

/**
 * One mod's relics as the deprecated {@link Relics#register} maps them: by name, the shared base anchor the relic stands
 * on, the shared item {@code fundamentum:relic}, the declaration and (wide relics) the base's filler. Give a relic with
 * {@link #stack}; the item alone is a blank relic.
 *
 * @deprecated relics are data now; use {@link Relics#stack}.
 */
@Deprecated
public final class RelicSet {
    private final String modid;
    private final Map<String, DeferredBlock<RelicBlock>> blocks = new LinkedHashMap<>();
    private final Map<String, DeferredItem<BlockItem>> items = new LinkedHashMap<>();
    private final Map<String, RelicDef> defs = new LinkedHashMap<>();
    private final Map<String, DeferredBlock<RelicPartBlock>> parts = new LinkedHashMap<>();
    DeferredHolder<BlockEntityType<?>, BlockEntityType<RelicBlockEntity>> entity;

    RelicSet(String modid) {
        this.modid = modid;
    }

    void add(String name, RelicDef def, DeferredBlock<RelicBlock> block, DeferredItem<BlockItem> item, @Nullable DeferredBlock<RelicPartBlock> part) {
        this.blocks.put(name, block);
        this.items.put(name, item);
        this.defs.put(name, def);
        if (part != null) {
            this.parts.put(name, part);
        }
    }

    public String modid() {
        return this.modid;
    }

    public DeferredBlock<RelicBlock> block(String name) {
        return this.blocks.get(name);
    }

    public DeferredItem<BlockItem> item(String name) {
        return this.items.get(name);
    }

    /** A stack of relic {@code <modid>:<name>}. */
    public ItemStack stack(String name) {
        return Relics.stack(FId.of(this.modid, name));
    }

    /** The declaration read at registration. */
    public RelicDef def(String name) {
        return this.defs.get(name);
    }

    /** A grand relic's filler block (null for a one-block relic). */
    public @Nullable DeferredBlock<RelicPartBlock> part(String name) {
        return this.parts.get(name);
    }

    public Map<String, DeferredBlock<RelicBlock>> blocks() {
        return Collections.unmodifiableMap(this.blocks);
    }

    public Map<String, DeferredItem<BlockItem>> items() {
        return Collections.unmodifiableMap(this.items);
    }

    /** The block entity type these relics use (the mod's own, or {@code fundamentum:relic}). */
    public DeferredHolder<BlockEntityType<?>, BlockEntityType<RelicBlockEntity>> entity() {
        return this.entity;
    }
}
