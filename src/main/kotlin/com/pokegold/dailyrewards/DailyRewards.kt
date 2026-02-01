package com.pokegold.dailyrewards

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.Inventory
import net.minecraft.inventory.SimpleInventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.ScreenHandlerType
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.io.File
import java.time.*
import java.util.*

class DailyRewards : ModInitializer {

    companion object {
        const val MOD_ID = "dailyrewards"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)
        
        lateinit var REWARDS_SCREEN_HANDLER: ScreenHandlerType<RewardsScreenHandler>
        lateinit var INSTANCE: DailyRewards
        
        // Player data: UUID -> PlayerRewardData
        val playerData = mutableMapOf<UUID, PlayerRewardData>()
        
        // Rewards config
        var rewardsConfig = RewardsConfig()
        
        private lateinit var dataFile: File
        private lateinit var configFile: File
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
        
        // EST timezone
        val EST_ZONE: ZoneId = ZoneId.of("America/New_York")
        const val CLAIM_HOUR = 19 // 7 PM EST
    }

    override fun onInitialize() {
        INSTANCE = this
        LOGGER.info("DailyRewards initialized!")
        
        REWARDS_SCREEN_HANDLER = Registry.register(
            Registries.SCREEN_HANDLER,
            Identifier.of(MOD_ID, "rewards"),
            ExtendedScreenHandlerType(::RewardsScreenHandler, RewardsScreenData.CODEC)
        )
        
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("rewards")
                    .executes { ctx -> openRewardsGUI(ctx) }
            )
            dispatcher.register(
                CommandManager.literal("daily")
                    .executes { ctx -> openRewardsGUI(ctx) }
            )
            dispatcher.register(
                CommandManager.literal("dailyrewards")
                    .then(CommandManager.literal("reload")
                        .requires { it.hasPermissionLevel(2) }
                        .executes { ctx -> reloadConfig(ctx) }
                    )
                    .then(CommandManager.literal("reset")
                        .requires { it.hasPermissionLevel(2) }
                        .then(CommandManager.argument("player", net.minecraft.command.argument.EntityArgumentType.player())
                            .executes { ctx -> resetPlayer(ctx) }
                        )
                    )
            )
        }
        
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            loadData(server)
            loadConfig(server)
        }
        
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            saveDataToFile(server)
        }
    }
    
    private fun openRewardsGUI(ctx: CommandContext<ServerCommandSource>): Int {
        val player = ctx.source.player ?: return 0
        
        val data = playerData.getOrPut(player.uuid) { PlayerRewardData() }
        
        player.openHandledScreen(RewardsScreenHandlerFactory(player, data))
        return 1
    }
    
    private fun reloadConfig(ctx: CommandContext<ServerCommandSource>): Int {
        loadConfig(ctx.source.server)
        ctx.source.sendFeedback({ Text.literal("Daily Rewards config reloaded!").formatted(Formatting.GREEN) }, true)
        return 1
    }
    
    private fun resetPlayer(ctx: CommandContext<ServerCommandSource>): Int {
        val target = net.minecraft.command.argument.EntityArgumentType.getPlayer(ctx, "player")
        playerData[target.uuid] = PlayerRewardData()
        saveDataToFile(ctx.source.server)
        ctx.source.sendFeedback({ 
            Text.literal("Reset daily rewards progress for ${target.name.string}").formatted(Formatting.YELLOW) 
        }, true)
        return 1
    }
    
    private fun loadData(server: MinecraftServer) {
        dataFile = server.runDirectory.resolve("config/dailyrewards-data.json").toFile()
        if (dataFile.exists()) {
            try {
                val type = object : TypeToken<MutableMap<String, PlayerRewardData>>() {}.type
                val loaded: MutableMap<String, PlayerRewardData> = gson.fromJson(dataFile.readText(), type)
                playerData.clear()
                loaded.forEach { (key, value) ->
                    playerData[UUID.fromString(key)] = value
                }
                LOGGER.info("Loaded ${playerData.size} player reward records")
            } catch (e: Exception) {
                LOGGER.error("Failed to load player data", e)
            }
        }
    }
    
    fun saveDataToFile(server: MinecraftServer) {
        try {
            dataFile.parentFile.mkdirs()
            val toSave = playerData.mapKeys { it.key.toString() }
            dataFile.writeText(gson.toJson(toSave))
        } catch (e: Exception) {
            LOGGER.error("Failed to save player data", e)
        }
    }
    
    private fun loadConfig(server: MinecraftServer) {
        configFile = server.runDirectory.resolve("config/dailyrewards-config.json").toFile()
        if (configFile.exists()) {
            try {
                rewardsConfig = gson.fromJson(configFile.readText(), RewardsConfig::class.java)
                LOGGER.info("Loaded rewards config with ${rewardsConfig.rewards.size} days")
            } catch (e: Exception) {
                LOGGER.error("Failed to load config", e)
                rewardsConfig = RewardsConfig()
                saveConfig()
            }
        } else {
            rewardsConfig = RewardsConfig()
            saveConfig()
            LOGGER.info("Created default rewards config")
        }
    }
    
    private fun saveConfig() {
        try {
            configFile.parentFile.mkdirs()
            configFile.writeText(gson.toJson(rewardsConfig))
        } catch (e: Exception) {
            LOGGER.error("Failed to save config", e)
        }
    }
}

