package com.fundamentum.port;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.resources.Identifier;

/**
 * A namespaced id ({@code namespace:path}) that is the same type on every Minecraft version. Vanilla calls it
 * {@code ResourceLocation} up to 1.21.x and {@code Identifier} on 26.x; {@link #vanilla()} gives whichever this version
 * has, and {@link #from} takes it back. The rules are vanilla's: namespace {@code [a-z0-9_.-]}, path {@code [a-z0-9_./-]},
 * a bare path means {@code minecraft}.
 */
public final class FId implements Comparable<FId> {
    /** Reads and writes the plain {@code "namespace:path"} string. */
    public static final Codec<FId> CODEC = Codec.STRING.comapFlatMap(FId::read, FId::toString);

    private final String namespace;
    private final String path;

    private FId(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    /** {@code "ns:path"}, or {@code "path"} for {@code minecraft:path}. Throws on characters vanilla refuses. */
    public static FId of(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? of("minecraft", id) : of(id.substring(0, colon), id.substring(colon + 1));
    }

    public static FId of(String namespace, String path) {
        if (!validNamespace(namespace)) throw new IllegalArgumentException("Bad namespace in id " + namespace + ":" + path);
        if (!validPath(path)) throw new IllegalArgumentException("Bad path in id " + namespace + ":" + path);
        return new FId(namespace, path);
    }

    /** The id, or null when the text is not a valid id. */
    public static FId tryOf(String id) {
        try {
            return of(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isValid(String id) {
        return tryOf(id) != null;
    }

    /** This version's vanilla id. */
    public Identifier vanilla() {
        return Identifier.fromNamespaceAndPath(this.namespace, this.path);
    }

    /** The FId for this version's vanilla id. */
    public static FId from(Identifier id) {
        return new FId(id.getNamespace(), id.getPath());
    }

    public String namespace() {
        return this.namespace;
    }

    public String path() {
        return this.path;
    }

    public FId withPath(String path) {
        return of(this.namespace, path);
    }

    public FId withSuffix(String suffix) {
        return of(this.namespace, this.path + suffix);
    }

    public FId withPrefix(String prefix) {
        return of(this.namespace, prefix + this.path);
    }

    /** {@code namespace_path} with slashes as underscores: a safe file or key name. */
    public String flat() {
        return this.namespace + "_" + this.path.replace('/', '_');
    }

    @Override
    public String toString() {
        return this.namespace + ":" + this.path;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FId other && other.namespace.equals(this.namespace) && other.path.equals(this.path);
    }

    @Override
    public int hashCode() {
        return 31 * this.namespace.hashCode() + this.path.hashCode();
    }

    @Override
    public int compareTo(FId o) {
        int c = this.namespace.compareTo(o.namespace);
        return c != 0 ? c : this.path.compareTo(o.path);
    }

    private static DataResult<FId> read(String s) {
        FId id = tryOf(s);
        return id != null ? DataResult.success(id) : DataResult.error(() -> "Not a valid id: " + s);
    }

    private static boolean validNamespace(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c == '_' || c == '-' || c == '.' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    private static boolean validPath(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c == '_' || c == '-' || c == '.' || c == '/' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }
}
