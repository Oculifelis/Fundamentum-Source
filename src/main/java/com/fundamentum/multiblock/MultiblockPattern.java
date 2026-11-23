package com.fundamentum.multiblock;

import com.fundamentum.port.FId;
import com.fundamentum.port.FTags;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * One multiblock shape, read from {@code data/<ns>/fundamentum/multiblocks/<name>.json}:
 * <pre>
 * {
 *   "layers": [ ["ICI", "I.I", "IIG"], ["L L", " F ", "LLL"] ],
 *   "key": {
 *     "I": "minecraft:iron_block",
 *     "L": "#minecraft:logs",
 *     "F": { "block": "minecraft:furnace", "state": { "facing": "north" } },
 *     "G": ["minecraft:gold_block", "minecraft:raw_gold_block"],
 *     "C": "minecraft:crafting_table"
 *   },
 *   "controller": "C",
 *   "rotate": true,
 *   "mirror": false
 * }
 * </pre>
 * Layers go bottom to top, each one's rows north to south and each row's characters west to east. A space is any block
 * and {@code .} is air unless the key names them. The unturned pattern faces north (its first row is the front); with
 * {@code rotate} it also matches turned to face east, south and west, with {@code mirror} also flipped east to west.
 * Block states in the key turn with the pattern, so a furnace facing north in the file faces east in an east-facing
 * build. The same file on every version.
 */
public final class MultiblockPattern {
    /** One accepted block or tag, with optional state properties by name. */
    public record Option(Block block, TagKey<Block> tag, Map<String, String> state) {
        boolean accepts(BlockState actual, Rotation rotation, Mirror mirror) {
            if (this.block != null ? actual.getBlock() != this.block : !actual.is(this.tag)) return false;
            if (this.state.isEmpty()) return true;
            BlockState expected = withState(actual.getBlock().defaultBlockState(), this.state);
            if (expected == null) return false;
            expected = expected.mirror(mirror).rotate(rotation);
            for (String name : this.state.keySet()) {
                Property<?> p = actual.getBlock().getStateDefinition().getProperty(name);
                if (p == null || !actual.getValue(p).equals(expected.getValue(p))) return false;
            }
            return true;
        }

        /** The block to show for this option: the block itself, else the first block in the tag. */
        BlockState display(Rotation rotation, Mirror mirror) {
            Block b = this.block;
            if (b == null) {
                for (Block candidate : BuiltInRegistries.BLOCK) {
                    if (candidate.defaultBlockState().is(this.tag)) {
                        b = candidate;
                        break;
                    }
                }
            }
            if (b == null) return null;
            BlockState s = withState(b.defaultBlockState(), this.state);
            return (s == null ? b.defaultBlockState() : s).mirror(mirror).rotate(rotation);
        }
    }

    /** What one key character accepts: air, any block, or any of its options. */
    public record Part(char symbol, boolean air, boolean any, List<Option> options) {
        public boolean accepts(BlockState actual, Rotation rotation, Mirror mirror) {
            if (this.any) return true;
            if (this.air) return actual.isAir();
            for (Option o : this.options) {
                if (o.accepts(actual, rotation, mirror)) return true;
            }
            return false;
        }

        /** The block a preview shows here (null for any block; air for air). */
        public BlockState display(Rotation rotation, Mirror mirror) {
            if (this.any) return null;
            if (this.air) return Blocks.AIR.defaultBlockState();
            for (Option o : this.options) {
                BlockState s = o.display(rotation, mirror);
                if (s != null) return s;
            }
            return null;
        }
    }

    private static final Part ANY = new Part(' ', false, true, List.of());
    private static final Part AIR = new Part('.', true, false, List.of());

    private final Part[][][] grid; // [y][z][x]
    private final Map<Character, Part> key;
    private final int width, height, depth;
    private final int cx, cy, cz;
    private final char controller;
    private final boolean rotate, mirror;
    private final String name;

    private MultiblockPattern(Part[][][] grid, Map<Character, Part> key, char controller, int cx, int cy, int cz,
                              boolean rotate, boolean mirror, String name) {
        this.grid = grid;
        this.key = key;
        this.height = grid.length;
        this.depth = grid[0].length;
        this.width = grid[0][0].length;
        this.controller = controller;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.rotate = rotate;
        this.mirror = mirror;
        this.name = name;
    }

