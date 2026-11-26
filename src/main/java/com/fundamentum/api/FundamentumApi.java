package com.fundamentum.api;

/**
 * The API version addons can check at runtime. {@link #VERSION} is {@code major * 100 + minor} of the Fundamentum
 * release that last changed the API (2.0 is 200, 2.1 would be 201); patch releases never change it.
 * <p>What counts as API: every public class and member in {@code com.fundamentum} and its packages, except what is
 * marked {@link Internal}, the {@code mixin}, {@code command} and {@code compat} packages, and classes named
 * {@code *SelfTest} or {@code *Test}. Data file paths under {@code data/<namespace>/fundamentum/}, registry ids,
 * tag names and event ids are API too.
 * <p>Rules: a minor version only adds. Anything removed is first {@code @Deprecated(forRemoval = true)} for at least
 * one minor version, and goes at the next major one.
 * <pre>
 * if (!FundamentumApi.atLeast(200)) throw new IllegalStateException("needs Fundamentum 2.0");
 * </pre>
 */
public final class FundamentumApi {
    private FundamentumApi() { }

    /** {@code major * 100 + minor} of the release that last changed the API. */
    public static final int VERSION = 200;

    /** The major version; it changes only when frozen API or data formats break. */
    public static final int MAJOR = VERSION / 100;

    /** Whether the loaded Fundamentum has at least this API version (e.g. {@code atLeast(200)}). */
    public static boolean atLeast(int version) {
        return VERSION >= version;
    }
}
