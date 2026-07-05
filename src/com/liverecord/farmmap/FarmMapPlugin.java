package com.liverecord.farmmap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * s2e-farm-pro のアリーナ（フロア）を読み取り、その周囲に「横に広がる立体的な農場マップ」を生成する補助プラグイン。
 *
 * <p>デザイン方針（v1.3.0 / #1「木の牧場」）:
 * <ul>
 *   <li>高さのある“壁”は作らない。装飾余白(margin-x / margin-z)帯へ平らに農場が広がる。</li>
 *   <li>X(横幅) と Z(奥行き) で余白を別々に設定でき、奥行きだけ長い非対称マップにできる。</li>
 *   <li>内側プレイフィールドに耕地(FARMLAND)の基礎床。作物は床の上に育つので非干渉。</li>
 *   <li>建物はプレイ面のすぐ外に集約し、広い余白は中央の道・遠景の木立で奥行きを演出。</li>
 * </ul>
 *
 * <p>大量設置でサーバーを固めないよう、設置は 1tickあたり {@code blocks-per-tick} 個に分割し、
 * 物理演算オフで連鎖更新を抑える。生成物は爆発・火・手壊し・ピストン・液体から保護し、
 * アリーナのサイズ/位置変化に自動追従する。シーンは座標から決定的に生成されるため、
 * 撤去(clear)は同じシーンを再計算して「置いたブロックのみ」元に戻せる。
 */
public final class FarmMapPlugin extends JavaPlugin implements TabExecutor, Listener {

    // ===== 設定 =====
    private String sourcePlugin;
    private String worldOverride;
    private String anchor;
    private int centerXOffset;
    private int centerZOffset;
    private double sizeScale;
    private int sizePadding;

    private int marginX;
    private int marginZ;
    private int floorYOffset;
    private boolean fillPlayfieldFloor;
    private boolean clearAboveFloor;

    private int blocksPerTick;
    private boolean applyPhysics;

    private boolean protectExplosion;
    private boolean protectFire;
    private boolean protectBreak;
    private boolean protectPiston;
    private boolean protectFluid;

    private boolean autoFollow;
    private int autoFollowInterval;
    private boolean debug;

    // ===== 現在の農場の状態（state.yml に永続化）=====
    private boolean active;
    private String mapWorld;
    private int mCx;
    private int mCz;
    private int mFloorY;
    private int mRadiusX;
    private int mRadiusZ;
    private int mMarginX;
    private int mMarginZ;
    private int oMinX;
    private int oMaxX;
    private int oMinZ;
    private int oMaxZ;
    private int sceneMaxY;
    private final Set<Material> sceneMaterials = new HashSet<>();

    private String lastArenaSig = null;
    private int followTaskId = -1;
    private BukkitRunnable activeTask = null;

    /** 読み取ったアリーナ（中心＋半径）。 */
    private static final class Arena {
        final World world;
        final int cx;
        final int cy;
        final int cz;
        final int radiusX;
        final int radiusZ;

        Arena(World world, int cx, int cy, int cz, int radiusX, int radiusZ) {
            this.world = world;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.radiusX = radiusX;
            this.radiusZ = radiusZ;
        }

        String signature() {
            return world.getName() + ":" + cx + "," + cy + "," + cz + ":" + radiusX + "x" + radiusZ;
        }
    }

    /** 設置対象の1ブロック。 */
    private static final class Plan {
        final int x;
        final int y;
        final int z;
        final Material mat;