data class PlayerRewardData(
    var currentDay: Int = 1,
    var lastClaimDate: String = "",
    var currentMonth: Int = -1
)

data class RewardsConfig(
    val rewards: Map<Int, DayReward> = (1..30).associateWith { day ->
        when {
            day <= 7 -> DayReward(
                commands = listOf("ultraeconomy give %player% ${500 * day} pokegold"),
                displayItem = "minecraft:gold_nugget",
                displayName = "&6Day $day: ${500 * day} PokéGold"
            )
            day <= 14 -> DayReward(
                commands = listOf("ultraeconomy give %player% ${1000 * (day - 7)} pokegold"),
                displayItem = "minecraft:gold_ingot",
                displayName = "&6Day $day: ${1000 * (day - 7)} PokéGold"
            )
            day <= 21 -> DayReward(
                commands = listOf("ultraeconomy give %player% ${2000 * (day - 14)} pokegold"),
                displayItem = "minecraft:gold_block",
                displayName = "&6Day $day: ${2000 * (day - 14)} PokéGold"
            )
            day <= 28 -> DayReward(
                commands = listOf(
                    "ultraeconomy give %player% ${5000 * (day - 21)} pokegold",
                    "give %player% minecraft:diamond ${day - 21}"
                ),
                displayItem = "minecraft:diamond",
                displayName = "&bDay $day: Special Reward"
            )
            else -> DayReward(
                commands = listOf(
                    "ultraeconomy give %player% 50000 pokegold",
                    "give %player% minecraft:diamond_block ${day - 28}"
                ),
                displayItem = "minecraft:diamond_block",
                displayName = "&dDay $day: Premium Reward"
            )
        }
    }
)

data class DayReward(
    val commands: List<String> = listOf(),
    val displayItem: String = "minecraft:paper",
    val displayName: String = "Reward"
)

data class RewardsScreenData(val currentDay: Int, val canClaim: Boolean) {
    companion object {
        val CODEC: PacketCodec<RegistryByteBuf, RewardsScreenData> = PacketCodec.tuple(
            PacketCodecs.INTEGER, RewardsScreenData::currentDay,
            PacketCodecs.BOOL, RewardsScreenData::canClaim,
            ::RewardsScreenData
        )
    }
}

class RewardsScreenHandlerFactory(
    private val player: ServerPlayerEntity,
    private val data: PlayerRewardData
) : ExtendedScreenHandlerFactory<RewardsScreenData> {
    
    override fun createMenu(syncId: Int, playerInventory: PlayerInventory, player: PlayerEntity): ScreenHandler {
        return RewardsScreenHandler(syncId, playerInventory, data, player as? ServerPlayerEntity)
    }
    
    override fun getDisplayName(): Text = Text.literal("Daily Rewards")
    
    override fun getScreenOpeningData(player: ServerPlayerEntity): RewardsScreenData {
        return RewardsScreenData(data.currentDay, canClaimToday(data))
    }
}

fun canClaimToday(data: PlayerRewardData): Boolean {
    val now = ZonedDateTime.now(DailyRewards.EST_ZONE)
    val today = now.toLocalDate().toString()
    
    // Check if already claimed today
    if (data.lastClaimDate == today) {
        return false
    }
    
    return true
}

fun checkMonthReset(data: PlayerRewardData): Boolean {
    val now = ZonedDateTime.now(DailyRewards.EST_ZONE)
    val currentMonth = now.monthValue
    
    if (data.currentMonth != currentMonth) {
        data.currentMonth = currentMonth
        data.currentDay = 1
        data.lastClaimDate = ""
        return true
    }
    return false
}

