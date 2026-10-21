package com.oktsas.onekeytosetaserver.domain;

import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Enforces domain protection and announces entering/leaving.
 *
 * <p>Break and place are checked with a possibly-null "player", so machines that are not a player
 * (Create contraptions, dispensers, pistons, explosions) are treated as intruders: they cannot break
 * or place inside a domain that forbids it. Explosions additionally have their block list filtered,
 * so TNT cannot even destroy protected blocks.</p>
 */
public class DomainProtection {

    private static final long WARN_COOLDOWN_MILLIS = 1500L;
    private static final Map<UUID, Long> LAST_WARN = new HashMap<>();
    private static final Map<UUID, Long> LAST_ANNOUNCE = new HashMap<>();

    public DomainProtection() {
    }

    // ------------------------------------------------------------------ blocks

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onBreak(BlockEvent.BreakEvent event) {
        LevelAccessor accessor = event.getLevel();
        if (!(accessor instanceof Level level)) {
            return;
        }
        if (allowed(event.getPlayer(), level, event.getPos(), Domain.Flag.BREAK)) {
            return;
        }
        event.setCanceled(true);
        warn(event.getPlayer(), level, event.getPos(), "§c这里禁止破坏方块");
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        LevelAccessor accessor = event.getLevel();
        if (!(accessor instanceof Level level)) {
            return;
        }
        Player player = event.getEntity() instanceof Player p ? p : null;
        if (allowed(player, level, event.getPos(), Domain.Flag.PLACE)) {
            return;
        }
        event.setCanceled(true);
        warn(player, level, event.getPos(), "§c这里禁止放置方块");
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onExplode(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        Iterator<BlockPos> iterator = event.getAffectedBlocks().iterator();
        boolean blocked = false;
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (!allowed(null, level, pos, Domain.Flag.EXPLOSION)) {
                iterator.remove();
                blocked = true;
            }
        }
        if (blocked) {
            OneKeyToSetAServer.LOGGER.debug("[Res] explosion at {} had protected blocks removed",
                    event.getExplosion().getPosition());
        }
    }

