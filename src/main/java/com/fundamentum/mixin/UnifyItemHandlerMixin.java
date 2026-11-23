package com.fundamentum.mixin;

import com.fundamentum.unify.Unify;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.StacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Items inserted into a mod's item storage (NeoForge's stack-backed handlers) become the kept item when unification replaces them. */
@Mixin(StacksResourceHandler.class)
public abstract class UnifyItemHandlerMixin {
    @ModifyVariable(method = "insert(ILnet/neoforged/neoforge/transfer/resource/Resource;ILnet/neoforged/neoforge/transfer/transaction/TransactionContext;)I",
        at = @At("HEAD"), argsOnly = true)
    private Resource fundamentum$unify(Resource resource) {
        if (!(resource instanceof ItemResource item) || item.isEmpty() || !Unify.ENABLED.get() || !Unify.CONTAINERS.get()) return resource;
        ItemStack s = item.toStack(1);
        ItemStack c = Unify.convert(s);
        return c == s ? resource : ItemResource.of(c);
    }
}