    public int width() { return this.width; }
    public int height() { return this.height; }
    public int depth() { return this.depth; }
    public boolean rotates() { return this.rotate; }
    public boolean mirrors() { return this.mirror; }
    /** The translation key the file gives as {@code name}, or null. */
    public String name() { return this.name; }
    public Part controllerPart() { return this.key.get(this.controller); }
    /** Every key character except air and any, in file order. */
    public Map<Character, Part> parts() { return Collections.unmodifiableMap(this.key); }

    /** How many times each key character appears in the layers. */
    public Map<Character, Integer> counts() {
        Map<Character, Integer> out = new LinkedHashMap<>();
        for (Part[][] layer : this.grid) for (Part[] row : layer) for (Part p : row) {
            if (!p.any && !p.air) out.merge(p.symbol, 1, Integer::sum);
        }
        return out;
    }

    /** The facings this pattern can be built in. */
    public List<Direction> facings() {
        return this.rotate ? List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST) : List.of(Direction.NORTH);
    }

    public static Rotation rotation(Direction facing) {
        return switch (facing) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static Mirror mirror(boolean mirrored) {
        return mirrored ? Mirror.FRONT_BACK : Mirror.NONE;
    }

    /** The world position of grid cell (x, y, z) when the controller sits at {@code controller}. */
    public BlockPos world(BlockPos controller, Direction facing, boolean mirrored, int x, int y, int z) {
        int dx = x - this.cx, dz = z - this.cz;
        if (mirrored) dx = -dx;
        int rx, rz;
        switch (facing) {
            case EAST -> { rx = -dz; rz = dx; }
            case SOUTH -> { rx = -dx; rz = -dz; }
            case WEST -> { rx = dz; rz = -dx; }
            default -> { rx = dx; rz = dz; }
        }
        return controller.offset(rx, y - this.cy, rz);
    }

    /** Calls {@code visit} for every cell that is not "any block". */
    public void forEach(BlockPos controller, Direction facing, boolean mirrored, CellVisitor visit) {
        for (int y = 0; y < this.height; y++) for (int z = 0; z < this.depth; z++) for (int x = 0; x < this.width; x++) {
            Part p = this.grid[y][z][x];
            if (p.any) continue;
            visit.visit(world(controller, facing, mirrored, x, y, z), p);
        }
    }

    @FunctionalInterface
    public interface CellVisitor {
        void visit(BlockPos pos, Part part);
    }

    /** Whether the blocks around {@code controller} make this pattern built facing {@code facing}. */
    public boolean matches(BlockLookup level, BlockPos controller, Direction facing, boolean mirrored) {
        Rotation r = rotation(facing);
        Mirror m = mirror(mirrored);
        for (int y = 0; y < this.height; y++) for (int z = 0; z < this.depth; z++) for (int x = 0; x < this.width; x++) {
            Part p = this.grid[y][z][x];
            if (p.any) continue;
            if (!p.accepts(level.get(world(controller, facing, mirrored, x, y, z)), r, m)) return false;
        }
        return true;
    }

    /** Reads block states; a Level's getBlockState fits. */
    @FunctionalInterface
    public interface BlockLookup {
        BlockState get(BlockPos pos);
    }

    // ---- reading

    public static MultiblockPattern parse(JsonObject json) {
        if (!json.has("layers") || !json.get("layers").isJsonArray()) throw new IllegalArgumentException("no layers");
        JsonArray layers = json.getAsJsonArray("layers");
        if (layers.isEmpty()) throw new IllegalArgumentException("no layers");
        String controllerText = json.has("controller") ? json.get("controller").getAsString() : "";
        if (controllerText.length() != 1) throw new IllegalArgumentException("controller must be one character");
        char controller = controllerText.charAt(0);

        Map<Character, Part> key = new LinkedHashMap<>();
        JsonObject keyJson = json.has("key") ? json.getAsJsonObject("key") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : keyJson.entrySet()) {
            if (e.getKey().length() != 1) throw new IllegalArgumentException("key '" + e.getKey() + "' must be one character");
            char c = e.getKey().charAt(0);
            key.put(c, part(c, e.getValue()));
        }
        if (!key.containsKey(controller)) throw new IllegalArgumentException("the controller '" + controller + "' is not in the key");

        int height = layers.size(), depth = -1, width = -1;
        Part[][][] grid = new Part[height][][];
        int cx = -1, cy = -1, cz = -1;
        for (int y = 0; y < height; y++) {
            JsonArray rows = layers.get(y).getAsJsonArray();
            if (depth < 0) depth = rows.size();
            if (rows.size() != depth || depth == 0) throw new IllegalArgumentException("layer " + y + " has " + rows.size() + " rows, expected " + depth);
            grid[y] = new Part[depth][];
            for (int z = 0; z < depth; z++) {
                String row = rows.get(z).getAsString();
                if (width < 0) width = row.length();
                if (row.length() != width || width == 0) throw new IllegalArgumentException("layer " + y + " row " + z + " is " + row.length() + " wide, expected " + width);
                grid[y][z] = new Part[width];
                for (int x = 0; x < width; x++) {
                    char c = row.charAt(x);
                    Part p = key.get(c);
                    if (p == null) {
                        if (c == ' ') p = ANY;
                        else if (c == '.') p = AIR;
                        else throw new IllegalArgumentException("'" + c + "' in layer " + y + " is not in the key");
                    }
                    if (c == controller) {
                        if (cx >= 0) throw new IllegalArgumentException("the controller appears more than once");
                        cx = x;
                        cy = y;
                        cz = z;
                    }
                    grid[y][z][x] = p;
                }
            }
        }
        if (cx < 0) throw new IllegalArgumentException("the controller does not appear in the layers");
        boolean rotate = !json.has("rotate") || json.get("rotate").getAsBoolean();
        boolean mirror = json.has("mirror") && json.get("mirror").getAsBoolean();
        String name = json.has("name") ? json.get("name").getAsString() : null;
        return new MultiblockPattern(grid, key, controller, cx, cy, cz, rotate, mirror, name);
    }

    private static Part part(char c, JsonElement json) {
        List<Option> options = new ArrayList<>();
        if (json.isJsonArray()) {
            for (JsonElement el : json.getAsJsonArray()) option(el, options);
        } else if (json.isJsonPrimitive() && (json.getAsString().equals("air") || json.getAsString().equals("minecraft:air"))) {
            return new Part(c, true, false, List.of());
        } else if (json.isJsonPrimitive() && json.getAsString().equals("*")) {
            return new Part(c, false, true, List.of());
        } else {
            option(json, options);
        }
        return new Part(c, false, false, List.copyOf(options));
    }

    private static void option(JsonElement json, List<Option> out) {
        String id;
        Map<String, String> state = new LinkedHashMap<>();
        if (json.isJsonObject()) {
            JsonObject o = json.getAsJsonObject();
            id = o.has("tag") ? "#" + o.get("tag").getAsString() : o.get("block").getAsString();
            if (o.has("state")) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("state").entrySet()) state.put(e.getKey(), e.getValue().getAsString());
            }
        } else {
            id = json.getAsString();
        }
        if (id.startsWith("#")) {
            out.add(new Option(null, FTags.block(FId.of(id.substring(1))), Map.copyOf(state)));
            return;
        }
        FId fid = FId.of(id);
        Block b = FVanilla.block(fid);
        // a block from a mod that is not installed leaves the option out; the part may still have others
        if (b == null || (b == Blocks.AIR && !fid.path().equals("air"))) return;
        out.add(new Option(b, null, Map.copyOf(state)));
    }

    static BlockState withState(BlockState base, Map<String, String> state) {
        BlockState s = base;
        for (Map.Entry<String, String> e : state.entrySet()) {
            Property<?> p = s.getBlock().getStateDefinition().getProperty(e.getKey());
            if (p == null) return null;
            s = set(s, p, e.getValue());
            if (s == null) return null;
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState set(BlockState s, Property<T> p, String value) {
        Optional<T> v = p.getValue(value);
        return v.isPresent() ? s.setValue(p, v.get()) : null;
    }
}