    /** Pistons moving blocks is a placement: a piston reaching into a protected domain is stopped. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onPiston(net.minecraftforge.event.level.PistonEvent.Pre event) {
        LevelAccessor accessor = event.getLevel();
        if (!(accessor instanceof Level level)) {
            return;
        }
        BlockPos head = event.getFaceOffsetPos();
        BlockPos beyond = head.relative(event.getDirection());
        if (allowed(null, level, event.getPos(), Domain.Flag.PLACE)
                && allowed(null, level, head, Domain.Flag.PLACE)
                && allowed(null, level, beyond, Domain.Flag.PLACE)) {
            return;
        }
        event.setCanceled(true);
    }

    /** Fluids turning into blocks (lava + water) may not create blocks inside a protected domain either. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        LevelAccessor accessor = event.getLevel();
        if (!(accessor instanceof Level level)) {
            return;
        }
        if (!allowed(null, level, event.getPos(), Domain.Flag.PLACE)) {
            event.setCanceled(true);
        }
    }

    /** Endermen, sheep eating grass and friends do not get to grief inside a protected claim. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onMobGriefing(net.minecraftforge.event.entity.EntityMobGriefingEvent event) {
        Entity entity = event.getEntity();
        if (entity == null) {
            return;
        }
        if (!allowed(null, entity.level(), entity.blockPosition(), Domain.Flag.MOB_GRIEFING)) {
            event.setCanceled(true);
        }
    }

    /**
     * PvP: a hit is refused when the victim or the attacker stands in a claim that forbids fighting.
     * Both sides are checked so nobody can hide inside a claim and shoot out of it either.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onAttack(net.minecraftforge.event.entity.player.AttackEntityEvent event) {
        if (!(event.getTarget() instanceof Player victim)) {
            return;
        }
        Player attacker = event.getEntity();
        if (attacker == null) {
            return;
        }
        if (!allowed(attacker, victim.level(), victim.blockPosition(), Domain.Flag.PVP)
                || !allowed(attacker, attacker.level(), attacker.blockPosition(), Domain.Flag.PVP)) {
            event.setCanceled(true);
            warn(attacker, victim.level(), victim.blockPosition(), "§c这里禁止玩家之间的战斗");
        }
    }

    // ------------------------------------------------------------------ interaction

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (DomainManager.isWand(event.getItemStack())) {
            // The claim wand is a golden hoe that never tills: right click only marks the second corner.
            handleWand(player, event, true);
            return;
        }
        boolean useItem = !event.getItemStack().isEmpty();
        if (!allowed(player, level, pos, Domain.Flag.INTERACT)
                || (useItem && !allowed(player, level, pos, Domain.Flag.USE_ITEM))) {
            event.setCanceled(true);
            warn(player, level, pos, "§c这里禁止交互或使用物品");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (DomainManager.isWand(event.getItemStack())) {
            handleWand(player, event, false);
            return;
        }
        if (allowed(player, level, pos, Domain.Flag.INTERACT)) {
            return;
        }
        event.setCanceled(true);
        warn(player, level, pos, "§c这里禁止交互");
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        BlockPos pos = player.blockPosition();
        if (DomainManager.isWand(event.getItemStack())) {
            event.setCanceled(true);
            return;
        }
        if (!allowed(player, level, pos, Domain.Flag.USE_ITEM)) {
            event.setCanceled(true);
            warn(player, level, pos, "§c这个领地禁止使用物品");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        Entity target = event.getTarget();
        if (!allowed(player, level, target.blockPosition(), Domain.Flag.INTERACT)) {
            event.setCanceled(true);
            warn(player, level, target.blockPosition(), "§c这里禁止交互");
        }
    }

    // ------------------------------------------------------------------ wand + messages

    private void handleWand(Player player, PlayerInteractEvent event, boolean second) {
        event.setCanceled(true);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (player.level().isClientSide()) {
            return;
        }
        DomainManager.Selection selection = DomainManager.mark(serverPlayer, second);
        BlockPos pos = second ? selection.second() : selection.first();
        String hint = selection.complete()
                ? "§a已选好，用 /res create <名字> 建立领地（" + DomainManager.preview(selection) + "）"
                : "§e再用§f" + (second ? "左键" : "右键") + "§e标记另一个对角点";
        serverPlayer.sendSystemMessage(Component.literal("§b领地法杖: " + (second ? "点2 " : "点1 ")
                + "§f" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " §7" + hint));
    }

    private static boolean allowed(Player player, Level level, BlockPos pos, Domain.Flag flag) {
        return DomainManager.allowed(player, level, pos, flag);
    }

    /** Tells the player why they were stopped, at most every 1.5 s. */
    private void warn(Player player, Level level, BlockPos pos, String message) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_WARN.get(player.getUUID());
        if (last != null && now - last < WARN_COOLDOWN_MILLIS) {
            return;
        }
        LAST_WARN.put(player.getUUID(), now);
        Domain domain = DomainManager.at(level, pos);
        String name = domain == null ? "" : "（" + domain.name + " / " + domain.ownerName + "）";
        serverPlayer.displayClientMessage(Component.literal(message + name), true);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // selection outline: 0.8 s by default, only for players who are actually marking something
            DomainManager.outline(player, now);
            if (server.getTickCount() % 5 != 0) {
                continue;
            }
            Long last = LAST_ANNOUNCE.get(player.getUUID());
            if (last != null && now - last < 250L) {
                continue;
            }
            LAST_ANNOUNCE.put(player.getUUID(), now);
            DomainManager.announceEntry(player);
        }
        if (LAST_WARN.size() > 256) {
            LAST_WARN.clear();
        }
    }

    /** Forgets cooldown state (server stopped). */
    public static void clear() {
        LAST_WARN.clear();
        LAST_ANNOUNCE.clear();
    }
}