class RewardsScreenHandler(
    syncId: Int,
    private val playerInventory: PlayerInventory,
    private var data: PlayerRewardData,
    private val serverPlayer: ServerPlayerEntity?
) : ScreenHandler(DailyRewards.REWARDS_SCREEN_HANDLER, syncId) {
    
    private val inventory: SimpleInventory = SimpleInventory(54)
    
    constructor(syncId: Int, playerInventory: PlayerInventory, screenData: RewardsScreenData) : 
        this(syncId, playerInventory, PlayerRewardData(screenData.currentDay), null)
    
    init {
        // Add GUI slots
        for (row in 0 until 6) {
            for (col in 0 until 9) {
                val slotIndex = row * 9 + col
                addSlot(RewardSlot(inventory, slotIndex, 8 + col * 18, 18 + row * 18))
            }
        }
        
        // Add player inventory slots
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18))
            }
        }
        
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 8 + col * 18, 198))
        }
        
        if (serverPlayer != null) {
            checkMonthReset(data)
            refreshDisplay()
        }
    }
    
    private fun refreshDisplay() {
        // Clear inventory
        for (i in 0 until 54) {
            inventory.setStack(i, ItemStack.EMPTY)
        }
        
        // Fill borders with glass
        val glass = ItemStack(Items.GRAY_STAINED_GLASS_PANE)
        glass.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "))
        for (i in 0 until 9) {
            inventory.setStack(i, glass.copy())
            inventory.setStack(45 + i, glass.copy())
        }
        for (i in listOf(9, 17, 18, 26, 27, 35, 36, 44)) {
            inventory.setStack(i, glass.copy())
        }
        
        // Display days 1-30 in the middle area
        val daySlots = listOf(
            10, 11, 12, 13, 14, 15, 16,  // Row 1: Days 1-7
            19, 20, 21, 22, 23, 24, 25,  // Row 2: Days 8-14
            28, 29, 30, 31, 32, 33, 34,  // Row 3: Days 15-21
            37, 38, 39, 40, 41, 42, 43   // Row 4: Days 22-28
        )
        
        for ((index, slot) in daySlots.withIndex()) {
            val day = index + 1
            if (day <= 28) {
                inventory.setStack(slot, createDayItem(day))
            }
        }
        
        // Days 29 and 30 in the bottom row
        inventory.setStack(48, createDayItem(29))
        inventory.setStack(50, createDayItem(30))
        
        // Info panel instead of claim button
        val infoItem = ItemStack(Items.BOOK)
        val canClaim = canClaimToday(data)
        if (canClaim) {
            infoItem.set(DataComponentTypes.CUSTOM_NAME, 
                Text.literal("§aClick Day ${data.currentDay} to claim!"))
        } else {
            infoItem.set(DataComponentTypes.CUSTOM_NAME, 
                Text.literal("§eCome back tomorrow!"))
        }
        val infoLore = listOf(
            Text.literal(""),
            Text.literal("§7Current Day: §f${data.currentDay}"),
            Text.literal("§7Days Claimed: §f${data.currentDay - 1}/30")
        )
        infoItem.set(DataComponentTypes.LORE, net.minecraft.component.type.LoreComponent(infoLore))
        inventory.setStack(49, infoItem)
        
        sendContentUpdates()
    }
    
    private fun createDayItem(day: Int): ItemStack {
        val reward = DailyRewards.rewardsConfig.rewards[day]
        val isClaimed = day < data.currentDay
        val isCurrent = day == data.currentDay
        val isLocked = day > data.currentDay
        
        val item = when {
            isClaimed -> {
                val stack = ItemStack(Items.LIME_STAINED_GLASS_PANE)
                stack.set(DataComponentTypes.CUSTOM_NAME, 
                    Text.literal("Day $day ✓").formatted(Formatting.GREEN))
                addLore(stack, reward, "Claimed!")
                stack
            }
            isCurrent -> {
                val itemId = reward?.displayItem ?: "minecraft:chest"
                val itemType = Registries.ITEM.get(Identifier.of(itemId))
                val stack = ItemStack(itemType)
                val name = reward?.displayName?.replace("&", "§") ?: "Day $day"
                stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name))
                if (canClaimToday(data)) {
                    addLore(stack, reward, "§aClick to claim!")
                } else {
                    addLore(stack, reward, "§cAlready claimed today!")
                }
                stack
            }
            isLocked -> {
                val itemId = reward?.displayItem ?: "minecraft:chest"
                val itemType = Registries.ITEM.get(Identifier.of(itemId))
                val stack = ItemStack(itemType)
                val name = reward?.displayName?.replace("&", "§") ?: "Day $day"
                stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§7$name"))
                addLore(stack, reward, "§7Locked")
                stack
            }
            else -> ItemStack.EMPTY
        }
        
        return item
    }
    
    private fun addLore(stack: ItemStack, reward: DayReward?, status: String) {
        val loreList = mutableListOf<Text>()
        loreList.add(Text.literal(""))
        
        if (reward != null) {
            loreList.add(Text.literal("§eRewards:").formatted(Formatting.YELLOW))
            for (cmd in reward.commands) {
                val readable = formatCommand(cmd)
                loreList.add(Text.literal("§7• $readable"))
            }
            loreList.add(Text.literal(""))
        }
        
        loreList.add(Text.literal(status))
        
        stack.set(DataComponentTypes.LORE, net.minecraft.component.type.LoreComponent(loreList))
    }
    
    private fun formatCommand(cmd: String): String {
        // Make commands more readable for players
        return when {
            cmd.contains("ultraeconomy give") -> {
                val match = Regex("ultraeconomy give %player% (\\d+) (\\w+)").find(cmd)
                if (match != null) {
                    val amount = match.groupValues[1]
                    val currency = match.groupValues[2]
                    "$amount $currency"
                } else cmd
            }
            cmd.contains("give %player%") -> {
                val match = Regex("give %player% ([\\w:]+) (\\d+)").find(cmd)
                if (match != null) {
                    val item = match.groupValues[1].replace("minecraft:", "").replace("_", " ")
                    val amount = match.groupValues[2]
                    "$amount $item"
                } else cmd
            }
            else -> cmd.replace("%player%", "you")
        }
    }
    
    // Map slot index to day number
    private val slotToDay: Map<Int, Int> = mapOf(
        10 to 1, 11 to 2, 12 to 3, 13 to 4, 14 to 5, 15 to 6, 16 to 7,
        19 to 8, 20 to 9, 21 to 10, 22 to 11, 23 to 12, 24 to 13, 25 to 14,
        28 to 15, 29 to 16, 30 to 17, 31 to 18, 32 to 19, 33 to 20, 34 to 21,
        37 to 22, 38 to 23, 39 to 24, 40 to 25, 41 to 26, 42 to 27, 43 to 28,
        48 to 29, 50 to 30
    )
    
    override fun onSlotClick(slotIndex: Int, button: Int, actionType: SlotActionType, player: PlayerEntity) {
        if (player !is ServerPlayerEntity) return
        if (slotIndex < 0 || slotIndex >= 54) return
        
        // Check if clicked slot is a day slot
        val clickedDay = slotToDay[slotIndex]
        if (clickedDay != null && clickedDay == data.currentDay) {
            if (canClaimToday(data)) {
                claimReward(player)
            } else {
                player.world.playSound(null, player.x, player.y, player.z, SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 0.5f, 1.0f)
            }
        }
    }
    
    private fun claimReward(player: ServerPlayerEntity) {
        val reward = DailyRewards.rewardsConfig.rewards[data.currentDay]
        
        if (reward != null) {
            // Execute reward commands
            for (command in reward.commands) {
                val cmd = command.replace("%player%", player.name.string)
                player.server.commandManager.executeWithPrefix(
                    player.server.commandSource,
                    cmd
                )
            }
        }
        
        // Update player data
        val now = ZonedDateTime.now(DailyRewards.EST_ZONE)
        data.lastClaimDate = now.toLocalDate().toString()
        data.currentDay = minOf(data.currentDay + 1, 31)
        
        // Save data
        DailyRewards.playerData[player.uuid] = data
        DailyRewards.INSTANCE.saveDataToFile(player.server)
        
        // Play sound and message
        player.world.playSound(null, player.x, player.y, player.z, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 1.0f)
        player.sendMessage(
            Text.literal("You claimed your Day ${data.currentDay - 1} reward!").formatted(Formatting.GREEN),
            false
        )
        
        // Refresh display
        refreshDisplay()
    }
    
    override fun quickMove(player: PlayerEntity, slot: Int): ItemStack = ItemStack.EMPTY
    
    override fun canUse(player: PlayerEntity): Boolean = true
}

class RewardSlot(inventory: Inventory, index: Int, x: Int, y: Int) : Slot(inventory, index, x, y) {
    override fun canTakeItems(playerEntity: PlayerEntity): Boolean = false
    override fun canInsert(stack: ItemStack): Boolean = false
}
