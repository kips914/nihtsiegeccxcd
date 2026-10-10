package com.nightsiege;

import com.nightsiege.ai.ArcherGoal;
import com.nightsiege.ai.BreakBlockGoal;
import com.nightsiege.ai.LeapGoal;
import com.nightsiege.ai.SiegeMoveGoal;
import com.nightsiege.ai.TntGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.ZombieHorse;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;

/** Поиск места спавна и создание всех видов зомби. */
public class SiegeSpawner {

    public enum Kind { NORMAL, TNT, ARCHER, RIDER, RUNNER, DIGGER, HUSK, LEAPER, TANK, PYRO }

    private static final Item[][] ARMOR = {
            {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS},
            {Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS},
            {Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
            {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
            {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS},
            {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS}
    };
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static double angleCursor = 0;
    private static int failStreak = 0;

    // ================== ПОЗИЦИЯ ==================

    /** Спавнит одного зомби вокруг игрока. false = подходящее место не найдено. */
    public static boolean spawnOne(ServerLevel level, ServerPlayer p, int wave) {
        BlockPos pos = findPos(level, p, failStreak >= 5);
        if (pos == null) {
            failStreak++;
            return false;
        }
        failStreak = 0;
        spawnKind(level, pos, pick(wave, level.random), wave);
        return true;
    }

    /**
     * Ищет точку по кругу вокруг игрока (угол каждый раз сдвигается => равномерно со всех сторон),
     * на любой высоте рядом с игроком (поверхность, пещеры, подвалы), по возможности в тёмном месте.
     */
    public static BlockPos findPos(ServerLevel level, ServerPlayer p, boolean relaxDark) {
        RandomSource r = level.random;
        int min = SiegeConfig.MIN_SPAWN_DIST.get();
        int max = Math.max(min + 4, SiegeConfig.MAX_SPAWN_DIST.get());
        boolean dark = SiegeConfig.REQUIRE_DARK.get() && !relaxDark;
        int light = SiegeConfig.MAX_LIGHT.get();
        int py = p.blockPosition().getY();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int t = 0; t < 14; t++) {
            angleCursor += 2.399963 + (r.nextDouble() - 0.5) * 0.6; // золотой угол
            double dist = min + r.nextDouble() * (max - min);
            int x = Mth.floor(p.getX() + Math.cos(angleCursor) * dist);
            int z = Mth.floor(p.getZ() + Math.sin(angleCursor) * dist);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;

            // ищем пол рядом по высоте с игроком: 0, +1, -1, +2, -2 ... до +-8
            for (int k = 0; k <= 16; k++) {
                int off = ((k + 1) / 2) * ((k & 1) == 1 ? 1 : -1);
                m.set(x, py + off, z);
                if (isGoodSpot(level, m) && (!dark || level.getMaxLocalRawBrightness(m) <= light)) {
                    return m.immutable();
                }
            }
            // запасной вариант: поверхность
            int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(sy - py) <= 24) {
                m.set(x, sy, z);
                if (isGoodSpot(level, m) && (!dark || level.getMaxLocalRawBrightness(m) <= light)) {
                    return m.immutable();
                }
            }
        }
        return null;
    }

    private static boolean isGoodSpot(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockPos above = pos.above();
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(above);
        BlockState floor = level.getBlockState(below);
        return feet.getCollisionShape(level, pos).isEmpty() && feet.getFluidState().isEmpty()
                && head.getCollisionShape(level, above).isEmpty() && head.getFluidState().isEmpty()
                && floor.isFaceSturdy(level, below, Direction.UP)
                && !floor.is(BlockTags.LEAVES) && floor.getFluidState().isEmpty();
    }

    // ================== ВЫБОР ВИДА ==================

