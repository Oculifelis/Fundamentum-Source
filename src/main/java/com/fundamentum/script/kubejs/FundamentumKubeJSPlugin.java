package com.fundamentum.script.kubejs;

import com.fundamentum.event.FEvents;
import com.fundamentum.script.FScript;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.event.EventResult;
import dev.latvian.mods.kubejs.event.EventTargetType;
import dev.latvian.mods.kubejs.event.KubeEvent;
import dev.latvian.mods.kubejs.event.TargetedEventHandler;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import dev.latvian.mods.kubejs.script.ScriptType;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;

/**
 * KubeJS 8 support. Loaded by KubeJS from {@code kubejs.plugins.txt}; nothing here runs without KubeJS.
 * <pre>
 * // server_scripts
 * FundamentumEvents.event('fundamentum:quest_completed', e => console.log(e.id + ' ' + e.fields))
 * FundamentumEvents.event('fundamentum:faction_join', e => { if (e.get('faction') == 'mymod:bandits') e.cancel() })
 * Fundamentum.addRep(player, 'mymod:guild', 10)
 * Fundamentum.questSignal(player, 'script', 'mymod:rang_bell', 1)
 * </pre>
 * {@code Fundamentum} is {@link FScript}. Script handlers are cleared and re-read by KubeJS on reload; the bridge into
 * Fundamentum's events is added once.
 */
public final class FundamentumKubeJSPlugin implements KubeJSPlugin {
    public static final EventGroup GROUP = EventGroup.of("FundamentumEvents");
    /** Every Fundamentum event, by id as the target; {@code e.cancel()} cancels a cancellable one. */
    public static final TargetedEventHandler<String> EVENT =
        GROUP.server("event", () -> FundamentumEventJS.class).hasResult().supportsTarget(EventTargetType.STRING);

    private static boolean bridged;

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(GROUP);
    }

    @Override
    public void registerBindings(BindingRegistry bindings) {
        bindings.add("Fundamentum", FScript.class);
    }

    @Override
    public void init() {
        if (bridged) return;
        bridged = true;
        FEvents.bridge((type, event, cancelled) -> {
            String id = type.id().toString();
            if (!EVENT.hasListeners(id) && !EVENT.hasListeners()) return false;
            FundamentumEventJS js = new FundamentumEventJS(id, event, type.cancellable(), cancelled);
            EventResult r = EVENT.post(ScriptType.SERVER, id, js);
            return type.cancellable() && r.interruptFalse();
        });
    }

    /** What a script sees: the event id, its fields by name, its player, and {@code cancel()} from KubeJS. */
    public static final class FundamentumEventJS implements KubeEvent {
        private final String id;
        private final Record event;
        private final boolean cancellable;
        private final boolean cancelled;

        FundamentumEventJS(String id, Record event, boolean cancellable, boolean cancelled) {
            this.id = id;
            this.event = event;
            this.cancellable = cancellable;
            this.cancelled = cancelled;
        }

        public String getId() { return id; }

        public Record getEvent() { return event; }

        public Map<String, Object> getFields() { return FEvents.fields(event); }

        public Object get(String field) { return FEvents.fields(event).get(field); }

        public ServerPlayer getPlayer() { return FEvents.player(event); }

        public boolean isCancellable() { return cancellable; }

        /** Whether a Java listener already cancelled it before scripts ran. */
        public boolean isCancelled() { return cancelled; }
    }
}