        Plan(int x, int y, int z, Material mat) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.mat = mat;
        }
    }

    // ===================== ライフサイクル =====================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getResource("README.md") != null) {
            saveResource("README.md", true);
        }
        loadSettings();
        loadState();
        getCommand("farmmap").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        startFollowTask();
        getLogger().info("FarmMap 有効化。/farmmap <build|clear|level 1-5|reload|status>"
                + " / スタイル=木の牧場(#1) / 余白[横=" + marginX + " 奥=" + marginZ + "]"
                + " / 保護[爆発=" + protectExplosion + " 火=" + protectFire
                + " 破壊=" + protectBreak + " ピストン=" + protectPiston + " 液体=" + protectFluid + "]"
                + " / バッチ=" + blocksPerTick + "blocks/tick / 自動追従=" + autoFollow);
    }

    @Override
    public void onDisable() {
        stopFollowTask();
        if (activeTask != null) {
            activeTask.cancel();
            activeTask = null;
        }
    }

    private void loadSettings() {
        reloadConfig();
        sourcePlugin = getConfig().getString("source-plugin", "s2e-farm-pro");
        worldOverride = getConfig().getString("world", "");
        anchor = getConfig().getString("arena.anchor", "center").toLowerCase(Locale.ROOT);
        centerXOffset = getConfig().getInt("arena.center-x-offset", 0);
        centerZOffset = getConfig().getInt("arena.center-z-offset", 0);
        sizeScale = getConfig().getDouble("arena.size-scale", 1.0);
        sizePadding = getConfig().getInt("arena.size-padding", 0);

        int base = getConfig().getInt("map.margin", 20);
        marginX = Math.max(2, getConfig().getInt("map.margin-x", base));
        marginZ = Math.max(2, getConfig().getInt("map.margin-z", base));
        floorYOffset = getConfig().getInt("map.floor-y-offset", 0);
        fillPlayfieldFloor = getConfig().getBoolean("map.fill-playfield-floor", true);
        clearAboveFloor = getConfig().getBoolean("map.clear-above-floor", false);

        blocksPerTick = Math.max(50, getConfig().getInt("build.blocks-per-tick", 1800));
        applyPhysics = getConfig().getBoolean("build.apply-physics", false);

        protectExplosion = getConfig().getBoolean("protect.explosion", true);
        protectFire = getConfig().getBoolean("protect.fire", true);
        protectBreak = getConfig().getBoolean("protect.break", true);
        protectPiston = getConfig().getBoolean("protect.piston", true);
        protectFluid = getConfig().getBoolean("protect.fluid", true);

        autoFollow = getConfig().getBoolean("auto-follow.enabled", true);
        autoFollowInterval = Math.max(1, getConfig().getInt("auto-follow.interval-seconds", 3));
        debug = getConfig().getBoolean("debug", false);
    }

    // ===================== コマンド =====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("farmmap")) {
            return false;
        }
        if (!sender.hasPermission("farmmap.use")) {
            sender.sendMessage("§cこのコマンドを使う権限がありません。");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§e使い方: /farmmap <build|clear|level 1-5|reload|status>");
            return true;
        }
        String a = args[0].toLowerCase(Locale.ROOT);

        switch (a) {
            case "reload":
                loadSettings();
                startFollowTask();
                sender.sendMessage("§aconfig.yml を再読み込みしました。"
                        + "（余白 横=" + marginX + " 奥=" + marginZ
                        + " / バッチ=" + blocksPerTick + "blocks/tick / 自動追従=" + autoFollow + "）");
                return true;

            case "status":
                showStatus(sender);
                return true;

            case "clear":
            case "off":
            case "remove":
                if (!active) {
                    sender.sendMessage("§e現在、生成済みの農場はありません。");
                    return true;
                }
                startClear(sender);
                return true;

            case "level": {
                if (args.length < 2) {
                    sender.sendMessage("§e使い方: /farmmap level <1-5>（横・奥の余白を 20/24/28/32/36 に揃える）");
                    return true;
                }
                int lv;
                try {
                    lv = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cレベルは 1〜5 の数値で指定してください。");
                    return true;
                }
                if (lv < 1 || lv > 5) {
                    sender.sendMessage("§cレベルは 1〜5 で指定してください。");
                    return true;
                }
                int m = 16 + 4 * lv; // Lv1=20, Lv2=24, ... Lv5=36
                marginX = m;
                marginZ = m;
                getConfig().set("map.margin", m);
                getConfig().set("map.margin-x", m);
                getConfig().set("map.margin-z", m);
                saveConfig();
                sender.sendMessage("§a拡張レベル " + lv + " ＝ 横・奥の余白を " + m + " に揃えました。再生成します…");
                sender.sendMessage("§7※ 非対称（奥だけ長い等）にする場合は config の margin-x / margin-z を直接指定してください。");
                startBuild(sender);
                return true;
            }

            case "build":
                startBuild(sender);
                return true;

            default:
                sender.sendMessage("§c不明なサブコマンドです。/farmmap <build|clear|level 1-5|reload|status>");
                return true;
        }
    }

    private void showStatus(CommandSender sender) {
        sender.sendMessage("§e=== FarmMap 状態（スタイル: 木の牧場 #1）===");
        Arena arena = readArena();
        if (arena == null) {
            sender.sendMessage("§c" + sourcePlugin + " のフロア設定が読めません（plugins/" + sourcePlugin
                    + "/config.yml とワールドを確認）。");
        } else {
            sender.sendMessage("§7アリーナ中心: §f(" + arena.cx + ", " + arena.cy + ", " + arena.cz + ") "
                    + arena.world.getName());
            sender.sendMessage("§7プレイフィールド: §f" + (2 * arena.radiusX + 1) + "×" + (2 * arena.radiusZ + 1));
            sender.sendMessage("§7マップ外形: §f横" + (2 * (arena.radiusX + marginX) + 1)
                    + " × 奥" + (2 * (arena.radiusZ + marginZ) + 1)
                    + " §7(余白 横+" + marginX + " 奥+" + marginZ + ")");
        }
        sender.sendMessage("§7農場: §f" + (active ? "生成済み" : "未生成"));
        sender.sendMessage("§7保護: 爆発=" + protectExplosion + " 火=" + protectFire + " 破壊=" + protectBreak
                + " ピストン=" + protectPiston + " 液体=" + protectFluid);
        if (activeTask != null) {
            sender.sendMessage("§e※ 設置/撤去を実行中です。");
        }
    }

    // ===================== アリーナ読み取り =====================

    private Arena readArena() {
        File f = new File(getDataFolder().getParentFile(), sourcePlugin + "/config.yml");
        if (!f.exists()) {
            return null;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        if (!y.contains("arena.location.x")) {
            return null;
        }
        World world = resolveWorld(y.getString("arena.location.world_key"));
        if (world == null) {
            return null;
        }
        int cx = (int) Math.floor(y.getDouble("arena.location.x")) + centerXOffset;
        int cy = (int) Math.floor(y.getDouble("arena.location.y"));
        int cz = (int) Math.floor(y.getDouble("arena.location.z")) + centerZOffset;
        int rawX = Math.max(1, y.getInt("arena.sizeX", 15));
        int rawZ = Math.max(1, y.getInt("arena.sizeZ", 15));

        int radiusX;
        int radiusZ;
        if (anchor.equals("corner")) {
            int sizeX = Math.max(1, (int) Math.round(rawX * sizeScale) + sizePadding);
            int sizeZ = Math.max(1, (int) Math.round(rawZ * sizeScale) + sizePadding);
            radiusX = sizeX / 2;
            radiusZ = sizeZ / 2;
            cx = cx + radiusX;
            cz = cz + radiusZ;
        } else {
            radiusX = Math.max(0, (int) Math.round(rawX * sizeScale) + sizePadding);
            radiusZ = Math.max(0, (int) Math.round(rawZ * sizeScale) + sizePadding);
        }
        return new Arena(world, cx, cy, cz, radiusX, radiusZ);
    }

    private World resolveWorld(String worldKey) {
        if (worldOverride != null && !worldOverride.isEmpty()) {
            World w = Bukkit.getWorld(worldOverride);
            if (w != null) {
                return w;
            }
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL) {
                return w;
            }
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    // ===================== 生成（バッチ設置）=====================

    private void startBuild(CommandSender sender) {
        Arena arena = readArena();
        if (arena == null) {
            sender.sendMessage("§c" + sourcePlugin + " のフロア設定を読めませんでした"
                    + "（plugins/" + sourcePlugin + "/config.yml とワールドを確認）。");
            return;
        }
        if (activeTask != null) {
            sender.sendMessage("§e前の設置/撤去がまだ進行中です。完了までお待ちください。");
            return;
        }
        if (active) {
            List<Plan> clearPlan = computeStoredScene();
            runBatch(clearPlan, false, () -> beginBuild(arena, sender));
        } else {
            beginBuild(arena, sender);
        }
    }

    private void beginBuild(Arena arena, CommandSender sender) {
        mCx = arena.cx;
        mCz = arena.cz;
        mFloorY = arena.cy + floorYOffset;
        mRadiusX = arena.radiusX;
        mRadiusZ = arena.radiusZ;
        mMarginX = marginX;
        mMarginZ = marginZ;
        mapWorld = arena.world.getName();
        oMinX = mCx - mRadiusX - mMarginX;
        oMaxX = mCx + mRadiusX + mMarginX;
        oMinZ = mCz - mRadiusZ - mMarginZ;
        oMaxZ = mCz + mRadiusZ + mMarginZ;

        List<Plan> plan = computeScene(arena.world, mCx, mCz, mFloorY, mRadiusX, mRadiusZ, mMarginX, mMarginZ);

        sceneMaterials.clear();
        sceneMaxY = mFloorY;
        for (Plan p : plan) {
            if (p.mat != Material.AIR) {
                sceneMaterials.add(p.mat);
            }
            if (p.y > sceneMaxY) {
                sceneMaxY = p.y;
            }
        }
        active = true;
        lastArenaSig = arena.signature();
        saveState();

        sender.sendMessage("§a農場マップ（木の牧場）を生成します… §7(" + plan.size() + " ブロック予定 / "
                + blocksPerTick + " blocks/tick)");
        runBatch(plan, true, () -> {
            sender.sendMessage("§a農場マップの生成が完了しました。");
            sender.sendMessage("§7範囲: X " + oMinX + "〜" + oMaxX + " / Z " + oMinZ + "〜" + oMaxZ
                    + " / Y " + mFloorY + "〜" + sceneMaxY
                    + " §7(破壊耐性ON / " + (autoFollow ? "サイズ追従ON" : "追従OFF") + ")");
        });
    }

    // ===================== 撤去（バッチ）=====================

    private void startClear(CommandSender sender) {
        if (activeTask != null) {
            sender.sendMessage("§e前の設置/撤去がまだ進行中です。完了までお待ちください。");
            return;
        }
        List<Plan> plan = computeStoredScene();
        sender.sendMessage("§a農場を撤去します… §7(" + plan.size() + " ブロック対象)");
        runBatch(plan, false, () -> {
            active = false;
            saveState();
            sender.sendMessage("§a農場を撤去しました。");
        });
    }

    private List<Plan> computeStoredScene() {
        World w = mapWorld == null ? null : Bukkit.getWorld(mapWorld);
        if (w == null) {
            return new ArrayList<>();
        }
        return computeScene(w, mCx, mCz, mFloorY, mRadiusX, mRadiusZ, mMarginX, mMarginZ);
    }

    private void runBatch(List<Plan> plan, boolean place, Runnable onComplete) {
        World w = mapWorld == null ? null : Bukkit.getWorld(mapWorld);
        if (w == null || plan.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        final int total = plan.size();
        activeTask = new BukkitRunnable() {
            int index = 0;
            int changed = 0;
            int skipped = 0;
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                int end = Math.min(index + blocksPerTick, total);
                for (; index < end; index++) {
                    Plan p = plan.get(index);
                    Block b = w.getBlockAt(p.x, p.y, p.z);
                    if (place) {
                        if (b.getType() != p.mat) {
                            b.setBlockData(p.mat.createBlockData(), applyPhysics);
                            changed++;
                        } else {
                            skipped++;
                        }
                    } else {
                        if (p.mat != Material.AIR && b.getType() == p.mat) {
                            b.setBlockData(Material.AIR.createBlockData(), applyPhysics);
                            changed++;
                        } else {
                            skipped++;
                        }
                    }
                }
                if (index >= total) {
                    cancel();
                    activeTask = null;
                    if (debug) {
                        getLogger().info("[farmmap] " + (place ? "設置" : "撤去") + "完了: 変更=" + changed
                                + " スキップ=" + skipped + " 合計=" + total + " 所要=" + ticks + "tick");
                    }
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
            }
        };
        activeTask.runTaskTimer(this, 1L, 1L);
    }

    // ===================== シーン生成（#1 木の牧場・横/奥に広がる・立体的）=====================

    /**
     * アリーナ中心と半径・余白(X/Z 別)から、農場シーン全体のブロックプランを決定的に組み立てる。
     * 配置順は「土台 → 中央の道 → 柵 → 遠景の木立 → 建物」。後に置く建物が優先される。
     * 同じ引数なら常に同じ結果＝撤去時に再計算して「置いたものだけ」戻せる。
     */
    private List<Plan> computeScene(World w, int cx, int cz, int floorY, int rX, int rZ, int marginX, int marginZ) {
        final int minY = w.getMinHeight();
        final int maxY = w.getMaxHeight() - 1;
        final int oxMin = cx - rX - marginX;
        final int oxMax = cx + rX + marginX;
        final int ozMin = cz - rZ - marginZ;
        final int ozMax = cz + rZ + marginZ;
        final int pxMin = cx - rX;
        final int pxMax = cx + rX;
        final int pzMin = cz - rZ;
        final int pzMax = cz + rZ;
        final List<Plan> p = new ArrayList<>();

        // --- 1) 平らな土台（横・奥に広がる）---
        for (int x = oxMin; x <= oxMax; x++) {
            for (int z = ozMin; z <= ozMax; z++) {
                boolean field = x >= pxMin && x <= pxMax && z >= pzMin && z <= pzMax;
                if (field) {
                    if (fillPlayfieldFloor) {
                        add(p, x, floorY, z, Material.FARMLAND, minY, maxY);
                        if (clearAboveFloor) {
                            add(p, x, floorY + 1, z, Material.AIR, minY, maxY);
                        }
                    }
                } else {
                    boolean rim = (x == oxMin || x == oxMax || z == ozMin || z == ozMax);
                    add(p, x, floorY, z, rim ? Material.DIRT_PATH : Material.GRASS_BLOCK, minY, maxY);
                }
            }
        }

        // --- 2) 中央の十字路（奥行き・横方向。プレイ面の外だけ）---
        for (int z = ozMin; z <= ozMax; z++) {
            if (z < pzMin || z > pzMax) {
                add(p, cx, floorY, z, Material.DIRT_PATH, minY, maxY);
            }
        }
        for (int x = oxMin; x <= oxMax; x++) {
            if (x < pxMin || x > pxMax) {
                add(p, x, floorY, cz, Material.DIRT_PATH, minY, maxY);
            }
        }

        // --- 3) プレイフィールドを囲う低い柵（高さ1。要所に柱＋ランタン）---
        int fxMin = pxMin - 1;
        int fxMax = pxMax + 1;
        int fzMin = pzMin - 1;
        int fzMax = pzMax + 1;
        for (int x = fxMin; x <= fxMax; x++) {
            for (int z = fzMin; z <= fzMax; z++) {
                boolean edge = (x == fxMin || x == fxMax || z == fzMin || z == fzMax);
                if (!edge) {
                    continue;
                }
                boolean cornerCell = (x == fxMin || x == fxMax) && (z == fzMin || z == fzMax);
                if (cornerCell) {
                    add(p, x, floorY + 1, z, Material.OAK_LOG, minY, maxY);
                    add(p, x, floorY + 2, z, Material.OAK_LOG, minY, maxY);
                    add(p, x, floorY + 3, z, Material.LANTERN, minY, maxY);
                } else {
                    add(p, x, floorY + 1, z, Material.OAK_FENCE, minY, maxY);
                    if ((x + z) % 8 == 0) {
                        add(p, x, floorY + 2, z, Material.OAK_LOG, minY, maxY);
                        add(p, x, floorY + 3, z, Material.LANTERN, minY, maxY);
                    }
                }
            }
        }

        // --- 4) 遠景の木立（外周に沿って点在＝広い余白を縁取る）---
        int c = 3;
        addTree(p, oxMin + c, floorY, ozMin + c, minY, maxY);
        addTree(p, oxMax - c, floorY, ozMin + c, minY, maxY);
        addTree(p, oxMin + c, floorY, ozMax - c, minY, maxY);
        addTree(p, oxMax - c, floorY, ozMax - c, minY, maxY);
        for (int x = oxMin + 9; x <= oxMax - 9; x += 8) {
            addTree(p, x, floorY, ozMin + 2, minY, maxY);
            addTree(p, x, floorY, ozMax - 2, minY, maxY);
        }
        for (int z = ozMin + 9; z <= ozMax - 9; z += 8) {
            addTree(p, oxMin + 2, floorY, z, minY, maxY);
            addTree(p, oxMax - 2, floorY, z, minY, maxY);
        }

        // --- 5) 奥行き方向の遠景を埋める並木＋干し草（広い Z 余白を populate）---
        for (int z = pzMin - 12; z >= ozMin + 6; z -= 16) {
            addTree(p, cx - 16, floorY, z, minY, maxY);
            addTree(p, cx + 16, floorY, z, minY, maxY);
            addHayStack(p, cx - 6, floorY, z, minY, maxY);
            addHayStack(p, cx + 5, floorY, z, minY, maxY);
        }
        for (int z = pzMax + 12; z <= ozMax - 6; z += 16) {
            addTree(p, cx - 16, floorY, z, minY, maxY);
            addTree(p, cx + 16, floorY, z, minY, maxY);
            addHayStack(p, cx - 6, floorY, z, minY, maxY);
            addHayStack(p, cx + 5, floorY, z, minY, maxY);
        }
        // 横方向の余白も少し並木で。
        for (int x = pxMax + 10; x <= oxMax - 6; x += 14) {
            addTree(p, x, floorY, cz - 12, minY, maxY);
            addTree(p, x, floorY, cz + 12, minY, maxY);
        }
        for (int x = pxMin - 10; x >= oxMin + 6; x -= 14) {
            addTree(p, x, floorY, cz - 12, minY, maxY);
            addTree(p, x, floorY, cz + 12, minY, maxY);
        }

        // --- 6) 立体的な建物（プレイフィールドのすぐ外に集約）---
        // 北側（z 小さい側）: 大納屋＋高いサイロ。入口は南＝プレイ面向き。
        if (pzMin - 9 > ozMin && cx - 4 > oxMin && cx + 9 < oxMax) {
            addBigBarn(p, cx - 4, floorY, pzMin - 8, minY, maxY);
            addTallSilo(p, cx + 6, floorY, pzMin - 8, minY, maxY);
        }
        // 西側: 給水塔。
        if (pxMin - 7 > oxMin) {
            addWaterTower(p, pxMin - 7, floorY, cz - 2, minY, maxY);
        }
        // 東側: 風車（回転翼は西＝プレイ面向き）。
        if (pxMax + 6 < oxMax) {
            addWindmill(p, pxMax + 5, floorY, cz, minY, maxY);
        }
        // 南側: コテージ＋池＋干し草。
        if (pzMax + 8 < ozMax) {
            addCottage(p, cx - 9, floorY, pzMax + 3, minY, maxY);
            addPond(p, cx + 2, floorY, pzMax + 3, cx + 7, pzMax + 6, minY, maxY);
            addHayStack(p, cx - 2, floorY, pzMax + 5, minY, maxY);
        }
        // プレイフィールドのすぐ外にかかし。
        if (pxMax + 2 < oxMax) {
            addScarecrow(p, pxMax + 2, floorY, cz - 6, minY, maxY);
        }
        if (pxMin - 2 > oxMin) {
            addScarecrow(p, pxMin - 2, floorY, cz + 6, minY, maxY);
        }

        return p;
    }

    /** 大納屋（9×7・壁5段＋切妻屋根＝高さ約9）。木組みの柱・窓・南面の入口つき。 */
    private void addBigBarn(List<Plan> p, int x0, int floorY, int z0, int minY, int maxY) {
        int x1 = x0 + 8;
        int z1 = z0 + 6;
        Material wall = Material.SPRUCE_PLANKS;
        Material frame = Material.STRIPPED_SPRUCE_LOG;
        Material roof = Material.DARK_OAK_PLANKS;
        Material window = Material.GLASS_PANE;

        for (int y = floorY + 1; y <= floorY + 5; y++) {
            add(p, x0, y, z0, frame, minY, maxY);
            add(p, x1, y, z0, frame, minY, maxY);
            add(p, x0, y, z1, frame, minY, maxY);
            add(p, x1, y, z1, frame, minY, maxY);
        }
        for (int y = floorY + 1; y <= floorY + 5; y++) {
            Material m = (y == floorY + 5) ? frame : wall;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = (x == x0 || x == x1 || z == z0 || z == z1);
                    boolean cornerCol = (x == x0 || x == x1) && (z == z0 || z == z1);
                    if (!edge || cornerCol) {
                        continue;
                    }
                    add(p, x, y, z, m, minY, maxY);
                }
            }
        }
        for (int y = floorY + 1; y <= floorY + 2; y++) {
            add(p, x0 + 4, y, z1, Material.AIR, minY, maxY);
            add(p, x0 + 5, y, z1, Material.AIR, minY, maxY);
        }
        add(p, x0 + 2, floorY + 3, z0, window, minY, maxY);
        add(p, x0 + 6, floorY + 3, z0, window, minY, maxY);
        add(p, x0 + 2, floorY + 3, z1, window, minY, maxY);
        add(p, x0 + 6, floorY + 3, z1, window, minY, maxY);
        add(p, x0, floorY + 3, z0 + 3, window, minY, maxY);
        add(p, x1, floorY + 3, z0 + 3, window, minY, maxY);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                add(p, x, floorY + 6, z, roof, minY, maxY);
            }
            for (int x = x0 + 1; x <= x1 - 1; x++) {
                add(p, x, floorY + 7, z, roof, minY, maxY);
            }
            for (int x = x0 + 2; x <= x1 - 2; x++) {
                add(p, x, floorY + 8, z, roof, minY, maxY);
            }
            add(p, x0 + 4, floorY + 9, z, roof, minY, maxY);
        }
        add(p, x0 + 4, floorY + 7, z0, window, minY, maxY);
        add(p, x0 + 4, floorY + 7, z1, window, minY, maxY);
    }

    /** 高いサイロ（3×3・壁9段＋円錐屋根＝高さ約11）。 */
    private void addTallSilo(List<Plan> p, int x0, int floorY, int z0, int minY, int maxY) {
        int x1 = x0 + 2;
        int z1 = z0 + 2;
        for (int y = floorY + 1; y <= floorY + 9; y++) {
            Material band = (y % 3 == 0) ? Material.WHITE_CONCRETE : Material.SMOOTH_QUARTZ;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = (x == x0 || x == x1 || z == z0 || z == z1);
                    if (edge) {
                        add(p, x, y, z, band, minY, maxY);
                    }
                }
            }
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                add(p, x, floorY + 10, z, Material.WHITE_CONCRETE, minY, maxY);
            }
        }
        add(p, x0 + 1, floorY + 11, z0 + 1, Material.WHITE_CONCRETE, minY, maxY);
    }

    /** 給水塔（4本脚＋上部タンク＝高さ約8）。 */
    private void addWaterTower(List<Plan> p, int x0, int floorY, int z0, int minY, int maxY) {
        int x1 = x0 + 3;
        int z1 = z0 + 3;
        for (int y = floorY + 1; y <= floorY + 4; y++) {
            add(p, x0, y, z0, Material.OAK_LOG, minY, maxY);
            add(p, x1, y, z0, Material.OAK_LOG, minY, maxY);
            add(p, x0, y, z1, Material.OAK_LOG, minY, maxY);
            add(p, x1, y, z1, Material.OAK_LOG, minY, maxY);
        }
        for (int y = floorY + 5; y <= floorY + 7; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = (x == x0 || x == x1 || z == z0 || z == z1);
                    if (edge) {
                        add(p, x, y, z, Material.SPRUCE_PLANKS, minY, maxY);
                    }
                }
            }
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                add(p, x, floorY + 8, z, Material.DARK_OAK_PLANKS, minY, maxY);
            }
        }
    }

    /** 風車（石塔＋円錐屋根＋西向きの回転翼＝高さ約9）。 */
    private void addWindmill(List<Plan> p, int hubX, int floorY, int hubZ, int minY, int maxY) {
        int x0 = hubX - 1;
        int x1 = hubX + 1;
        int z0 = hubZ - 1;
        int z1 = hubZ + 1;
        for (int y = floorY + 1; y <= floorY + 7; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = (x == x0 || x == x1 || z == z0 || z == z1);
                    if (edge) {
                        add(p, x, y, z, Material.COBBLESTONE, minY, maxY);
                    }
                }
            }
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                add(p, x, floorY + 8, z, Material.DARK_OAK_PLANKS, minY, maxY);
            }
        }
        add(p, hubX, floorY + 9, hubZ, Material.DARK_OAK_PLANKS, minY, maxY);
        int bx = x0 - 1;
        int by = floorY + 5;
        add(p, bx, by, hubZ, Material.OAK_LOG, minY, maxY);
        for (int i = 1; i <= 3; i++) {
            add(p, bx, by + i, hubZ, Material.OAK_PLANKS, minY, maxY);
            add(p, bx, by - i, hubZ, Material.OAK_PLANKS, minY, maxY);
            add(p, bx, by, hubZ + i, Material.OAK_PLANKS, minY, maxY);
            add(p, bx, by, hubZ - i, Material.OAK_PLANKS, minY, maxY);
        }
    }

    /** コテージ（5×5・壁3段＋切妻屋根＋煙突＝高さ約6）。 */
    private void addCottage(List<Plan> p, int x0, int floorY, int z0, int minY, int maxY) {
        int x1 = x0 + 4;
        int z1 = z0 + 4;
        for (int y = floorY + 1; y <= floorY + 3; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = (x == x0 || x == x1 || z == z0 || z == z1);
                    boolean cornerCol = (x == x0 || x == x1) && (z == z0 || z == z1);
                    if (!edge) {
                        continue;
                    }
                    add(p, x, y, z, cornerCol ? Material.STRIPPED_OAK_LOG : Material.OAK_PLANKS, minY, maxY);
                }
            }
        }
        add(p, x0 + 2, floorY + 1, z0, Material.AIR, minY, maxY);
        add(p, x0 + 2, floorY + 2, z0, Material.AIR, minY, maxY);
        add(p, x0 + 1, floorY + 2, z1, Material.GLASS_PANE, minY, maxY);
        add(p, x0 + 3, floorY + 2, z1, Material.GLASS_PANE, minY, maxY);
        add(p, x0, floorY + 2, z0 + 2, Material.GLASS_PANE, minY, maxY);
        add(p, x1, floorY + 2, z0 + 2, Material.GLASS_PANE, minY, maxY);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                add(p, x, floorY + 4, z, Material.DARK_OAK_PLANKS, minY, maxY);
            }
            for (int x = x0 + 1; x <= x1 - 1; x++) {
                add(p, x, floorY + 5, z, Material.DARK_OAK_PLANKS, minY, maxY);
            }
            add(p, x0 + 2, floorY + 6, z, Material.DARK_OAK_PLANKS, minY, maxY);
        }
        for (int y = floorY + 1; y <= floorY + 6; y++) {
            add(p, x0 + 1, y, z0 + 1, Material.BRICKS, minY, maxY);
        }
    }

    /** 小さな池（水面＝floorY）。 */
    private void addPond(List<Plan> p, int x0, int floorY, int z0, int x1, int z1, int minY, int maxY) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                add(p, x, floorY, z, Material.WATER, minY, maxY);
            }
        }
    }

    /** 干し草の山（2×2×2）。 */
    private void addHayStack(List<Plan> p, int x0, int floorY, int z0, int minY, int maxY) {
        for (int y = floorY + 1; y <= floorY + 2; y++) {
            for (int x = x0; x <= x0 + 1; x++) {
                for (int z = z0; z <= z0 + 1; z++) {
                    add(p, x, y, z, Material.HAY_BLOCK, minY, maxY);
                }
            }
        }
    }

    /** かかし（柱2段＋干し草の腕＋カボチャの頭）。 */
    private void addScarecrow(List<Plan> p, int x, int floorY, int z, int minY, int maxY) {
        add(p, x, floorY + 1, z, Material.OAK_FENCE, minY, maxY);
        add(p, x, floorY + 2, z, Material.HAY_BLOCK, minY, maxY);
        add(p, x - 1, floorY + 2, z, Material.HAY_BLOCK, minY, maxY);
        add(p, x + 1, floorY + 2, z, Material.HAY_BLOCK, minY, maxY);
        add(p, x, floorY + 3, z, Material.CARVED_PUMPKIN, minY, maxY);
    }

    /** 背景の木（幹4段＋葉のかたまり＝高さ約7）。 */
    private void addTree(List<Plan> p, int x, int floorY, int z, int minY, int maxY) {
        for (int y = floorY + 1; y <= floorY + 4; y++) {
            add(p, x, y, z, Material.OAK_LOG, minY, maxY);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                add(p, x + dx, floorY + 4, z + dz, Material.OAK_LEAVES, minY, maxY);
                add(p, x + dx, floorY + 5, z + dz, Material.OAK_LEAVES, minY, maxY);
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                add(p, x + dx, floorY + 6, z + dz, Material.OAK_LEAVES, minY, maxY);
            }
        }
        add(p, x, floorY + 7, z, Material.OAK_LEAVES, minY, maxY);
    }

    private void add(List<Plan> p, int x, int y, int z, Material m, int minY, int maxY) {
        if (y < minY || y > maxY) {
            return;
        }
        p.add(new Plan(x, y, z, m));
    }

    // ===================== 破壊保護 =====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (protectExplosion && active) {
            e.blockList().removeIf(this::isOurBlock);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (protectExplosion && active) {
            e.blockList().removeIf(this::isOurBlock);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent e) {
        if (protectFire && active && isOurBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent e) {
        if (protectFire && active && touchesOurs(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent e) {
        if (protectFire && active && e.getSource().getType() == Material.FIRE && touchesOurs(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (protectBreak && active && isOurBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (protectBreak && active && isOurBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (protectPiston && active) {
            for (Block b : e.getBlocks()) {
                if (isOurBlock(b)) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (protectPiston && active) {
            for (Block b : e.getBlocks()) {
                if (isOurBlock(b)) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent e) {
        if (protectFluid && active && isOurBlock(e.getToBlock())) {
            e.setCancelled(true);
        }
    }

    /** その座標が農場ゾーン内＆我々の素材なら true。 */
    private boolean isOurBlock(Block b) {
        if (!active || mapWorld == null || !mapWorld.equals(b.getWorld().getName())) {
            return false;
        }
        int x = b.getX();
        int y = b.getY();
        int z = b.getZ();
        if (x < oMinX || x > oMaxX || z < oMinZ || z > oMaxZ || y < mFloorY || y > sceneMaxY) {
            return false;
        }
        return sceneMaterials.contains(b.getType());
    }

    /** 対象ブロック自身か6近傍が我々のブロックなら true（火を周囲で止める）。 */
    private boolean touchesOurs(Block b) {
        if (isOurBlock(b)) {
            return true;
        }
        World w = b.getWorld();
        int x = b.getX();
        int y = b.getY();
        int z = b.getZ();
        return isOurBlock(w.getBlockAt(x + 1, y, z)) || isOurBlock(w.getBlockAt(x - 1, y, z))
                || isOurBlock(w.getBlockAt(x, y, z + 1)) || isOurBlock(w.getBlockAt(x, y, z - 1))
                || isOurBlock(w.getBlockAt(x, y + 1, z)) || isOurBlock(w.getBlockAt(x, y - 1, z));
    }

    // ===================== 自動追従 =====================

    private void startFollowTask() {
        stopFollowTask();
        if (!autoFollow) {
            return;
        }
        long period = autoFollowInterval * 20L;
        followTaskId = Bukkit.getScheduler().runTaskTimer(this, this::followTick, period, period).getTaskId();
    }

    private void stopFollowTask() {
        if (followTaskId != -1) {
            Bukkit.getScheduler().cancelTask(followTaskId);
            followTaskId = -1;
        }
    }

    private void followTick() {
        if (activeTask != null) {
            return;
        }
        Arena arena = readArena();
        if (arena == null) {
            return;
        }
        String sig = arena.signature();
        boolean changed = !sig.equals(lastArenaSig);
        lastArenaSig = sig;
        if (changed && active) {
            getLogger().info("[farmmap] アリーナ変化を検知 → 農場を作り直します。");
            startBuild(Bukkit.getConsoleSender());
        }
    }

    // ===================== 状態の永続化 =====================

    private File stateFile() {
        return new File(getDataFolder(), "state.yml");
    }

    private void saveState() {
        YamlConfiguration s = new YamlConfiguration();
        s.set("active", active);
        s.set("world", mapWorld);
        s.set("cx", mCx);
        s.set("cz", mCz);
        s.set("floor-y", mFloorY);
        s.set("radius-x", mRadiusX);
        s.set("radius-z", mRadiusZ);
        s.set("margin-x", mMarginX);
        s.set("margin-z", mMarginZ);
        s.set("o-min-x", oMinX);
        s.set("o-max-x", oMaxX);
        s.set("o-min-z", oMinZ);
        s.set("o-max-z", oMaxZ);
        s.set("scene-max-y", sceneMaxY);
        List<String> mats = new ArrayList<>();
        for (Material m : sceneMaterials) {
            mats.add(m.name());
        }
        s.set("materials", mats);
        try {
            s.save(stateFile());
        } catch (Exception e) {
            getLogger().warning("state.yml の保存に失敗: " + e);
        }
    }

    private void loadState() {
        File f = stateFile();
        if (!f.exists()) {
            return;
        }
        YamlConfiguration s = YamlConfiguration.loadConfiguration(f);
        active = s.getBoolean("active", false);
        mapWorld = s.getString("world");
        mCx = s.getInt("cx");
        mCz = s.getInt("cz");
        mFloorY = s.getInt("floor-y");
        mRadiusX = s.getInt("radius-x");
        mRadiusZ = s.getInt("radius-z");
        mMarginX = s.getInt("margin-x", s.getInt("margin", 20));
        mMarginZ = s.getInt("margin-z", s.getInt("margin", 20));
        oMinX = s.getInt("o-min-x");
        oMaxX = s.getInt("o-max-x");
        oMinZ = s.getInt("o-min-z");
        oMaxZ = s.getInt("o-max-z");
        sceneMaxY = s.getInt("scene-max-y", mFloorY);
        sceneMaterials.clear();
        for (String name : s.getStringList("materials")) {
            Material m = Material.matchMaterial(name);
            if (m != null) {
                sceneMaterials.add(m);
            }
        }
    }

    // ===================== タブ補完 =====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (String o : new String[] {"build", "clear", "level", "reload", "status"}) {
                if (o.startsWith(pre)) {
                    out.add(o);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("level")) {
            for (String o : new String[] {"1", "2", "3", "4", "5"}) {
                if (o.startsWith(args[1])) {
                    out.add(o);
                }
            }
        }
        return out;
    }
}