    public static Kind pick(int wave, RandomSource r) {
        double[] w = new double[Kind.values().length];
        w[Kind.TNT.ordinal()] = wave >= 2 ? Math.min(0.20, 0.06 + 0.02 * wave) : 0;
        w[Kind.ARCHER.ordinal()] = wave >= 2 ? Math.min(0.22, 0.08 + 0.02 * wave) : 0;
        w[Kind.RUNNER.ordinal()] = wave >= 2 ? Math.min(0.18, 0.08 + 0.01 * wave) : 0;
        w[Kind.RIDER.ordinal()] = wave >= 3 ? Math.min(0.15, 0.03 + 0.015 * wave) : 0;
        w[Kind.DIGGER.ordinal()] = wave >= 3 ? Math.min(0.12, 0.05 + 0.01 * wave) : 0;
        w[Kind.HUSK.ordinal()] = wave >= 3 ? Math.min(0.15, 0.06 + 0.01 * wave) : 0;
        w[Kind.LEAPER.ordinal()] = wave >= 4 ? Math.min(0.12, 0.05 + 0.01 * wave) : 0;
        w[Kind.TANK.ordinal()] = wave >= 4 ? Math.min(0.10, 0.03 + 0.01 * wave) : 0;
        w[Kind.PYRO.ordinal()] = wave >= 5 ? Math.min(0.10, 0.03 + 0.01 * wave) : 0;

        double sum = 0;
        for (double v : w) sum += v;
        if (sum <= 0) return Kind.NORMAL;
        double f = Math.min(SiegeConfig.special(), 0.85 / sum); // особые не более 85% волны
        double roll = r.nextDouble();
        double acc = 0;
        for (Kind k : Kind.values()) {
            acc += w[k.ordinal()] * f;
            if (roll < acc) return k;
        }
        return Kind.NORMAL;
    }

    // ================== СОЗДАНИЕ ==================

    private static void spawnKind(ServerLevel level, BlockPos pos, Kind kind, int wave) {
        RandomSource r = level.random;
        Zombie z = (kind == Kind.HUSK ? EntityType.HUSK : EntityType.ZOMBIE).create(level);
        if (z == null) return;
        z.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, r.nextFloat() * 360f, 0f);
        prepare(z, wave, kind == Kind.DIGGER ? 2.5 : 1.0);

        boolean canWearArmor = false;
        boolean fullArmor = false;
        boolean mounted = false;

