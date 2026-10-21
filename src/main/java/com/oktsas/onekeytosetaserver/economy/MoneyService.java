package com.oktsas.onekeytosetaserver.economy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The mod's own currency: {@code $}, two decimals, no Vault (there is no such thing on Forge).
 *
 * <p>Balances live in a {@link SavedData} attached to the overworld, i.e. inside the world save, written by
 * vanilla's normal save cycle. Money is handled as {@link BigDecimal} scaled to two decimals so repeated
 * additions cannot drift the way raw doubles do.</p>
 */
public final class MoneyService {
    private static final String DATA_NAME = "oktsas_money";

    private MoneyService() {
    }

    public static final class Data extends SavedData {
        private final Map<UUID, BigDecimal> balances = new HashMap<>();

        public static Data load(CompoundTag tag) {
            Data data = new Data();
            ListTag list = tag.getList("balances", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                try {
                    data.balances.put(UUID.fromString(entry.getString("id")), scale(entry.getDouble("amount")));
                } catch (Throwable ignored) {
                    // skip malformed entry
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            balances.forEach((id, amount) -> {
                CompoundTag entry = new CompoundTag();
                entry.putString("id", id.toString());
                entry.putDouble("amount", amount.doubleValue());
                list.add(entry);
            });
            tag.put("balances", list);
            return tag;
        }
    }

    private static Data data(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(Data::load, Data::new, DATA_NAME);
    }

    public static double get(MinecraftServer server, UUID player) {
        return data(server).balances.getOrDefault(player, BigDecimal.ZERO).doubleValue();
    }

    public static void set(MinecraftServer server, UUID player, double amount) {
        Data data = data(server);
        data.balances.put(player, scale(amount));
        data.setDirty();
    }

    public static void deposit(MinecraftServer server, UUID player, double amount) {
        if (amount == 0.0D) {
            return;
        }
        Data data = data(server);
        data.balances.merge(player, scale(amount), BigDecimal::add);
        data.setDirty();
    }

    /** @return false when the player cannot afford it; nothing is taken in that case. */
    public static boolean withdraw(MinecraftServer server, UUID player, double amount) {
        Data data = data(server);
        BigDecimal current = data.balances.getOrDefault(player, BigDecimal.ZERO);
        BigDecimal cost = scale(amount);
        if (current.compareTo(cost) < 0) {
            return false;
        }
        BigDecimal left = current.subtract(cost);
        if (left.signum() == 0) {
            data.balances.remove(player);
        } else {
            data.balances.put(player, left);
        }
        data.setDirty();
        return true;
    }

    public static boolean transfer(MinecraftServer server, UUID from, UUID to, double amount) {
        if (amount <= 0.0D || !withdraw(server, from, amount)) {
            return false;
        }
        deposit(server, to, amount);
        return true;
    }

    public static String format(double amount) {
        return "$" + String.format(Locale.ROOT, "%,.2f", scale(amount).doubleValue());
    }

    private static BigDecimal scale(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
    }
}
