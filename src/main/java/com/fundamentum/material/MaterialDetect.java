package com.fundamentum.material;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.BlockFamilies;
import net.minecraft.data.BlockFamily;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * The scan behind auto-detected sets. For every block with a stairs, slab or wall sibling by name ({@code x_stairs},
 * {@code x_slab}, {@code xs} for {@code x_stairs} of bricks, {@code x_planks}...), checked by block class, it builds a set
 * keyed by the base block id; vanilla's BlockFamily data is merged in. Wood sets come from {@code *_planks} and are keyed
 * by the wood name ({@code minecraft:oak}) with planks, log, stripped, door, trapdoor, fence, ladder, barrel and bookshelf
 * slots. Wear blocks ({@code cracked_/mossy_/chiseled_/polished_/smooth_/cut_} + name) join their parent's set as wears and
 * stay sets of their own in the parent's family.
 */
final class MaterialDetect {
    private MaterialDetect() { }

    static final String[] WEAR_PREFIXES = {"cracked_", "mossy_", "chiseled_", "polished_", "smooth_", "cut_"};
    private static final String[] NETHER = {"nether_brick", "red_nether", "blackstone", "basalt", "crimson", "warped", "soul_", "netherrack"};
    private static final String[] END = {"end_stone", "purpur", "chorus"};