        switch (kind) {
            case TNT -> {
                z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TNT));
                z.setDropChance(EquipmentSlot.HEAD, 0f);
                setSpeed(z, 0.27);
                z.goalSelector.addGoal(1, new TntGoal(z, 2.5f + Math.min(2.0f, wave * 0.15f)));
                z.setCustomName(Component.literal("Зомби-подрывник"));
            }
            case ARCHER -> {
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                z.setDropChance(EquipmentSlot.MAINHAND, 0.05f);
                z.goalSelector.addGoal(1, new ArcherGoal(z, (2.0 + Math.min(3.0, wave * 0.2)) * SiegeConfig.damage()));
                z.setCustomName(Component.literal("Зомби-лучник"));
                canWearArmor = true;
            }
            case RIDER -> {
                z.setCustomName(Component.literal("Зомби-всадник"));
                canWearArmor = true;
                mounted = true;
            }
            case RUNNER -> {
                mulAttr(z, Attributes.MOVEMENT_SPEED, 1.4, 0.40);
                mulAttr(z, Attributes.MAX_HEALTH, 0.6, 1000);
                z.setHealth(z.getMaxHealth());
                z.setCustomName(Component.literal("Зомби-бегун"));
            }
            case DIGGER -> {
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(wave >= 8 ? Items.DIAMOND_PICKAXE : Items.IRON_PICKAXE));
                z.setDropChance(EquipmentSlot.MAINHAND, 0.05f);
                z.setCustomName(Component.literal("Зомби-шахтёр"));
                canWearArmor = true;
            }
            case HUSK -> {
                z.setCustomName(Component.literal("Зомби-пустынник"));
                canWearArmor = true;
            }
            case LEAPER -> {
                mulAttr(z, Attributes.MOVEMENT_SPEED, 1.1, 0.36);
                z.goalSelector.addGoal(2, new LeapGoal(z));
                z.setCustomName(Component.literal("Зомби-прыгун"));
            }
            case TANK -> {
                mulAttr(z, Attributes.MAX_HEALTH, 3.0, 1000);
                z.setHealth(z.getMaxHealth());
                mulAttr(z, Attributes.MOVEMENT_SPEED, 0.8, 1.0);
                mulAttr(z, Attributes.ATTACK_DAMAGE, 1.3, 20);
                z.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(0.8);
                z.setCustomName(Component.literal("Зомби-танк"));
                fullArmor = true;
            }
            case PYRO -> {
                z.addTag(WaveManager.PYRO_TAG);
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BLAZE_ROD));
                z.setDropChance(EquipmentSlot.MAINHAND, 0.05f);
                z.setCustomName(Component.literal("Зомби-поджигатель"));
            }
            default -> {
                z.setCustomName(Component.literal("Зомби"));
                canWearArmor = true;
            }
        }

        if (fullArmor) {
            armor(z, wave, r, true);
        } else if (canWearArmor && r.nextDouble() < Math.min(0.9, 0.15 + 0.08 * wave)) {
            armor(z, wave, r, false);
            if (kind != Kind.ARCHER && kind != Kind.DIGGER && wave >= 4 && r.nextFloat() < 0.4f) {
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(
                        wave >= 9 ? Items.DIAMOND_SWORD : (wave >= 6 ? Items.IRON_SWORD : Items.STONE_SWORD)));
                z.setDropChance(EquipmentSlot.MAINHAND, 0.05f);
            }
        }

        if (mounted) {
            ZombieHorse horse = EntityType.ZOMBIE_HORSE.create(level);
            if (horse != null) {
                horse.moveTo(z.getX(), z.getY(), z.getZ(), z.getYRot(), 0f);
                horse.addTag(WaveManager.MOUNT_TAG);
                horse.setPersistenceRequired();
                horse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.33 * SiegeConfig.speed());
                horse.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64);
                horse.goalSelector.getAvailableGoals().clear();
                horse.goalSelector.addGoal(1, new BreakBlockGoal(horse, 1.0));
                horse.goalSelector.addGoal(2, new SiegeMoveGoal(horse, 1.4, true));
                level.addFreshEntity(horse);
                level.addFreshEntity(z);
                z.startRiding(horse, true);
                return;
            }
        }
        level.addFreshEntity(z);
    }

    /** Общие параметры всех зомби волны: рост характеристик с номером волны + настройки сложности. */
    private static void prepare(Zombie z, int wave, double breakFactor) {
        z.addTag(WaveManager.TAG);
        z.setPersistenceRequired();
        z.setCanPickUpLoot(false);
        z.setBaby(false);

        double hp = Math.min(2.5, 1 + 0.07 * (wave - 1)) * SiegeConfig.health();
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20 * hp);
        z.setHealth(z.getMaxHealth());
        z.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(Math.min(8, 3 + 0.25 * (wave - 1)) * SiegeConfig.damage());
        z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(Math.min(0.30, 0.23 + 0.004 * wave) * SiegeConfig.speed());
        z.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64);
        z.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE).setBaseValue(0);
        // естественная броня растёт с каждой волной
        z.getAttribute(Attributes.ARMOR).setBaseValue(2 + Math.min(12, 0.6 * wave));

        z.goalSelector.addGoal(1, new BreakBlockGoal(z, breakFactor));
        z.goalSelector.addGoal(3, new SiegeMoveGoal(z, 1.0, false));
    }

    private static void setSpeed(Zombie z, double base) {
        z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(base * SiegeConfig.speed());
    }

    private static void mulAttr(Zombie z, Attribute attr, double mul, double cap) {
        AttributeInstance a = z.getAttribute(attr);
        if (a != null) a.setBaseValue(Math.min(cap, a.getBaseValue() * mul));
    }

    /**
     * Броня становится сильнее с каждой волной:
     *  - материал: кожа -> золото -> кольчуга -> железо -> алмаз -> незерит (ещё +бонус из конфига)
     *  - больше надетых частей
     *  - чары "Защита" с 3 волны, уровень растёт
     *  - плюс естественная броня моба (см. prepare)
     */
    private static void armor(Zombie z, int wave, RandomSource r, boolean full) {
        int tierMax = Math.max(0, Math.min(5, wave * 2 / 3 + SiegeConfig.ARMOR_TIER_BONUS.get()));
        if (full) tierMax = Math.min(5, Math.max(3, tierMax));
        double pieceChance = full ? 1.0 : Math.min(1.0, 0.45 + 0.06 * wave);
        int prot = Math.min(4, wave / 3);
        boolean any = false;

        for (int i = 0; i < 4; i++) {
            if (r.nextDouble() < pieceChance) {
                int tier = Math.max(0, tierMax - (r.nextInt(3) == 0 ? 1 : 0));
                ItemStack st = new ItemStack(ARMOR[tier][i]);
                if (prot > 0 && r.nextFloat() < 0.6f) {
                    st.enchant(Enchantments.ALL_DAMAGE_PROTECTION, Math.max(1, prot - r.nextInt(2)));
                }
                z.setItemSlot(SLOTS[i], st);
                z.setDropChance(SLOTS[i], 0.05f);
                any = true;
            }
        }
        if (!any) {
            z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ARMOR[tierMax][0]));
            z.setDropChance(EquipmentSlot.HEAD, 0.05f);
        }
    }
}
