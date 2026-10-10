package com.nightsiege;

import com.nightsiege.net.Network;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class WaveManager {
    public static final String TAG = "nightsiege_zombie";
    public static final String MOUNT_TAG = "nightsiege_mount";
    public static final String PYRO_TAG = "nightsiege_pyro";

    private static final long NIGHT_START = 13000L;
    private static final long NIGHT_END = 23000L;

    public enum EndReason { VICTORY, DAWN, STOPPED }

    private static int lastSyncKey = Integer.MIN_VALUE;

    public static WaveData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(WaveData::load, WaveData::new, "nightsiege_waves");
    }

    /** Живые игроки в выживании/приключении (спектаторы не считаются). */
    public static List<ServerPlayer> targets(ServerLevel level) {
        List<ServerPlayer> l = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.isAlive() && !p.isSpectator()) l.add(p);
        }
        return l;
    }

    // ===================== ГЛАВНЫЙ ЦИКЛ =====================

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD) return;
        if (level.players().isEmpty()) return;

        WaveData d = data(level);
        long time = level.getDayTime();
        int day = (int) (time / 24000L) + 1;
        long tod = time % 24000L;
        boolean night = tod >= NIGHT_START && tod < NIGHT_END;
        long gt = level.getGameTime();
        int startDay = SiegeConfig.START_DAY.get();

        if (!d.active) {
            List<ServerPlayer> t = targets(level);
            if (day >= startDay && tod >= NIGHT_START && tod < NIGHT_END - 1000
                    && d.lastDay != day && !t.isEmpty()) {
                start(level, d, day, t.size(), day - startDay + 1);
            } else {
                dailyWarning(level, d, day, tod, startDay);
                if (gt % 100 == 0) sync(level, 0, 0, 0, 0, true);
            }
            return;
        }

        if (!night) { // наступило утро
            end(level, d, EndReason.DAWN);
            return;
        }
        if (gt % 10 != 0) return;

        List<ServerPlayer> t = targets(level);
        if (t.isEmpty()) return; // все мертвы/спектаторы -> пауза

        List<? extends Zombie> alive = aliveZombies(level);

        // постепенный спавн: порция каждые spawnIntervalTicks
        int interval = Math.max(10, SiegeConfig.SPAWN_INTERVAL.get() / 10 * 10);
        if (d.spawned < d.total && gt % interval == 0) {
            if (spawnBatch(level, d, t, alive)) {
                d.setDirty();
                alive = aliveZombies(level);
            }
        }

        // оптимизация: зомби, ушедшие далеко от всех игроков, переносятся поближе
        if (gt % 100 == 0) relocateFar(level, alive, t);

        int remaining = (d.total - d.spawned) + alive.size();
        if (d.spawned >= d.total && alive.isEmpty()) {
            end(level, d, EndReason.VICTORY);
            return;
        }
        sync(level, 1, d.wave, remaining, d.total, gt % 100 == 0);
    }

    private static List<? extends Zombie> aliveZombies(ServerLevel level) {
        return level.getEntities(EntityTypeTest.forClass(Zombie.class),
                z -> z.isAlive() && z.getTags().contains(TAG));
    }

    /** Каждому игроку по порции зомби, но не больше maxAlivePerPlayer рядом с ним. */
    private static boolean spawnBatch(ServerLevel level, WaveData d, List<ServerPlayer> targets,
                                      List<? extends Zombie> alive) {
        int batch = Math.min(3, 1 + d.wave / 6);
        int cap = SiegeConfig.MAX_ALIVE_PER_PLAYER.get();
        double near = SiegeConfig.MAX_SPAWN_DIST.get() + 40.0;
        double near2 = near * near;
        boolean any = false;

        for (ServerPlayer p : targets) {
            int around = 0;
            for (Zombie z : alive) {
                if (z.distanceToSqr(p) < near2) around++;
            }
            for (int i = 0; i < batch && d.spawned < d.total && around < cap; i++) {
                if (SiegeSpawner.spawnOne(level, p, d.wave)) {
                    d.spawned++;
                    around++;
                    any = true;
                }
            }
        }
        return any;
    }

    private static void relocateFar(ServerLevel level, List<? extends Zombie> alive, List<ServerPlayer> targets) {
        for (Zombie z : alive) {
            if (z.isPassenger() || z.isVehicle()) continue;
            ServerPlayer nearest = null;
            double best = Double.MAX_VALUE;
            for (ServerPlayer p : targets) {
                double dd = z.distanceToSqr(p);
                if (dd < best) {
                    best = dd;
                    nearest = p;
                }
            }
            if (nearest != null && best > 100.0 * 100.0) {
                BlockPos pos = SiegeSpawner.findPos(level, nearest, true);
                if (pos != null) {
                    z.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                    z.getNavigation().stop();
                }
            }
        }
    }

    // ===================== СТАРТ / КОНЕЦ =====================

    private static void start(ServerLevel level, WaveData d, int day, int playerCount, int wave) {
        d.active = true;
        d.lastDay = day;
        d.wave = wave;
        int base = Math.min(SiegeConfig.MAX_TOTAL.get(), 10 + 5 * wave);
        double mp = 1 + SiegeConfig.MP_SCALING.get() * (playerCount - 1);
        d.total = (int) Math.max(5, Math.round(base * SiegeConfig.count() * mp));
        d.spawned = 0;
        d.setDirty();

        Component title = Component.literal("ВОЛНА " + wave).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
        Component sub = Component.literal("Зомби идут! Защищайте базу").withStyle(ChatFormatting.GOLD);
        for (ServerPlayer p : level.players()) title(p, title, sub);
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("Волна " + wave + " начинается! Зомби: " + d.total)
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
        sync(level, 1, wave, d.total, d.total, true);
    }

    /** Принудительный запуск (команда /siege start). wave <= 0 -> по текущему дню. */
    public static boolean forceStart(ServerLevel level, int wave) {
        List<ServerPlayer> t = targets(level);
        if (t.isEmpty()) return false;
        WaveData d = data(level);
        if (d.active) end(level, d, EndReason.STOPPED);

        long time = level.getDayTime();
        int day = (int) (time / 24000L) + 1;
        long tod = time % 24000L;
        if (!(tod >= NIGHT_START && tod < NIGHT_END - 1000)) {
            level.setDayTime((day - 1) * 24000L + NIGHT_START + 500);
        }
        int w = wave > 0 ? wave : Math.max(1, day - SiegeConfig.START_DAY.get() + 1);
        start(level, d, day, t.size(), w);
        return true;
    }

    public static void stop(ServerLevel level) {
        WaveData d = data(level);
        if (d.active) end(level, d, EndReason.STOPPED);
    }

    private static void end(ServerLevel level, WaveData d, EndReason reason) {
        d.active = false;
        d.setDirty();

        List<Entity> remove = new ArrayList<>();
        for (Entity en : level.getAllEntities()) {
            if (en.getTags().contains(TAG) || en.getTags().contains(MOUNT_TAG)) remove.add(en);
        }
        remove.forEach(Entity::discard);

        Component msg = switch (reason) {
            case VICTORY -> Component.literal("Волна " + d.wave + " отбита!")
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
            case DAWN -> Component.literal("Рассвет. Волна " + d.wave + " пережита.")
                    .withStyle(ChatFormatting.YELLOW);
            case STOPPED -> Component.literal("Волна остановлена.").withStyle(ChatFormatting.GRAY);
        };
        level.getServer().getPlayerList().broadcastSystemMessage(msg, false);
        int state = reason == EndReason.VICTORY ? 2 : (reason == EndReason.DAWN ? 3 : 0);
        sync(level, state, d.wave, 0, d.total, true);
    }

    private static void sync(ServerLevel level, int state, int wave, int remaining, int total, boolean force) {
        int key = Objects.hash(state, wave, remaining, total);
        if (!force && key == lastSyncKey) return; // не шлём одинаковые пакеты
        lastSyncKey = key;
        Network.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension),
                new Network.SyncPacket(state, wave, remaining, total));
    }

    // ===================== ОБЪЯВЛЕНИЯ =====================

    static void title(ServerPlayer p, Component title, Component sub) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        p.connection.send(new ClientboundSetSubtitleTextPacket(sub));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    static String days(int n) {
        int m = n % 100, k = n % 10;
        String w = (m >= 11 && m <= 14) ? "дней" : (k == 1 ? "день" : (k >= 2 && k <= 4 ? "дня" : "дней"));
        return n + " " + w;
    }

    /** Напоминание в начале каждого дня до первой волны. */
    private static void dailyWarning(ServerLevel level, WaveData d, int day, long tod, int startDay) {
        if (!SiegeConfig.ANNOUNCE.get()) return;
        if (day >= 2 && day <= startDay && tod >= 60 && tod < 12000 && d.lastWarnDay != day) {
            d.lastWarnDay = day;
            d.setDirty();
            int n = startDay - day + 1;
            Component msg = n == 1
                    ? Component.literal("Сегодня ночью нападёт первая волна зомби! Готовьте оборону!")
                    .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                    : Component.literal("До нападения зомби: " + days(n) + ".").withStyle(ChatFormatting.GOLD);
            level.getServer().getPlayerList().broadcastSystemMessage(msg, false);
        }
    }

    /** Надпись при создании мира (первый вход) и напоминание остальным игрокам. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || !SiegeConfig.ANNOUNCE.get()) return;
        ServerLevel level = sp.server.overworld();
        WaveData d = data(level);
        int startDay = SiegeConfig.START_DAY.get();
        int day = (int) (level.getDayTime() / 24000L) + 1;
        if (day >= startDay) return;

        int n = startDay - day + 1;
        if (!d.announced) {
            d.announced = true;
            d.lastWarnDay = day;
            d.setDirty();
            title(sp,
                    Component.literal("Скоро нападение!").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                    Component.literal("Через " + days(n) + " начнутся ночные волны зомби").withStyle(ChatFormatting.GOLD));
        }
        sp.sendSystemMessage(Component.literal("Скоро нападение! Через " + days(n)
                + " начнутся ночные волны зомби. Стройте базу!").withStyle(ChatFormatting.RED));
    }

    // ===================== ПРОЧЕЕ =====================

    /** Во время волны спать нельзя (настраивается). */
    @SubscribeEvent
    public static void onSleep(PlayerSleepInBedEvent e) {
        Player p = e.getEntity();
        if (SiegeConfig.ALLOW_SLEEP.get()) return;
        if (p.level() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD && data(level).active) {
            e.setResult(Player.BedSleepingProblem.OTHER_PROBLEM);
            p.displayClientMessage(Component.literal("Нельзя спать во время волны!"), true);
        }
    }

    /** Зомби-поджигатель поджигает жертву. */
    @SubscribeEvent
    public static void onHurt(LivingHurtEvent e) {
        if (e.getSource().getEntity() instanceof Zombie z && z.getTags().contains(PYRO_TAG)) {
            e.getEntity().setSecondsOnFire(5);
        }
    }
}
