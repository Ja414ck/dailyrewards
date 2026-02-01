package com.pokegold.dailyrewards

import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.client.gui.screen.ingame.HandledScreens
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.text.Text
import net.minecraft.util.Identifier

class DailyRewardsClient : ClientModInitializer {
    
    override fun onInitializeClient() {
        HandledScreens.register(DailyRewards.REWARDS_SCREEN_HANDLER, ::RewardsScreen)
    }
}

class RewardsScreen(
    handler: RewardsScreenHandler,
    inventory: PlayerInventory,
    title: Text
) : HandledScreen<RewardsScreenHandler>(handler, inventory, title) {
    
    companion object {
        val TEXTURE: Identifier = Identifier.of("minecraft", "textures/gui/container/generic_54.png")
    }
    
    init {
        backgroundHeight = 222
        playerInventoryTitleY = backgroundHeight - 94
    }
    
    override fun drawBackground(context: DrawContext, delta: Float, mouseX: Int, mouseY: Int) {
        val x = (width - backgroundWidth) / 2
        val y = (height - backgroundHeight) / 2
        
        context.drawTexture(TEXTURE, x, y, 0, 0, backgroundWidth, 6 * 18 + 17)
        context.drawTexture(TEXTURE, x, y + 6 * 18 + 17, 0, 126, backgroundWidth, 96)
    }
    
    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(context, mouseX, mouseY, delta)
        super.render(context, mouseX, mouseY, delta)
        drawMouseoverTooltip(context, mouseX, mouseY)
    }
}
