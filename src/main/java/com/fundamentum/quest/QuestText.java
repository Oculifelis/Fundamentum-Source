package com.fundamentum.quest;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Chat lines for quests. */
final class QuestText {
    private QuestText() { }

    static Component offers(List<QuestDef> list) {
        MutableComponent c = Component.literal("Quests offered:").withStyle(ChatFormatting.GOLD);
        for (QuestDef d : list) {
            c.append(Component.literal("\n " + d.displayName()).withStyle(ChatFormatting.WHITE));
            if (!d.description().isEmpty()) c.append(Component.literal(" - " + d.description()).withStyle(ChatFormatting.GRAY));
            c.append(Component.literal(" "));
            c.append(Component.literal("[Accept]").withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand("/quests accept " + d.id()))));
        }
        return c;
    }

    static Component completed(QuestDef d) {
        return Component.literal("Quest complete: " + d.displayName()).withStyle(ChatFormatting.GREEN);
    }

    static Component failed(QuestDef d, boolean abandoned) {
        return Component.literal((abandoned ? "Quest abandoned: " : "Quest failed: ") + d.displayName()).withStyle(ChatFormatting.RED);
    }

    static Component npcIdle(Component name) {
        return Component.empty().append(name).append(" has nothing for now.").withStyle(ChatFormatting.GRAY);
    }
}