    static Map<Identifier, MaterialSet.Builder> scan() {
        Map<Identifier, MaterialSet.Builder> out = new LinkedHashMap<>();
        try {
            families(out);
        } catch (Throwable t) {
            LoggerFactory.getLogger("fundamentum").debug("block families unavailable: {}", t.toString());
        }
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            String path = id.getPath();
            if (path.endsWith("_planks")) {
                wood(out, id.getNamespace(), path.substring(0, path.length() - "_planks".length()), block);
                continue;
            }
            if (isShape(path)) continue;
            Block stairs = sibling(id.getNamespace(), path, "_stairs", StairBlock.class);
            Block slab = sibling(id.getNamespace(), path, "_slab", SlabBlock.class);
            Block wall = sibling(id.getNamespace(), path, "_wall", WallBlock.class);
            if (stairs == null && slab == null && wall == null) continue;
            MaterialSet.Builder b = out.computeIfAbsent(id, k -> stone(k, block));
            b.detected = true;
            put(b, "main", "base", block);
            put(b, "main", "stairs", stairs);
            put(b, "main", "slab", slab);
            put(b, "main", "wall", wall);
            put(b, "main", "fence", sibling(id.getNamespace(), path, "_fence", FenceBlock.class));
            put(b, "main", "fence_gate", sibling(id.getNamespace(), path, "_fence_gate", FenceGateBlock.class));
            put(b, "main", "button", sibling(id.getNamespace(), path, "_button", ButtonBlock.class));
            put(b, "main", "pressure_plate", sibling(id.getNamespace(), path, "_pressure_plate", PressurePlateBlock.class));
        }
        // wears: join the parent set
        for (MaterialSet.Builder b : out.values().toArray(MaterialSet.Builder[]::new)) {
            if (b.kind == MaterialSet.Kind.WOOD) continue;
            String path = b.id.getPath();
            for (String prefix : WEAR_PREFIXES) {
                if (!path.startsWith(prefix)) continue;
                String wear = prefix.substring(0, prefix.length() - 1);
                MaterialSet.Builder parent = parentOf(out, b.id.getNamespace(), path.substring(prefix.length()));
                if (parent == null) break;
                b.family = parent.family != null ? parent.family : parent.id.toString();
                for (String shape : MaterialSet.SHAPES) {
                    Block w = b.get("main", shape, "");
                    if (w != null && parent.get("main", shape, wear) == null) parent.block("main", shape, wear, w);
                }
                break;
            }
        }
        // wear blocks without shapes (chiseled_stone_bricks, cracked_stone_bricks) still join as base wears
        for (MaterialSet.Builder b : out.values()) {
            if (b.kind == MaterialSet.Kind.WOOD) continue;
            Block base = b.get("main", "base", "");
            if (base == null) continue;
            String ns = b.id.getNamespace(), path = b.id.getPath();
            for (String prefix : WEAR_PREFIXES) {
                String wear = prefix.substring(0, prefix.length() - 1);
                if (b.get("main", "base", wear) != null) continue;
                Block w = block(ns, prefix + path);
                if (w == null && path.endsWith("s")) w = block(ns, prefix + path.substring(0, path.length() - 1) + "s");
                if (w != null) b.block("main", "base", wear, w);
            }
        }
        return out;
    }

    private static MaterialSet.@Nullable Builder parentOf(Map<Identifier, MaterialSet.Builder> out, String ns, String path) {
        for (String p : new String[] {path, path + "s", path.endsWith("s") ? path.substring(0, path.length() - 1) : path}) {
            MaterialSet.Builder b = out.get(Identifier.fromNamespaceAndPath(ns, p));
            if (b != null) return b;
        }
        return null;
    }

    private static void families(Map<Identifier, MaterialSet.Builder> out) {
        BlockFamilies.getAllFamilies().forEach(fam -> {
            Block base = fam.getBaseBlock();
            Identifier id = BuiltInRegistries.BLOCK.getKey(base);
            if (id.getPath().endsWith("_planks")) return; // wood sets come from wood()
            MaterialSet.Builder b = out.computeIfAbsent(id, k -> stone(k, base));
            b.detected = true;
            put(b, "main", "base", base);
            for (Map.Entry<BlockFamily.Variant, Block> e : fam.getVariants().entrySet()) {
                String shape = switch (e.getKey().name()) {
                    case "STAIRS" -> "stairs";
                    case "SLAB" -> "slab";
                    case "WALL" -> "wall";
                    case "FENCE" -> "fence";
                    case "FENCE_GATE" -> "fence_gate";
                    case "BUTTON" -> "button";
                    case "PRESSURE_PLATE" -> "pressure_plate";
                    case "DOOR" -> "door";
                    case "TRAPDOOR" -> "trapdoor";
                    case "CHISELED" -> "chiseled";
                    case "CRACKED" -> "cracked";
                    case "POLISHED" -> "polished";
                    case "CUT" -> "cut";
                    default -> null;
                };
                if (shape == null) continue;
                if (MaterialSet.WEARS.contains(shape)) {
                    if (b.get("main", "base", shape) == null) b.block("main", "base", shape, e.getValue());
                } else {
                    put(b, "main", shape, e.getValue());
                }
            }
        });
    }

    private static void wood(Map<Identifier, MaterialSet.Builder> out, String ns, String wood, Block planks) {
        Identifier id = Identifier.fromNamespaceAndPath(ns, wood);
        MaterialSet.Builder b = out.computeIfAbsent(id, k -> new MaterialSet.Builder(k));
        b.detected = true;
        b.kind = MaterialSet.Kind.WOOD;
        b.tag("wood");
        dimension(b, wood);
        String p = wood + "_planks";
        put(b, "main", "base", planks);
        put(b, "main", "stairs", sibling(ns, wood, "_stairs", StairBlock.class));
        put(b, "main", "slab", sibling(ns, wood, "_slab", SlabBlock.class));
        put(b, "main", "fence", sibling(ns, wood, "_fence", FenceBlock.class));
        put(b, "main", "fence_gate", sibling(ns, wood, "_fence_gate", FenceGateBlock.class));
        put(b, "main", "door", sibling(ns, wood, "_door", DoorBlock.class));
        put(b, "main", "trapdoor", sibling(ns, wood, "_trapdoor", TrapDoorBlock.class));
        put(b, "main", "button", sibling(ns, wood, "_button", ButtonBlock.class));
        put(b, "main", "pressure_plate", sibling(ns, wood, "_pressure_plate", PressurePlateBlock.class));
        if (b.get("main", "stairs", "") == null) put(b, "main", "stairs", sibling(ns, p, "_stairs", StairBlock.class));
        if (b.get("main", "slab", "") == null) put(b, "main", "slab", sibling(ns, p, "_slab", SlabBlock.class));
        // planks slot mirrors main
        b.slots.get("main").forEach((shape, wears) -> wears.forEach((w, blk) -> b.block("planks", shape, w, blk)));
        Block log = first(ns, wood + "_log", wood + "_stem", wood + "_block");
        if (log != null) {
            put(b, "log", "base", log);
            put(b, "post", "base", log);
        }
        Block wd = first(ns, wood + "_wood", wood + "_hyphae");
        if (wd != null) put(b, "log", "wood", wd);
        Block stripped = first(ns, "stripped_" + wood + "_log", "stripped_" + wood + "_stem", "stripped_" + wood + "_block");
        if (stripped != null) put(b, "stripped", "base", stripped);
        put(b, "door", "base", b.get("main", "door", ""));
        put(b, "trapdoor", "base", b.get("main", "trapdoor", ""));
        put(b, "fence", "base", b.get("main", "fence", ""));
        put(b, "fence", "fence_gate", b.get("main", "fence_gate", ""));
        put(b, "ladder", "base", first(ns, wood + "_ladder"));
        put(b, "barrel", "base", first(ns, wood + "_barrel"));
        put(b, "bookshelf", "base", first(ns, wood + "_bookshelf"));
    }

    private static MaterialSet.Builder stone(Identifier id, Block base) {
        MaterialSet.Builder b = new MaterialSet.Builder(id);
        String path = id.getPath();
        if (path.contains("copper") || path.contains("iron") || path.contains("gold") || path.contains("metal") || path.contains("steel")) {
            b.kind = MaterialSet.Kind.METAL;
        } else if (path.contains("plaster") || path.contains("terracotta") || path.contains("concrete") || path.contains("stucco")) {
            b.kind = MaterialSet.Kind.PLASTER;
        } else if (path.contains("wool") || path.contains("glass") || path.contains("bamboo_mosaic")) {
            b.kind = MaterialSet.Kind.OTHER;
        } else {
            b.kind = MaterialSet.Kind.STONE;
            b.tag("stone_like");
        }
        if (path.contains("brick")) b.tag("bricks");
        if (path.contains("tile")) b.tag("tiles");
        dimension(b, path);
        return b;
    }

    private static void dimension(MaterialSet.Builder b, String path) {
        for (String s : NETHER) if (path.contains(s)) { b.dimension = "nether"; b.tag("nether"); return; }
        for (String s : END) if (path.contains(s)) { b.dimension = "end"; b.tag("end"); return; }
    }

    /** Tags every set from its colour, namespace and kind once the colour is known. */
    static void autoTags(MaterialSet.Builder b, int rgb) {
        b.tag(b.kind.id());
        b.tag(b.id.getNamespace());
        if (b.id.getNamespace().equals("minecraft")) b.tag("vanilla");
        for (String w : MaterialColor.words(rgb)) b.tag(w);
    }

    private static boolean isShape(String path) {
        for (String s : new String[] {"_stairs", "_slab", "_wall", "_fence", "_fence_gate", "_door", "_trapdoor", "_button", "_pressure_plate"}) {
            if (path.endsWith(s)) return true;
        }
        return false;
    }

    private static @Nullable Block sibling(String ns, String path, String suffix, Class<? extends Block> type) {
        String[] names = path.endsWith("s")
            ? new String[] {path + suffix, path.substring(0, path.length() - 1) + suffix}
            : new String[] {path + suffix, path.replace("_block", "") + suffix};
        for (String n : names) {
            Block b = block(ns, n);
            if (b != null && type.isInstance(b)) return b;
        }
        return null;
    }

    private static @Nullable Block first(String ns, String... paths) {
        for (String p : paths) {
            Block b = block(ns, p);
            if (b != null) return b;
        }
        return null;
    }

    static @Nullable Block block(String ns, String path) {
        Identifier id = Identifier.tryBuild(ns, path.toLowerCase(Locale.ROOT));
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        return BuiltInRegistries.BLOCK.getValue(id);
    }

    private static void put(MaterialSet.Builder b, String slot, String shape, @Nullable Block block) {
        if (block != null && b.get(slot, shape, "") == null) b.block(slot, shape, "", block);
    }

    @SuppressWarnings("unused")
    private static boolean pillar(Block b) {
        return b instanceof RotatedPillarBlock;
    }
}
