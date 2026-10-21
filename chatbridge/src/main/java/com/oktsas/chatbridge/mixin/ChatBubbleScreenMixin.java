package com.oktsas.chatbridge.mixin;

import com.niuqu.chatbubble.render.ChatBubbleScreen;
import com.oktsas.chatbridge.ChatButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the button column to e33chat's chat screen.
 *
 * <p>Mixin remapping is left at its default (on) on purpose: the one Minecraft method we hook is written
 * with its development name ({@code mouseClicked}) so the annotation processor puts the SRG name
 * ({@code m_6375_}) into the refmap for the production jar. Everything that belongs to e33chat keeps its own
 * name - {@code renderSidebar}, {@code getSidebarScreenX}, {@code barTop}, ... - and since a mod jar is not
 * remapped, Mixin finds no refmap entry for those and uses the literal name, which is exactly right.</p>
 *
 * <p>Its mixin config uses {@code defaultRequire: 0}, so if a future e33chat renames these members the
 * injection only logs a warning and the buttons silently disappear instead of crashing the client.</p>
 */
@Mixin(ChatBubbleScreen.class)
public abstract class ChatBubbleScreenMixin {

    @Shadow(remap = false)
    private int barTop;

    @Shadow(remap = false)
    private static boolean sidebarOpen;

    @Shadow(remap = false)
    private int getSidebarScreenX() {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    private float getSidebarAnimProgress() {
        throw new AssertionError();
    }

    @Inject(method = "renderSidebar", at = @At("TAIL"), remap = false)
    private void oktsas$renderButtons(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                      CallbackInfo ci) {
        if (!sidebarOpen || getSidebarAnimProgress() < 0.55F) {
            return;
        }
        ChatButtonRenderer.render(graphics, getSidebarScreenX(), this.barTop, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void oktsas$clickButtons(double mouseX, double mouseY, int button,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (!sidebarOpen || getSidebarAnimProgress() < 0.55F) {
            return;
        }
        if (ChatButtonRenderer.click(getSidebarScreenX(), this.barTop, mouseX, mouseY, button)) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
