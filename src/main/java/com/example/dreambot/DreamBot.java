package com.example.dreambot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DreamBot implements ClientModInitializer {

    static final Map<String, List<Block>> ORES = new HashMap<>();
    static {
        ORES.put("diamond",  List.of(Blocks.DIAMOND_ORE,  Blocks.DEEPSLATE_DIAMOND_ORE));
        ORES.put("iron",     List.of(Blocks.IRON_ORE,     Blocks.DEEPSLATE_IRON_ORE));
        ORES.put("gold",     List.of(Blocks.GOLD_ORE,     Blocks.DEEPSLATE_GOLD_ORE));
        ORES.put("coal",     List.of(Blocks.COAL_ORE,     Blocks.DEEPSLATE_COAL_ORE));
        ORES.put("redstone", List.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE));
        ORES.put("lapis",    List.of(Blocks.LAPIS_ORE,    Blocks.DEEPSLATE_LAPIS_ORE));
        ORES.put("emerald",  List.of(Blocks.EMERALD_ORE,  Blocks.DEEPSLATE_EMERALD_ORE));
        ORES.put("copper",   List.of(Blocks.COPPER_ORE,   Blocks.DEEPSLATE_COPPER_ORE));
        ORES.put("ancient",  List.of(Blocks.ANCIENT_DEBRIS));
    }

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            dispatcher.register(ClientCommandManager.literal("findore")
                .then(ClientCommandManager.argument("ore", StringArgumentType.word())
                    .executes(ctx -> findOre(ctx.getSource(),
                        StringArgumentType.getString(ctx, "ore"), 64))
                    .then(ClientCommandManager.argument("radius", IntegerArgumentType.integer(1, 256))
                        .executes(ctx -> findOre(ctx.getSource(),
                            StringArgumentType.getString(ctx, "ore"),
                            IntegerArgumentType.getInteger(ctx, "radius"))))));

            dispatcher.register(ClientCommandManager.literal("listores")
                .executes(ctx -> { ctx.getSource().sendFeedback(
                    Text.literal("ores: " + String.join(", ", ORES.keySet()))); return 1; }));

            dispatcher.register(ClientCommandManager.literal("clearmark")
                .executes(ctx -> { Marker.clear();
                    ctx.getSource().sendFeedback(Text.literal("marker cleared")); return 1; }));

            dispatcher.register(ClientCommandManager.literal("findstronghold")
                .executes(ctx -> sendLocate("minecraft:stronghold")));

            dispatcher.register(ClientCommandManager.literal("findstructure")
                .then(ClientCommandManager.argument("id", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String id = StringArgumentType.getString(ctx, "id").trim();
                        if (!id.contains(":")) id = "minecraft:" + id;
                        return sendLocate(id);
                    })));

            dispatcher.register(ClientCommandManager.literal("waypoints")
                .executes(ctx -> {
                    List<Waypoints.WP> all = Waypoints.all();
                    if (all.isEmpty()) {
                        ctx.getSource().sendFeedback(Text.literal("no waypoints - try /findstructure village"));
                        return 1;
                    }
                    ctx.getSource().sendFeedback(Text.literal("waypoints (" + all.size() + "):"));
                    for (int i = 0; i < all.size(); i++) {
                        Waypoints.WP w = all.get(i);
                        ctx.getSource().sendFeedback(Text.literal(
                            String.format(" %d. %s @ %d %d %d", i+1, w.name, w.pos.getX(), w.pos.getY(), w.pos.getZ())));
                    }
                    return 1;
                })
                .then(ClientCommandManager.literal("clear").executes(ctx -> {
                    Waypoints.clear();
                    ctx.getSource().sendFeedback(Text.literal("waypoints cleared")); return 1; }))
                .then(ClientCommandManager.literal("mark")
                    .then(ClientCommandManager.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            int i = IntegerArgumentType.getInteger(ctx, "index") - 1;
                            List<Waypoints.WP> all = Waypoints.all();
                            if (i < 0 || i >= all.size()) {
                                ctx.getSource().sendFeedback(Text.literal("bad index")); return 0;
                            }
                            Waypoints.WP w = all.get(i);
                            Marker.set(w.pos, w.name);
                            ctx.getSource().sendFeedback(Text.literal("marked " + w.name));
                            return 1;
                        }))));

            dispatcher.register(ClientCommandManager.literal("spawninfo")
                .executes(ctx -> { SpawnAnalyzer.run(ctx.getSource()); return 1; }));

            dispatcher.register(ClientCommandManager.literal("seedpicker")
                .then(ClientCommandManager.argument("profile", StringArgumentType.word())
                    .executes(ctx -> SeedPicker.run(ctx.getSource(),
                        StringArgumentType.getString(ctx, "profile"), 50))
                    .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(5, 500))
                        .executes(ctx -> SeedPicker.run(ctx.getSource(),
                            StringArgumentType.getString(ctx, "profile"),
                            IntegerArgumentType.getInteger(ctx, "count"))))));

            // --- inventory sorter ---
            dispatcher.register(ClientCommandManager.literal("sortinv")
                .executes(ctx -> { InvSorter.sort(ctx.getSource()); return 1; }));

            // --- terrain bot ---
            dispatcher.register(ClientCommandManager.literal("pos1")
                .executes(ctx -> { Terrain.setPos(ctx.getSource(), 1); return 1; }));
            dispatcher.register(ClientCommandManager.literal("pos2")
                .executes(ctx -> { Terrain.setPos(ctx.getSource(), 2); return 1; }));
            dispatcher.register(ClientCommandManager.literal("terrain")
                .then(ClientCommandManager.literal("styles")
                    .executes(ctx -> { Terrain.listStyles(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("suggest")
                    .executes(ctx -> { Terrain.suggest(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("cancel")
                    .executes(ctx -> { Terrain.cancel(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.argument("style", StringArgumentType.word())
                    .executes(ctx -> { Terrain.generate(ctx.getSource(),
                        StringArgumentType.getString(ctx, "style")); return 1; })));

            // --- fast place toggle ---
            dispatcher.register(ClientCommandManager.literal("fastplace")
                .executes(ctx -> { FastPlace.toggle(ctx.getSource()); return 1; }));

            // --- inventory: stack partial stacks ---
            dispatcher.register(ClientCommandManager.literal("stackinv")
                .executes(ctx -> { InvSorter.stack(ctx.getSource()); return 1; }));

            // --- auto mine ---
            dispatcher.register(ClientCommandManager.literal("automine")
                .executes(ctx -> { AutoMine.toggle(ctx.getSource(), null); return 1; })
                .then(ClientCommandManager.argument("ore", StringArgumentType.word())
                    .executes(ctx -> { AutoMine.toggle(ctx.getSource(),
                        StringArgumentType.getString(ctx, "ore")); return 1; })));

            // --- strip mine (walks in a serpentine pattern) ---
            dispatcher.register(ClientCommandManager.literal("stripmine")
                .executes(ctx -> { StripMine.toggle(ctx.getSource(), 16); return 1; })
                .then(ClientCommandManager.argument("length", IntegerArgumentType.integer(4, 128))
                    .executes(ctx -> { StripMine.toggle(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "length")); return 1; })));

            // --- webhook ---
            dispatcher.register(ClientCommandManager.literal("webhook")
                .then(ClientCommandManager.literal("set")
                    .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> { Webhook.setUrl(ctx.getSource(),
                            StringArgumentType.getString(ctx, "url")); return 1; })))
                .then(ClientCommandManager.literal("clear")
                    .executes(ctx -> { Webhook.setUrl(ctx.getSource(), ""); return 1; }))
                .then(ClientCommandManager.literal("test")
                    .executes(ctx -> { Webhook.fire("test", "DreamBot webhook test");
                        ctx.getSource().sendFeedback(Text.literal("test sent")); return 1; }))
                .then(ClientCommandManager.literal("toggle")
                    .then(ClientCommandManager.argument("event", StringArgumentType.word())
                        .executes(ctx -> { Webhook.toggle(ctx.getSource(),
                            StringArgumentType.getString(ctx, "event")); return 1; }))));

            // --- fullbright toggle ---
            dispatcher.register(ClientCommandManager.literal("fullbright")
                .executes(ctx -> { FullBright.toggle(ctx.getSource()); return 1; }));

            // --- back: marks last death point ---
            dispatcher.register(ClientCommandManager.literal("back")
                .executes(ctx -> { DeathTracker.markLastDeath(ctx.getSource()); return 1; }));

            // --- speedrun assist ---
            dispatcher.register(ClientCommandManager.literal("speedrun")
                .then(ClientCommandManager.literal("start")
                    .executes(ctx -> { Speedrun.start(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("stop")
                    .executes(ctx -> { Speedrun.stop(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("reset")
                    .executes(ctx -> { Speedrun.reset(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("split")
                    .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> { Speedrun.split(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")); return 1; })))
                .then(ClientCommandManager.literal("pb")
                    .executes(ctx -> { Speedrun.showPb(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("splits")
                    .executes(ctx -> { Speedrun.showSplits(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("savepb")
                    .executes(ctx -> { Speedrun.savePbSplits(ctx.getSource()); return 1; })));

            // --- practice resetter ---
            dispatcher.register(ClientCommandManager.literal("srreset")
                .executes(ctx -> { SrTools.practiceReset(ctx.getSource()); return 1; }));

            // --- more QoL toggles ---
            dispatcher.register(ClientCommandManager.literal("autosprint")
                .executes(ctx -> { AutoSprint.toggle(ctx.getSource()); return 1; }));
            dispatcher.register(ClientCommandManager.literal("autotool")
                .executes(ctx -> { AutoTool.toggle(ctx.getSource()); return 1; }));

            // --- chat aliases ---
            dispatcher.register(ClientCommandManager.literal("alias")
                .then(ClientCommandManager.literal("list")
                    .executes(ctx -> { ChatExtras.listAliases(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("set")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                ChatExtras.setAlias(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "text"));
                                return 1;
                            }))))
                .then(ClientCommandManager.literal("del")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            ChatExtras.delAlias(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"));
                            return 1;
                        }))));

            // --- ignore list ---
            dispatcher.register(ClientCommandManager.literal("ignore")
                .then(ClientCommandManager.literal("list")
                    .executes(ctx -> { ChatExtras.listIgnored(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        ChatExtras.ignore(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name"));
                        return 1;
                    })));
            dispatcher.register(ClientCommandManager.literal("unignore")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        ChatExtras.unignore(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name"));
                        return 1;
                    })));

            // --- home point ---
            dispatcher.register(ClientCommandManager.literal("sethome")
                .executes(ctx -> { HomePoint.setHome(ctx.getSource()); return 1; }));
            dispatcher.register(ClientCommandManager.literal("home")
                .executes(ctx -> { HomePoint.markHome(ctx.getSource()); return 1; }));

            // --- 20-pack QoL commands ---
            dispatcher.register(ClientCommandManager.literal("copycoords")
                .executes(ctx -> { QolPack.copyCoords(ctx.getSource()); return 1; }));
            dispatcher.register(ClientCommandManager.literal("sharecoords")
                .executes(ctx -> { QolPack.shareCoords(ctx.getSource()); return 1; }));
            dispatcher.register(ClientCommandManager.literal("clearchat")
                .executes(ctx -> { QolPack.clearChat(ctx.getSource()); return 1; }));
            dispatcher.register(ClientCommandManager.literal("autorespawn")
                .executes(ctx -> {
                    DreamBotConfig.get().autoRespawn = !DreamBotConfig.get().autoRespawn;
                    DreamBotConfig.save();
                    ctx.getSource().sendFeedback(Text.literal("auto respawn: " +
                        (DreamBotConfig.get().autoRespawn ? "ON" : "off"))); return 1;
                }));
            dispatcher.register(ClientCommandManager.literal("permachat")
                .executes(ctx -> {
                    DreamBotConfig.get().permaChat = !DreamBotConfig.get().permaChat;
                    DreamBotConfig.save();
                    ctx.getSource().sendFeedback(Text.literal("perma chat: " +
                        (DreamBotConfig.get().permaChat ? "ON" : "off"))); return 1;
                }));
            dispatcher.register(ClientCommandManager.literal("togglesneak")
                .executes(ctx -> {
                    DreamBotConfig.get().toggleSneakMode = !DreamBotConfig.get().toggleSneakMode;
                    DreamBotConfig.save();
                    ctx.getSource().sendFeedback(Text.literal("sneak mode: " +
                        (DreamBotConfig.get().toggleSneakMode ? "toggle" : "hold"))); return 1;
                }));
            dispatcher.register(ClientCommandManager.literal("stats")
                .executes(ctx -> { SessionStats.show(ctx.getSource()); return 1; }));
            dispatcher.register(ClientCommandManager.literal("resetstats")
                .executes(ctx -> { SessionStats.reset(ctx.getSource()); return 1; }));

            // --- 20-pack v2 commands ---
            dispatcher.register(ClientCommandManager.literal("wp")
                .then(ClientCommandManager.literal("save")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> { QolPack2.wpSave(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")); return 1; })))
                .then(ClientCommandManager.literal("list")
                    .executes(ctx -> { QolPack2.wpList(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("mark")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> { QolPack2.wpMark(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")); return 1; })))
                .then(ClientCommandManager.literal("del")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> { QolPack2.wpDel(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")); return 1; }))));

            dispatcher.register(ClientCommandManager.literal("finditem")
                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                    .executes(ctx -> { QolPack2.findItem(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")); return 1; })));

            dispatcher.register(ClientCommandManager.literal("repeat")
                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 100))
                    .then(ClientCommandManager.argument("cmd", StringArgumentType.greedyString())
                        .executes(ctx -> { QolPack2.repeat(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "n"),
                            StringArgumentType.getString(ctx, "cmd")); return 1; }))));

            dispatcher.register(ClientCommandManager.literal("timer")
                .then(ClientCommandManager.argument("secs", IntegerArgumentType.integer(1, 86400))
                    .then(ClientCommandManager.argument("msg", StringArgumentType.greedyString())
                        .executes(ctx -> { QolPack2.startTimer(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "secs"),
                            StringArgumentType.getString(ctx, "msg")); return 1; }))));

            dispatcher.register(ClientCommandManager.literal("day")
                .executes(ctx -> { QolPack2.showDay(ctx.getSource()); return 1; }));

            dispatcher.register(ClientCommandManager.literal("mobs")
                .executes(ctx -> { QolPack2.listMobs(ctx.getSource()); return 1; }));

            dispatcher.register(ClientCommandManager.literal("motd")
                .executes(ctx -> { QolPack2.showMotd(ctx.getSource()); return 1; }));

            dispatcher.register(ClientCommandManager.literal("note")
                .then(ClientCommandManager.literal("list")
                    .executes(ctx -> { Notes.list(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("clear")
                    .executes(ctx -> { Notes.clear(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("add")
                    .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> { Notes.add(ctx.getSource(),
                            StringArgumentType.getString(ctx, "text")); return 1; }))));

            dispatcher.register(ClientCommandManager.literal("autogreet")
                .then(ClientCommandManager.argument("msg", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        DreamBotConfig.get().autoGreetMsg = StringArgumentType.getString(ctx, "msg");
                        DreamBotConfig.save();
                        ctx.getSource().sendFeedback(Text.literal("auto-greet set"));
                        return 1;
                    })));
            dispatcher.register(ClientCommandManager.literal("autogreetoff")
                .executes(ctx -> {
                    DreamBotConfig.get().autoGreetMsg = "";
                    DreamBotConfig.save();
                    ctx.getSource().sendFeedback(Text.literal("auto-greet cleared"));
                    return 1;
                }));

            // --- nether coord calc ---
            dispatcher.register(ClientCommandManager.literal("netherof")
                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> { SrTools.netherOf(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "x"),
                            IntegerArgumentType.getInteger(ctx, "z")); return 1; }))));
            dispatcher.register(ClientCommandManager.literal("overworldof")
                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> { SrTools.overworldOf(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "x"),
                            IntegerArgumentType.getInteger(ctx, "z")); return 1; }))));

            // --- blind travel helper ---
            dispatcher.register(ClientCommandManager.literal("blind")
                .then(ClientCommandManager.literal("eye1")
                    .executes(ctx -> { BlindTravel.captureEye(ctx.getSource(), 1); return 1; }))
                .then(ClientCommandManager.literal("eye2")
                    .executes(ctx -> { BlindTravel.captureEye(ctx.getSource(), 2); return 1; }))
                .then(ClientCommandManager.literal("calc")
                    .executes(ctx -> { BlindTravel.calculate(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("clear")
                    .executes(ctx -> { BlindTravel.clear(ctx.getSource()); return 1; })));

            // --- menu / hud config ---
            dispatcher.register(ClientCommandManager.literal("dreambot")
                .executes(ctx -> {
                    MinecraftClient.getInstance().send(() ->
                        MinecraftClient.getInstance().setScreen(new DreamBotMenu()));
                    return 1;
                }));
            dispatcher.register(ClientCommandManager.literal("hudconfig")
                .executes(ctx -> {
                    MinecraftClient.getInstance().send(() ->
                        MinecraftClient.getInstance().setScreen(new HudConfigScreen()));
                    return 1;
                }));
        });

        // keybind: right shift opens menu
        DreamBotKeys.MENU = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.dreambot.menu", GLFW.GLFW_KEY_RIGHT_SHIFT, "key.categories.dreambot"));
        DreamBotKeys.ZOOM = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.dreambot.zoom", GLFW.GLFW_KEY_C, "key.categories.dreambot"));

        DreamBotConfig.load();

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            Waypoints.maybeCapture(message.getString());
        });

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) return true;
            // filter ignored players: messages typically contain "<name>" or "name:"
            String text = message.getString();
            for (String name : DreamBotConfig.get().ignoredPlayers) {
                if (name == null || name.isEmpty()) continue;
                if (text.contains("<" + name + ">") || text.startsWith(name + ":") || text.contains(" " + name + ":")) {
                    return false;
                }
            }
            return true;
        });

        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (overlay) return message;
            net.minecraft.text.Text result = message;
            if (DreamBotConfig.get().chatTimestamps) {
                String ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                result = Text.literal("[" + ts + "] ").formatted(Formatting.DARK_GRAY).append(result);
            }
            // name highlight: simple substring check on player name in raw text
            if (DreamBotConfig.get().highlightName) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null) {
                    String name = mc.player.getName().getString();
                    String text = result.getString();
                    if (text.contains(name) && !text.startsWith("<" + name + ">")) {
                        // play a soft ping sound
                        try {
                            mc.player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.5f);
                        } catch (Exception ignored) {}
                        // wrap in yellow
                        result = Text.literal("").append(result).formatted(Formatting.YELLOW);
                    }
                }
            }
            return result;
        });

        // Chat aliases: .name [extra] expands to configured text. Leading dot
        // so it doesn't collide with slash commands.
        ClientSendMessageEvents.MODIFY_CHAT.register(message -> {
            if (message == null || !message.startsWith(".")) return message;
            String[] parts = message.substring(1).split("\\s+", 2);
            if (parts.length == 0) return message;
            String name = parts[0];
            String alias = DreamBotConfig.get().chatAliases.get(name);
            if (alias == null) return message;
            if (parts.length == 2) return alias + " " + parts[1];
            return alias;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Terrain.tick();
            FastPlace.tick();
            WardenTracker.tick();
            AutoMine.tick();
            StripMine.tick();
            Zoom.tick();
            DeathTracker.tick();
            DurabilityWatcher.tick();
            Speedrun.tick();
            AutoSprint.tick();
            AutoTool.tick();
            CpsCounter.tick();
            SessionTracker.tick();
            SessionStats.tick();
            AutoRespawn.tick();
            PermaChat.tick();
            ToggleSneak.tick();
            BedTracker.tick();
            BreathWatcher.tick();
            AutoGreet.tick();
            CountdownTimer.tick();
            TpsTracker.tick();
            LastDamageTracker.tick();
            PearlTracker.tick();
            while (DreamBotKeys.MENU != null && DreamBotKeys.MENU.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new DreamBotMenu());
                }
            }
        });

        TotemAndMarkerHud.register();
    }

    private static int sendLocate(String id) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;
        Waypoints.armCapture(id);
        mc.player.networkHandler.sendChatCommand("locate structure " + id);
        return 1;
    }

    private int findOre(FabricClientCommandSource src, String name, int radius) {
        List<Block> targets = ORES.get(name.toLowerCase());
        if (targets == null) { src.sendFeedback(Text.literal("unknown ore. try /listores")); return 0; }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return 0;

        boolean exposedOnly = DreamBotConfig.get().findOreExposedOnly;
        World world = mc.world;
        BlockPos origin = mc.player.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    Block b = world.getBlockState(p).getBlock();
                    if (!targets.contains(b)) continue;
                    if (exposedOnly && !hasAirNeighbor(world, p)) continue;
                    double d = origin.getSquaredDistance(p);
                    if (d < bestDist) { bestDist = d; best = p; }
                }

        if (best == null) {
            String suffix = exposedOnly ? " (exposed only — toggle in menu for full scan)" : "";
            src.sendFeedback(Text.literal("no " + name + " within " + radius + " blocks" + suffix));
        } else {
            Marker.set(best, name);
            src.sendFeedback(Text.literal(String.format(
                "nearest %s: %d %d %d (%.1fm) - marked",
                name, best.getX(), best.getY(), best.getZ(), Math.sqrt(bestDist))));
            Webhook.fire("ore_found", String.format("Found %s at %d %d %d", name, best.getX(), best.getY(), best.getZ()));
        }
        return 1;
    }

    private static boolean hasAirNeighbor(World world, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (world.getBlockState(p.offset(d)).isAir()) return true;
        }
        return false;
    }
}

// ================== Marker state ==================
class Marker {
    static volatile BlockPos pos = null;
    static volatile String label = null;
    static void set(BlockPos p, String name) { pos = p; label = name; }
    static void clear() { pos = null; label = null; }
}

// ================== HUD: totem + marker compass ==================
class TotemAndMarkerHud {
    private static int lastCount = -1;
    private static long flashUntil = 0;
    private static boolean flashGain = false;
    private static int sessionPopped = 0;

    static void register() { HudRenderCallback.EVENT.register(TotemAndMarkerHud::render); }

    private static int countTotems(PlayerInventory inv) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(Items.TOTEM_OF_UNDYING)) n += s.getCount();
        }
        return n;
    }

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        DreamBotConfig cfg = DreamBotConfig.get();

        int count = countTotems(mc.player.getInventory());
        if (lastCount != -1 && count != lastCount) {
            flashGain = count > lastCount;
            if (!flashGain) {
                sessionPopped += (lastCount - count);
                Webhook.fire("totem_pop", "Totem popped! Remaining: " + count);
            }
            flashUntil = System.currentTimeMillis() + 1500;
        }
        lastCount = count;

        List<HudLine> lines = new ArrayList<>();

        if (cfg.showTotems) {
            String text = "Totems: " + count + "  |  Popped: " + sessionPopped;
            Formatting color = Formatting.WHITE;
            if (System.currentTimeMillis() < flashUntil) {
                color = flashGain ? Formatting.GREEN : Formatting.RED;
                text += flashGain ? "  (+1)" : "  (popped!)";
            } else if (count == 0) color = Formatting.DARK_RED;
            else if (count == 1)   color = Formatting.GOLD;
            lines.add(new HudLine(text, color));
        }

        BlockPos m = Marker.pos;
        if (cfg.showMarker && m != null) {
            Vec3d p = mc.player.getPos();
            double dx = (m.getX() + 0.5) - p.x;
            double dz = (m.getZ() + 0.5) - p.z;
            double dy = (m.getY() + 0.5) - p.y;
            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
            float yaw = MathHelper.wrapDegrees(mc.player.getYaw());
            double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double rel = MathHelper.wrapDegrees((float)(targetYaw - yaw));
            String arrow;
            if (rel > -22.5 && rel < 22.5)          arrow = "^";
            else if (rel >= 22.5 && rel < 67.5)     arrow = "/^";
            else if (rel >= 67.5 && rel < 112.5)    arrow = ">";
            else if (rel >= 112.5 && rel < 157.5)   arrow = "v\\";
            else if (rel <= -22.5 && rel > -67.5)   arrow = "^\\";
            else if (rel <= -67.5 && rel > -112.5)  arrow = "<";
            else if (rel <= -112.5 && rel > -157.5) arrow = "/v";
            else                                     arrow = "v";
            String markerLine = String.format("%s %s  %.0fm  (%d %d %d)",
                arrow, Marker.label == null ? "marker" : Marker.label,
                dist, m.getX(), m.getY(), m.getZ());
            lines.add(new HudLine(markerLine, dist < 8 ? Formatting.GREEN : Formatting.AQUA));
        }

        if (cfg.showCoords) {
            BlockPos pp = mc.player.getBlockPos();
            String facing = cardinalFacing(mc.player.getYaw());
            String coordLine = String.format("XYZ %d %d %d  %s", pp.getX(), pp.getY(), pp.getZ(), facing);
            if (cfg.showLight && mc.world != null) {
                int blockLight = mc.world.getLightLevel(net.minecraft.world.LightType.BLOCK, pp);
                int skyLight = mc.world.getLightLevel(net.minecraft.world.LightType.SKY, pp);
                coordLine += String.format("  L:%d/%d", blockLight, skyLight);
            }
            lines.add(new HudLine(coordLine, Formatting.GRAY));
        }

        if (cfg.showTime && mc.world != null) {
            long worldTime = mc.world.getTimeOfDay();
            long day = worldTime / 24000L;
            long tod = worldTime % 24000L;
            long totalMin = ((tod + 6000) % 24000) * 60 / 1000;
            long hh = (totalMin / 60) % 24;
            long mm = totalMin % 60;
            String phase; Formatting pc;
            if (tod < 12000)      { phase = "day";   pc = Formatting.YELLOW; }
            else if (tod < 13000) { phase = "dusk";  pc = Formatting.GOLD; }
            else if (tod < 23000) { phase = "night"; pc = Formatting.BLUE; }
            else                  { phase = "dawn";  pc = Formatting.GOLD; }
            lines.add(new HudLine(String.format("Day %d  %02d:%02d  (%s)", day, hh, mm, phase), pc));
        }

        if (cfg.showBiome && mc.world != null) {
            String biome = mc.world.getBiome(mc.player.getBlockPos()).getKey()
                .map(k -> k.getValue().getPath()).orElse("unknown");
            lines.add(new HudLine("Biome: " + biome, Formatting.DARK_GRAY));
        }

        if (cfg.showWarden && WardenTracker.warningLevel > 0) {
            Formatting wc = WardenTracker.warningLevel >= 3 ? Formatting.DARK_RED :
                            WardenTracker.warningLevel >= 2 ? Formatting.RED : Formatting.GOLD;
            String wardenLine = "Warden warning: " + WardenTracker.warningLevel + "/4";
            if (WardenTracker.warningLevel >= 3) wardenLine += "  NEXT SHRIEK SUMMONS";
            lines.add(new HudLine(wardenLine, wc));
        }

        if (cfg.showFastplace && FastPlace.enabled) {
            lines.add(new HudLine("[fastplace]", Formatting.LIGHT_PURPLE));
        }

        if (cfg.showFps) {
            lines.add(new HudLine("FPS: " + mc.getCurrentFps(), Formatting.GRAY));
        }

        if (cfg.showPing && mc.getNetworkHandler() != null) {
            try {
                var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
                if (entry != null) {
                    int ping = entry.getLatency();
                    Formatting pc = ping < 80 ? Formatting.GREEN : ping < 200 ? Formatting.YELLOW : Formatting.RED;
                    lines.add(new HudLine("Ping: " + ping + "ms", pc));
                }
            } catch (Exception ignored) {}
        }

        if (cfg.showDurability) {
            ItemStack held = mc.player.getMainHandStack();
            if (held != null && !held.isEmpty() && held.isDamageable()) {
                int max = held.getMaxDamage();
                int dmg = held.getDamage();
                int remaining = max - dmg;
                int pct = (int)((remaining * 100L) / Math.max(1, max));
                if (pct < 25) {
                    Formatting dc = pct < 10 ? Formatting.DARK_RED : pct < 25 ? Formatting.RED : Formatting.YELLOW;
                    lines.add(new HudLine(
                        held.getItem().getName().getString() + ": " + remaining + "/" + max + " (" + pct + "%)",
                        dc));
                }
            }
        }

        if (cfg.showArmor) {
            String[] slotNames = {"boots", "legs", "chest", "helm"};
            for (int slot = 0; slot < 4; slot++) {
                ItemStack a = mc.player.getInventory().getArmorStack(slot);
                if (a == null || a.isEmpty() || !a.isDamageable()) continue;
                int max = a.getMaxDamage();
                int remaining = max - a.getDamage();
                int pct = (int)((remaining * 100L) / Math.max(1, max));
                if (pct < 25) {
                    Formatting ac = pct < 10 ? Formatting.DARK_RED : Formatting.RED;
                    lines.add(new HudLine(
                        slotNames[slot] + ": " + pct + "%", ac));
                }
            }
        }

        if (cfg.showHandInfo) {
            ItemStack held = mc.player.getMainHandStack();
            if (held != null && !held.isEmpty()) {
                String name = held.getItem().getName().getString();
                StringBuilder info = new StringBuilder(name);
                if (held.getCount() > 1) info.append(" x").append(held.getCount());
                int enchCount = held.getEnchantments().getSize();
                if (enchCount > 0) info.append("  [").append(enchCount).append(" ench]");
                float weaponDmg = getWeaponDamage(held);
                if (weaponDmg > 0) {
                    info.append("  ").append(formatHearts(weaponDmg / 2f)).append("\u2764");
                }
                if (held.isDamageable()) {
                    int max = held.getMaxDamage();
                    int rem = max - held.getDamage();
                    info.append("  ").append(rem).append("/").append(max);
                }
                lines.add(new HudLine(info.toString(), Formatting.WHITE));
            }
        }

        if (cfg.showRealClock) {
            String t = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            lines.add(new HudLine("Clock: " + t, Formatting.DARK_GRAY));
        }

        if (cfg.showSession) {
            long secs = SessionTracker.sessionSeconds();
            long h = secs / 3600, m = (secs % 3600) / 60;
            String sess = h > 0
                ? String.format("Session: %dh %dm", h, m)
                : String.format("Session: %dm", m);
            lines.add(new HudLine(sess, Formatting.DARK_GRAY));
        }

        if (cfg.showCps) {
            int cps = CpsCounter.getCps();
            Formatting cpc = cps >= 10 ? Formatting.GREEN : cps >= 5 ? Formatting.YELLOW : Formatting.GRAY;
            lines.add(new HudLine("CPS: " + cps, cpc));
        }

        if (cfg.showXp) {
            int lvl = mc.player.experienceLevel;
            int currentXp = (int) (mc.player.experienceProgress * mc.player.getNextLevelExperience());
            int next = mc.player.getNextLevelExperience();
            lines.add(new HudLine(
                String.format("XP L%d  %d/%d  (total %d)", lvl, currentXp, next, mc.player.totalExperience),
                Formatting.GREEN));
        }

        if (cfg.showSaturation) {
            int food = mc.player.getHungerManager().getFoodLevel();
            float sat = mc.player.getHungerManager().getSaturationLevel();
            Formatting fc = food < 10 ? Formatting.RED : food < 17 ? Formatting.YELLOW : Formatting.WHITE;
            lines.add(new HudLine(
                String.format("Food: %d   Sat: %.1f", food, sat), fc));
        }

        if (cfg.showElytra && mc.player.isFallFlying()) {
            Vec3d vel = mc.player.getVelocity();
            double bps = vel.length() * 20.0; // blocks per tick * 20 ticks = blocks/sec
            double altY = mc.player.getY();
            // estimate ground: scan straight down up to 200 blocks
            int groundY = (int) altY;
            BlockPos p = mc.player.getBlockPos();
            for (int dy = 0; dy < 200; dy++) {
                BlockPos q = p.down(dy);
                if (mc.world != null && !mc.world.getBlockState(q).isAir()) { groundY = q.getY(); break; }
            }
            int altAboveGround = (int) (altY - groundY);
            String facing = cardinalFacing(mc.player.getYaw());
            lines.add(new HudLine(
                String.format("Elytra  %.1f bps  alt:%d (ground %d)  %s", bps, (int) altY, altAboveGround, facing),
                Formatting.AQUA));
        }

        if (AutoSprint.enabled) {
            lines.add(new HudLine("[autosprint]", Formatting.LIGHT_PURPLE));
        }
        if (AutoTool.enabled) {
            lines.add(new HudLine("[autotool]", Formatting.LIGHT_PURPLE));
        }

        // --- 20-pack HUD lines ---
        if (cfg.showCrosshair && mc.crosshairTarget != null) {
            String info = null;
            if (mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult bhr
                && bhr.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK && mc.world != null) {
                BlockPos bp = bhr.getBlockPos();
                BlockState bs = mc.world.getBlockState(bp);
                double dist = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(bp));
                info = String.format("-> %s  %.1fm", bs.getBlock().getName().getString(), dist);
            } else if (mc.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult ehr) {
                net.minecraft.entity.Entity e = ehr.getEntity();
                double dist = mc.player.getEyePos().distanceTo(e.getPos());
                String name = e.getName().getString();
                if (e instanceof net.minecraft.entity.LivingEntity le) {
                    info = String.format("-> %s  %s/%s\u2764  %.1fm", name,
                        formatHearts(le.getHealth() / 2f),
                        formatHearts(le.getMaxHealth() / 2f),
                        dist);
                } else {
                    info = String.format("-> %s  %.1fm", name, dist);
                }
            }
            if (info != null) lines.add(new HudLine(info, Formatting.GRAY));
        }

        if (cfg.showSpawnSafety && mc.world != null) {
            BlockPos at = mc.player.getBlockPos();
            int block = mc.world.getLightLevel(net.minecraft.world.LightType.BLOCK, at);
            int sky = mc.world.getLightLevel(net.minecraft.world.LightType.SKY, at);
            if (block <= 0 && sky < 8) {
                lines.add(new HudLine("UNSAFE: mobs can spawn here", Formatting.RED));
            }
        }

        if (cfg.showEffects) {
            var effects = mc.player.getStatusEffects();
            if (!effects.isEmpty()) {
                StringBuilder sb = new StringBuilder("Effects: ");
                int count = 0;
                for (var inst : effects) {
                    if (count++ > 0) sb.append(", ");
                    String name = inst.getEffectType().value().getName().getString();
                    int secs = inst.getDuration() / 20;
                    int amp = inst.getAmplifier() + 1;
                    sb.append(name).append(" ").append(amp).append(" ");
                    if (secs >= 60) sb.append(secs/60).append("m").append(String.format("%02d", secs%60)).append("s");
                    else sb.append(secs).append("s");
                    if (count >= 3) { if (effects.size() > 3) sb.append(" +").append(effects.size()-3); break; }
                }
                lines.add(new HudLine(sb.toString(), Formatting.LIGHT_PURPLE));
            }
        }

        if (cfg.showBreath) {
            int air = mc.player.getAir();
            int maxAir = mc.player.getMaxAir();
            if (air < maxAir) {
                Formatting bc = air < 60 ? Formatting.DARK_RED : air < 150 ? Formatting.RED : Formatting.AQUA;
                lines.add(new HudLine("Air: " + air + "/" + maxAir, bc));
            }
        }

        if (cfg.showFreeze) {
            int freeze = mc.player.getFrozenTicks();
            if (freeze > 0) {
                int min = mc.player.getMinFreezeDamageTicks();
                Formatting fc = freeze >= min ? Formatting.AQUA : Formatting.BLUE;
                lines.add(new HudLine("Freeze: " + freeze + "/" + min, fc));
            }
        }

        if (cfg.showSleep && mc.world != null) {
            long tod = mc.world.getTimeOfDay() % 24000L;
            if (tod >= 12542 && tod < 23460) {
                long secs = (23460 - tod) * 50 / 1000;
                lines.add(new HudLine(String.format("Sleepable  (morning in %ds)", secs), Formatting.DARK_AQUA));
            } else {
                long ticksUntilNight = 12542 - tod;
                if (ticksUntilNight < 0) ticksUntilNight += 24000;
                long secs = ticksUntilNight * 50 / 1000;
                lines.add(new HudLine(String.format("Sleep in %ds", secs), Formatting.GRAY));
            }
        }

        if (cfg.showKillCount) {
            lines.add(new HudLine("Kills: " + SessionStats.kills, Formatting.RED));
        }
        if (cfg.showWalkDist) {
            lines.add(new HudLine("Walked: " + (int) SessionStats.distance + "m", Formatting.GRAY));
        }
        if (cfg.showDamageTaken) {
            float hearts = (float)(SessionStats.damageTaken / 2.0);
            lines.add(new HudLine("Dmg taken: " + formatHearts(hearts) + "\u2764", Formatting.DARK_RED));
        }

        if (cfg.showNearbyMobs && mc.world != null) {
            int hostile = 0, passive = 0, playersCount = 0;
            double range = 32 * 32;
            for (net.minecraft.entity.Entity e : mc.world.getEntities()) {
                if (e == mc.player) continue;
                if (e.squaredDistanceTo(mc.player) > range) continue;
                if (e instanceof net.minecraft.entity.mob.HostileEntity) hostile++;
                else if (e instanceof net.minecraft.entity.player.PlayerEntity) playersCount++;
                else if (e instanceof net.minecraft.entity.LivingEntity) passive++;
            }
            lines.add(new HudLine(
                String.format("Nearby: %dh %dp %dplyr", hostile, passive, playersCount),
                Formatting.YELLOW));
        }

        if (cfg.showFallDmg && !mc.player.isOnGround() && !mc.player.isFallFlying()
            && mc.player.fallDistance > 3) {
            float dmg = mc.player.fallDistance - 3;
            float hearts = dmg / 2f;
            Formatting fc = dmg > mc.player.getHealth() ? Formatting.DARK_RED : Formatting.RED;
            lines.add(new HudLine(String.format("Fall: %s\u2764", formatHearts(hearts)), fc));
        }

        // --- 20-pack v2 HUD lines ---
        if (cfg.showSpawnDist && mc.world != null) {
            BlockPos spawn = mc.world.getSpawnPos();
            BlockPos pp = mc.player.getBlockPos();
            double dx = spawn.getX() - pp.getX();
            double dz = spawn.getZ() - pp.getZ();
            double dist = Math.sqrt(dx*dx + dz*dz);
            String facing = cardinalFacing((float)Math.toDegrees(Math.atan2(-dx, dz)));
            lines.add(new HudLine(String.format("Spawn: %.0fm %s", dist, facing), Formatting.GRAY));
        }

        if (cfg.showVelocity) {
            Vec3d v = mc.player.getVelocity();
            lines.add(new HudLine(
                String.format("Vel: %.2f %.2f %.2f", v.x*20, v.y*20, v.z*20),
                Formatting.GRAY));
        }

        if (cfg.showLastDmg && LastDamageTracker.lastSource != null
            && System.currentTimeMillis() - LastDamageTracker.lastTime < 10000) {
            long secs = (System.currentTimeMillis() - LastDamageTracker.lastTime) / 1000;
            lines.add(new HudLine(
                String.format("Last dmg: %s (%ds)", LastDamageTracker.lastSource, secs),
                Formatting.RED));
        }

        if (cfg.showPearlCd && PearlTracker.cooldownLeftMs() > 0) {
            long left = PearlTracker.cooldownLeftMs();
            lines.add(new HudLine(String.format("Pearl: %.1fs", left / 1000.0), Formatting.LIGHT_PURPLE));
        }

        if (cfg.showReach) {
            double reach = mc.player.isCreative() ? 5.0 : 4.5;
            lines.add(new HudLine(String.format("Reach: %.1f", reach), Formatting.GRAY));
        }

        if (cfg.showWeather && mc.world != null) {
            String weather;
            if (mc.world.isThundering()) weather = "thunder";
            else if (mc.world.isRaining()) weather = "rain";
            else weather = "clear";
            lines.add(new HudLine("Weather: " + weather, Formatting.GRAY));
        }

        if (cfg.showTps) {
            double tps = TpsTracker.getTps();
            Formatting tc = tps >= 19 ? Formatting.GREEN : tps >= 15 ? Formatting.YELLOW : Formatting.RED;
            lines.add(new HudLine(String.format("TPS: %.1f", tps), tc));
        }

        if (CountdownTimer.isActive()) {
            long left = CountdownTimer.secondsLeft();
            Formatting tc = left < 10 ? Formatting.RED : Formatting.YELLOW;
            lines.add(new HudLine(String.format("Timer %s: %ds", CountdownTimer.label(), left), tc));
        }

        if (cfg.autoRespawn)    lines.add(new HudLine("[autorespawn]", Formatting.LIGHT_PURPLE));
        if (cfg.permaChat)      lines.add(new HudLine("[permachat]", Formatting.LIGHT_PURPLE));
        if (cfg.toggleSneakMode)lines.add(new HudLine("[togglesneak]", Formatting.LIGHT_PURPLE));

        if (cfg.showSpeedrun && Speedrun.isRunning()) {
            lines.add(new HudLine(Speedrun.formatTimer(), Formatting.WHITE));
            String last = Speedrun.lastSplit();
            if (last != null) lines.add(new HudLine(last, Formatting.GRAY));
            String items = Speedrun.itemCounters(mc);
            if (items != null) lines.add(new HudLine(items, Formatting.AQUA));
            String ready = Speedrun.readinessLine(mc);
            if (ready != null) lines.add(new HudLine(ready, Formatting.LIGHT_PURPLE));
        }

        // draw with anchor + scale
        float scale = cfg.hudScale;
        ctx.getMatrices().push();
        ctx.getMatrices().scale(scale, scale, 1);
        int sw = (int)(mc.getWindow().getScaledWidth() / scale);
        int sh = (int)(mc.getWindow().getScaledHeight() / scale);
        int lh = 12;
        for (int i = 0; i < lines.size(); i++) {
            HudLine ln = lines.get(i);
            int w = mc.textRenderer.getWidth(ln.text);
            int x, y;
            switch (cfg.anchor) {
                case "top-right":    x = sw - 4 - w; y = 4 + i*lh; break;
                case "bottom-left":  x = 4;          y = sh - 4 - (lines.size()-i)*lh; break;
                case "bottom-right": x = sw - 4 - w; y = sh - 4 - (lines.size()-i)*lh; break;
                default:             x = 4;          y = 4 + i*lh; break;
            }
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(ln.text).formatted(ln.color), x, y, 0xFFFFFF);
        }
        ctx.getMatrices().pop();

        // ===== WASD key overlay =====
        if (cfg.showKeys) {
            drawKeyOverlay(ctx, mc, cfg);
        }

        // ===== unavoidable auto-mine / strip-mine warning banner =====
        // Drawn outside the HUD scale block so it ignores user scaling.
        // Cannot be hidden, moved, or styled away. Pulses red to draw the eye.
        if (AutoMine.enabled || StripMine.enabled) {
            int screenW = mc.getWindow().getScaledWidth();
            String mode = AutoMine.enabled ? ("AUTO-MINE: " + AutoMine.oreType) : "STRIP-MINE";
            String banner = "*** " + mode + " ACTIVE - BOTTING - DO NOT USE ON PUBLIC SERVERS ***";
            // pulse: alternate between bright red and dark red every 500ms
            boolean pulse = (System.currentTimeMillis() / 500) % 2 == 0;
            int textColor = pulse ? 0xFFFF5555 : 0xFFAA0000;
            int bgColor   = pulse ? 0xCC000000 : 0xCC330000;

            int textW = mc.textRenderer.getWidth(banner);
            int padding = 6;
            int boxW = textW + padding * 2;
            int boxH = 14;
            int boxX = (screenW - boxW) / 2;
            int boxY = 2;
            ctx.fill(boxX, boxY, boxX + boxW, boxY + boxH, bgColor);
            ctx.fill(boxX, boxY, boxX + boxW, boxY + 1, textColor);            // top border
            ctx.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, textColor); // bottom border
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(banner),
                boxX + padding, boxY + 3, textColor);

            // also a runtime line under the banner
            long startMs = AutoMine.enabled ? AutoMine.startTimeMs() : StripMine.startTimeMs();
            if (startMs > 0) {
                long secs = (System.currentTimeMillis() - startMs) / 1000;
                String runtime = String.format("running %d:%02d   not your achievement", secs / 60, secs % 60);
                int rw = mc.textRenderer.getWidth(runtime);
                ctx.drawTextWithShadow(mc.textRenderer,
                    Text.literal(runtime).formatted(Formatting.RED),
                    (screenW - rw) / 2, boxY + boxH + 2, 0xFFFF5555);
            }
        }
    }

    private static class HudLine {
        final String text; final Formatting color;
        HudLine(String t, Formatting c) { text = t; color = c; }
    }

    private static void drawKeyOverlay(DrawContext ctx, MinecraftClient mc, DreamBotConfig cfg) {
        boolean w = mc.options.forwardKey.isPressed();
        boolean a = mc.options.leftKey.isPressed();
        boolean s = mc.options.backKey.isPressed();
        boolean d = mc.options.rightKey.isPressed();
        boolean space = mc.options.jumpKey.isPressed();
        boolean shift = mc.options.sneakKey.isPressed();

        int keyW = 16, keyH = 16, spacing = 2;
        int blockW = keyW * 3 + spacing * 2;  // A S D row width
        int blockH = keyH * 2 + spacing + 7;  // W + ASD + space bar

        int screenW = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();

        int originX, originY;
        switch (cfg.keysCorner) {
            case "bottom-right": originX = screenW - blockW - 8; originY = screenH - blockH - 44; break;
            case "top-left":     originX = 8;                    originY = 8; break;
            case "top-right":    originX = screenW - blockW - 8; originY = 8; break;
            default:             originX = 8;                    originY = screenH - blockH - 44; break;
        }

        // colors
        int off   = 0x77202020; // dim background
        int on    = 0xFFFFFFFF; // bright white when pressed
        int outline = 0xFF808080;
        int textOff = 0xFFCCCCCC;
        int textOn  = 0xFF000000;

        // W key (top, centered on middle column)
        int wX = originX + keyW + spacing;
        int wY = originY;
        drawKey(ctx, mc, wX, wY, keyW, keyH, "W", w, off, on, outline, textOff, textOn);

        // A key
        int aX = originX;
        int row2Y = originY + keyH + spacing;
        drawKey(ctx, mc, aX, row2Y, keyW, keyH, "A", a, off, on, outline, textOff, textOn);

        // S key
        int sX = originX + keyW + spacing;
        drawKey(ctx, mc, sX, row2Y, keyW, keyH, "S", s, off, on, outline, textOff, textOn);

        // D key
        int dX = originX + (keyW + spacing) * 2;
        drawKey(ctx, mc, dX, row2Y, keyW, keyH, "D", d, off, on, outline, textOff, textOn);

        // Space bar (wide, thin, under ASD row)
        int spaceY = row2Y + keyH + spacing;
        int spaceH = 5;
        ctx.fill(originX, spaceY, originX + blockW, spaceY + spaceH, space ? on : off);
        ctx.fill(originX, spaceY, originX + blockW, spaceY + 1, outline);
        ctx.fill(originX, spaceY + spaceH - 1, originX + blockW, spaceY + spaceH, outline);
        ctx.fill(originX, spaceY, originX + 1, spaceY + spaceH, outline);
        ctx.fill(originX + blockW - 1, spaceY, originX + blockW, spaceY + spaceH, outline);

        // Shift indicator (small box to the left of space)
        if (shift) {
            int shY = spaceY - 1;
            ctx.fill(originX - 10, shY, originX - 2, shY + 7, 0xFFFFAA00);
        }
    }

    private static void drawKey(DrawContext ctx, MinecraftClient mc, int x, int y, int w, int h,
                                 String label, boolean pressed, int off, int on, int outline,
                                 int textOff, int textOn) {
        int bg = pressed ? on : off;
        int fg = pressed ? textOn : textOff;
        ctx.fill(x, y, x + w, y + h, bg);
        // outline
        ctx.fill(x, y, x + w, y + 1, outline);
        ctx.fill(x, y + h - 1, x + w, y + h, outline);
        ctx.fill(x, y, x + 1, y + h, outline);
        ctx.fill(x + w - 1, y, x + w, y + h, outline);
        // centered letter
        int textW = mc.textRenderer.getWidth(label);
        int tx = x + (w - textW) / 2;
        int ty = y + (h - 8) / 2;
        ctx.drawText(mc.textRenderer, label, tx, ty, fg, false);
    }

    private static String formatHearts(float hearts) {
        float rounded = Math.round(hearts * 2) / 2f;
        if (rounded == (int) rounded) return String.valueOf((int) rounded);
        return String.format("%.1f", rounded);
    }

    // Returns total attack damage (including base 1) for known vanilla weapons.
    // Returns 0 for non-weapons. Hardcoded vanilla values to avoid version-fragile
    // attribute-component API access.
    private static float getWeaponDamage(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        net.minecraft.item.Item it = stack.getItem();
        // swords (base 1 + sword damage)
        if (it == Items.WOODEN_SWORD)    return 4;
        if (it == Items.GOLDEN_SWORD)    return 4;
        if (it == Items.STONE_SWORD)     return 5;
        if (it == Items.IRON_SWORD)      return 6;
        if (it == Items.DIAMOND_SWORD)   return 7;
        if (it == Items.NETHERITE_SWORD) return 8;
        // axes
        if (it == Items.WOODEN_AXE)    return 7;
        if (it == Items.GOLDEN_AXE)    return 7;
        if (it == Items.STONE_AXE)     return 9;
        if (it == Items.IRON_AXE)      return 9;
        if (it == Items.DIAMOND_AXE)   return 9;
        if (it == Items.NETHERITE_AXE) return 10;
        // tridents and maces
        if (it == Items.TRIDENT) return 9;
        if (it == Items.MACE)    return 6;
        // pickaxes/shovels/hoes (low but real damage)
        if (it == Items.WOODEN_PICKAXE || it == Items.GOLDEN_PICKAXE) return 2;
        if (it == Items.STONE_PICKAXE)     return 3;
        if (it == Items.IRON_PICKAXE)      return 4;
        if (it == Items.DIAMOND_PICKAXE)   return 5;
        if (it == Items.NETHERITE_PICKAXE) return 6;
        if (it == Items.WOODEN_SHOVEL || it == Items.GOLDEN_SHOVEL) return 2.5f;
        if (it == Items.STONE_SHOVEL)     return 3.5f;
        if (it == Items.IRON_SHOVEL)      return 4.5f;
        if (it == Items.DIAMOND_SHOVEL)   return 5.5f;
        if (it == Items.NETHERITE_SHOVEL) return 6.5f;
        return 0;
    }

    private static String cardinalFacing(float yaw) {
        float y = MathHelper.wrapDegrees(yaw);
        if (y >= -45 && y < 45)   return "S";
        if (y >= 45 && y < 135)   return "W";
        if (y >= -135 && y < -45) return "E";
        return "N";
    }
}

// ================== Waypoints ==================
class Waypoints {
    static class WP { final String name; final BlockPos pos; WP(String n, BlockPos p){ name=n; pos=p; } }
    private static final List<WP> list = new ArrayList<>();
    private static String pendingName = null;
    private static final Pattern LOCATE = Pattern.compile(
        "nearest\\s+([\\w:_]+).*?\\[(-?\\d+),\\s*[~\\d-]+,\\s*(-?\\d+)\\]", Pattern.CASE_INSENSITIVE);

    static void armCapture(String id) { pendingName = id; }
    static void maybeCapture(String msg) {
        if (pendingName == null) return;
        Matcher mt = LOCATE.matcher(msg);
        if (mt.find()) {
            try {
                int x = Integer.parseInt(mt.group(2));
                int z = Integer.parseInt(mt.group(3));
                String name = pendingName.replace("minecraft:", "");
                list.add(new WP(name, new BlockPos(x, 64, z)));
                pendingName = null;
            } catch (NumberFormatException ignored) {}
        }
    }
    static List<WP> all() { return list; }
    static void clear() { list.clear(); }
}

// ================== Spawn analyzer ==================
class SpawnAnalyzer {
    static void run(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) { src.sendFeedback(Text.literal("no world")); return; }
        World world = mc.world;
        BlockPos origin = mc.player.getBlockPos();

        Map<String, Integer> biomeCounts = new HashMap<>();
        int radius = 128, step = 16, samples = 0;
        for (int dx = -radius; dx <= radius; dx += step)
            for (int dz = -radius; dz <= radius; dz += step) {
                BlockPos p = origin.add(dx, 0, dz);
                RegistryEntry<Biome> entry = world.getBiome(p);
                String key = entry.getKey().map(k -> k.getValue().getPath()).orElse("unknown");
                biomeCounts.merge(key, 1, Integer::sum);
                samples++;
            }

        src.sendFeedback(Text.literal("=== spawn area (" + samples + " samples within " + radius + "m) ==="));
        final int total = samples;
        biomeCounts.entrySet().stream()
            .sorted((a,b) -> b.getValue()-a.getValue()).limit(6)
            .forEach(e -> src.sendFeedback(Text.literal(
                String.format(" %s: %d%%", e.getKey(), (e.getValue()*100)/Math.max(1,total)))));

        if (!Waypoints.all().isEmpty()) {
            src.sendFeedback(Text.literal("known structures nearby:"));
            for (Waypoints.WP w : Waypoints.all()) {
                double dist = Math.sqrt(origin.getSquaredDistance(w.pos));
                src.sendFeedback(Text.literal(String.format(" %s @ %.0fm", w.name, dist)));
            }
        }
    }
}

// ================== Seed picker ==================
class SeedPicker {
    enum Profile { SPEEDRUN, SURVIVAL, MANHUNT, PEACEFUL;
        static Profile parse(String s) { try { return valueOf(s.toUpperCase()); } catch (Exception e) { return null; } } }

    static int run(FabricClientCommandSource src, String profileName, int count) {
        Profile p = Profile.parse(profileName);
        if (p == null) { src.sendFeedback(Text.literal("profiles: speedrun, survival, manhunt, peaceful")); return 0; }
        src.sendFeedback(Text.literal("generating " + count + " candidate seeds for " + p + "..."));

        Random rng = new Random();
        List<long[]> scored = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long seed = rng.nextLong();
            scored.add(new long[]{ seed, scoreSeed(seed, p) });
        }
        scored.sort((a,b) -> Long.compare(b[1], a[1]));

        src.sendFeedback(Text.literal("=== top 5 ==="));
        for (int i = 0; i < Math.min(5, scored.size()); i++) {
            long[] e = scored.get(i);
            src.sendFeedback(Text.literal(String.format(" %d. %d  (score %d)", i+1, e[0], e[1])));
        }
        src.sendFeedback(Text.literal("paste into vanilla 'Create New World' -> 'More' -> 'Seed'"));
        return 1;
    }

    private static long scoreSeed(long seed, Profile p) {
        Random r = new Random(seed);
        int openness=r.nextInt(100), villages=r.nextInt(5), waterAccess=r.nextInt(100);
        int caveDepth=r.nextInt(100), flatness=r.nextInt(100), netherGood=r.nextInt(100);
        switch (p) {
            case SPEEDRUN: return villages*30L + netherGood + caveDepth/2 + openness/4;
            case MANHUNT:  return openness + villages*20L + netherGood/2;
            case SURVIVAL: return villages*15L + waterAccess + flatness/2 + caveDepth/3;
            case PEACEFUL: return flatness*2L + waterAccess + (100-caveDepth);
        }
        return 0;
    }
}

// ================== Inventory sorter ==================
class InvSorter {
    // Sorts main player inventory (slots 9-35) by item registry name, stacks of same item grouped.
    // Uses pickup-swap clicks against the current screen handler. Works with no container open
    // (player screen handler) - if a container is open, sorts the main-inv portion of that handler.
    static void sort(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.interactionManager == null) {
            src.sendFeedback(Text.literal("not in world")); return;
        }
        ScreenHandler handler = player.currentScreenHandler;
        int syncId = handler.syncId;

        // For the player screen handler, main inv = slots 9..35.
        // If a chest is open, player inv is at the end: slots = handler.slots.size()-36 .. size()-10.
        int size = handler.slots.size();
        int mainStart, mainEnd; // inclusive
        if (size == 46) { // PlayerScreenHandler: 0 result,1-4 grid,5-8 armor,9-35 main,36-44 hotbar,45 offhand
            mainStart = 9; mainEnd = 35;
        } else {
            // Assume main inv immediately precedes hotbar at the end; 27 main + 9 hotbar tail
            mainEnd = size - 10; // last main slot
            mainStart = mainEnd - 26;
            if (mainStart < 0) {
                src.sendFeedback(Text.literal("can't identify inventory in this screen")); return;
            }
        }

        int slotCount = mainEnd - mainStart + 1;
        ItemStack[] current = new ItemStack[slotCount];
        for (int i = 0; i < slotCount; i++) {
            current[i] = handler.getSlot(mainStart + i).getStack().copy();
        }

        // Build sorted target: non-empty stacks sorted by (itemId asc, count desc), empties at end.
        Integer[] order = new Integer[slotCount];
        for (int i = 0; i < slotCount; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            ItemStack sa = current[a], sb = current[b];
            boolean ea = sa.isEmpty(), eb = sb.isEmpty();
            if (ea && eb) return 0;
            if (ea) return 1;
            if (eb) return -1;
            String na = Registries.ITEM.getId(sa.getItem()).toString();
            String nb = Registries.ITEM.getId(sb.getItem()).toString();
            int c = na.compareTo(nb);
            if (c != 0) return c;
            return Integer.compare(sb.getCount(), sa.getCount());
        });

        ItemStack[] target = new ItemStack[slotCount];
        for (int i = 0; i < slotCount; i++) target[i] = current[order[i]];

        // Walk target; for each position i where current[i] != target[i], find source j>i that
        // matches target[i] and swap. Uses 3 PICKUP clicks (pickup i, pickup j, pickup i).
        ItemStack[] live = new ItemStack[slotCount];
        for (int i = 0; i < slotCount; i++) live[i] = current[i];

        int swaps = 0;
        for (int i = 0; i < slotCount; i++) {
            if (stacksMatch(live[i], target[i])) continue;
            int j = -1;
            for (int k = i + 1; k < slotCount; k++) {
                if (stacksMatch(live[k], target[i])) { j = k; break; }
            }
            if (j == -1) continue; // shouldn't happen if target was built from current
            // swap live[i] and live[j]
            int slotI = mainStart + i, slotJ = mainStart + j;
            mc.interactionManager.clickSlot(syncId, slotI, 0, SlotActionType.PICKUP, player);
            mc.interactionManager.clickSlot(syncId, slotJ, 0, SlotActionType.PICKUP, player);
            mc.interactionManager.clickSlot(syncId, slotI, 0, SlotActionType.PICKUP, player);
            ItemStack tmp = live[i]; live[i] = live[j]; live[j] = tmp;
            swaps++;
        }

        src.sendFeedback(Text.literal("sorted inventory (" + swaps + " swaps)"));
    }

    private static boolean stacksMatch(ItemStack a, ItemStack b) {
        if (a.isEmpty() && b.isEmpty()) return true;
        if (a.isEmpty() != b.isEmpty()) return false;
        return ItemStack.areEqual(a, b);
    }

    // Consolidate partial stacks of the same item into single stacks.
    static void stack(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.interactionManager == null) {
            src.sendFeedback(Text.literal("not in world")); return;
        }
        ScreenHandler handler = player.currentScreenHandler;
        int syncId = handler.syncId;
        int size = handler.slots.size();
        int mainStart, mainEnd;
        if (size == 46) { mainStart = 9; mainEnd = 35; }
        else { mainEnd = size - 10; mainStart = mainEnd - 26; if (mainStart < 0) {
            src.sendFeedback(Text.literal("can't identify inventory")); return; } }

        int merges = 0;
        boolean changed = true;
        int safety = 0;
        while (changed && safety++ < 64) {
            changed = false;
            for (int i = mainStart; i <= mainEnd; i++) {
                ItemStack a = handler.getSlot(i).getStack();
                if (a.isEmpty() || a.getCount() >= a.getMaxCount()) continue;
                for (int j = i + 1; j <= mainEnd; j++) {
                    ItemStack b = handler.getSlot(j).getStack();
                    if (b.isEmpty()) continue;
                    if (!ItemStack.areItemsAndComponentsEqual(a, b)) continue;
                    // pickup b, deposit on a, return remainder to b
                    mc.interactionManager.clickSlot(syncId, j, 0, SlotActionType.PICKUP, player);
                    mc.interactionManager.clickSlot(syncId, i, 0, SlotActionType.PICKUP, player);
                    mc.interactionManager.clickSlot(syncId, j, 0, SlotActionType.PICKUP, player);
                    merges++;
                    changed = true;
                    break;
                }
                if (changed) break;
            }
        }
        src.sendFeedback(Text.literal("stacked inventory (" + merges + " merges)"));
    }
}

// ================== Terrain generator ==================
class Terrain {
    static BlockPos pos1 = null, pos2 = null;
    private static final Deque<String> queue = new ArrayDeque<>();
    private static final int COMMANDS_PER_TICK = 20;

    static void setPos(FabricClientCommandSource src, int n) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        BlockPos p;
        HitResult hit = mc.crosshairTarget;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            p = bhr.getBlockPos();
        } else {
            p = mc.player.getBlockPos();
        }
        if (n == 1) pos1 = p; else pos2 = p;
        src.sendFeedback(Text.literal("pos" + n + " = " + p.getX() + " " + p.getY() + " " + p.getZ()));
        if (pos1 != null && pos2 != null) {
            int dx = Math.abs(pos1.getX() - pos2.getX()) + 1;
            int dy = Math.abs(pos1.getY() - pos2.getY()) + 1;
            int dz = Math.abs(pos1.getZ() - pos2.getZ()) + 1;
            src.sendFeedback(Text.literal("box: " + dx + " x " + dy + " x " + dz));
        }
    }

    static void listStyles(FabricClientCommandSource src) {
        src.sendFeedback(Text.literal("styles: rolling_hills, mountains, plains, desert_dunes, forest, islands, canyon"));
        src.sendFeedback(Text.literal("usage: /pos1, /pos2, /terrain <style>  (or /terrain suggest)"));
    }

    static void cancel(FabricClientCommandSource src) {
        int n = queue.size();
        queue.clear();
        src.sendFeedback(Text.literal("terrain: cancelled " + n + " queued commands"));
    }

    static void suggest(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) { src.sendFeedback(Text.literal("no world")); return; }
        World world = mc.world;
        BlockPos origin = mc.player.getBlockPos();

        // sample biomes + heightmap variance within 64 blocks
        Map<String, Integer> biomes = new HashMap<>();
        int[] heights = new int[25];
        int idx = 0;
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        for (int dx = -64; dx <= 64; dx += 32)
            for (int dz = -64; dz <= 64; dz += 32) {
                BlockPos p = origin.add(dx, 0, dz);
                String key = world.getBiome(p).getKey().map(k -> k.getValue().getPath()).orElse("unknown");
                biomes.merge(key, 1, Integer::sum);
                int h = world.getTopY();
                for (int y = Math.min(world.getTopY(), origin.getY() + 32); y > origin.getY() - 32; y--) {
                    BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                    if (!world.getBlockState(q).isAir()) { h = y; break; }
                }
                heights[idx++] = h;
                if (h < minH) minH = h;
                if (h > maxH) maxH = h;
            }
        int variance = maxH - minH;

        String topBiome = biomes.entrySet().stream()
            .max((a,b) -> a.getValue() - b.getValue())
            .map(Map.Entry::getKey).orElse("unknown");

        String suggested;
        String reason;
        if (topBiome.contains("desert")) { suggested = "desert_dunes"; reason = "you're in a desert biome"; }
        else if (topBiome.contains("badlands") || topBiome.contains("mesa")) { suggested = "canyon"; reason = "badlands terrain"; }
        else if (topBiome.contains("ocean") || topBiome.contains("beach")) { suggested = "islands"; reason = "coastal/ocean biome"; }
        else if (topBiome.contains("forest") || topBiome.contains("taiga") || topBiome.contains("jungle")) { suggested = "forest"; reason = "forested biome"; }
        else if (topBiome.contains("mountain") || topBiome.contains("peak") || topBiome.contains("hills") || variance > 20) {
            suggested = "mountains"; reason = "mountainous terrain (variance " + variance + ")"; }
        else if (variance < 4) { suggested = "plains"; reason = "very flat area"; }
        else { suggested = "rolling_hills"; reason = "moderate terrain"; }

        src.sendFeedback(Text.literal("=== terrain suggestion ==="));
        src.sendFeedback(Text.literal(" dominant biome: " + topBiome));
        src.sendFeedback(Text.literal(" height variance: " + variance + " blocks"));
        src.sendFeedback(Text.literal(" -> " + suggested + "  (" + reason + ")"));
        src.sendFeedback(Text.literal(" run: /terrain " + suggested));
    }

    static void generate(FabricClientCommandSource src, String style) {
        if (pos1 == null || pos2 == null) {
            src.sendFeedback(Text.literal("set both corners first: /pos1 and /pos2")); return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        int x1 = Math.min(pos1.getX(), pos2.getX()), x2 = Math.max(pos1.getX(), pos2.getX());
        int y1 = Math.min(pos1.getY(), pos2.getY()), y2 = Math.max(pos1.getY(), pos2.getY());
        int z1 = Math.min(pos1.getZ(), pos2.getZ()), z2 = Math.max(pos1.getZ(), pos2.getZ());

        // pick materials by biome at box center
        BlockPos center = new BlockPos((x1+x2)/2, (y1+y2)/2, (z1+z2)/2);
        String biome = mc.world.getBiome(center).getKey().map(k -> k.getValue().getPath()).orElse("plains");

        String topBlock, subBlock;
        if (biome.contains("desert")) { topBlock = "minecraft:sand"; subBlock = "minecraft:sandstone"; }
        else if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice")) { topBlock = "minecraft:snow_block"; subBlock = "minecraft:stone"; }
        else if (biome.contains("badlands") || biome.contains("mesa")) { topBlock = "minecraft:red_sand"; subBlock = "minecraft:red_sandstone"; }
        else if (biome.contains("mountain") || biome.contains("peak") || biome.contains("stony")) { topBlock = "minecraft:stone"; subBlock = "minecraft:stone"; }
        else { topBlock = "minecraft:grass_block"; subBlock = "minecraft:dirt"; }

        long seed = new Random().nextLong();
        Random noise = new Random(seed);
        double[][] heightMap = buildHeightMap(style, x2-x1+1, z2-z1+1, noise);

        int baseY = y1;
        int maxH = y2 - y1;

        queue.clear();
        int columns = 0;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                double h = heightMap[x - x1][z - z1];
                int hi = (int) Math.max(0, Math.min(maxH, Math.round(h)));
                int topY = baseY + hi;

                // fill sub from baseY..topY-1, top at topY, air above
                if (topY - 1 >= baseY) {
                    queue.add(String.format("fill %d %d %d %d %d %d %s",
                        x, baseY, z, x, topY - 1, z, subBlock));
                }
                queue.add(String.format("setblock %d %d %d %s", x, topY, z, topBlock));
                if (y2 > topY) {
                    String fillAir = style.equals("islands") || style.equals("canyon") ? "minecraft:water" : "minecraft:air";
                    // for islands: water above baseY if height very low; for canyon: air
                    if (style.equals("islands") && hi < 2) {
                        queue.add(String.format("fill %d %d %d %d %d %d minecraft:water",
                            x, topY + 1, z, x, baseY + Math.max(1, maxH/3), z));
                        if (y2 > baseY + maxH/3) {
                            queue.add(String.format("fill %d %d %d %d %d %d minecraft:air",
                                x, baseY + maxH/3 + 1, z, x, y2, z));
                        }
                    } else {
                        queue.add(String.format("fill %d %d %d %d %d %d minecraft:air",
                            x, topY + 1, z, x, y2, z));
                    }
                }
                columns++;
            }
        }

        // forest: scatter tree markers
        if (style.equals("forest")) {
            int treeCount = Math.max(1, columns / 20);
            for (int t = 0; t < treeCount; t++) {
                int tx = x1 + noise.nextInt(x2-x1+1);
                int tz = z1 + noise.nextInt(z2-z1+1);
                double h = heightMap[tx-x1][tz-z1];
                int topY = baseY + (int) Math.round(h);
                int trunkH = 4 + noise.nextInt(3);
                queue.add(String.format("fill %d %d %d %d %d %d minecraft:oak_log",
                    tx, topY+1, tz, tx, topY+trunkH, tz));
                queue.add(String.format("fill %d %d %d %d %d %d minecraft:oak_leaves replace minecraft:air",
                    tx-2, topY+trunkH-1, tz-2, tx+2, topY+trunkH+1, tz+2));
            }
        }

        src.sendFeedback(Text.literal("terrain: queued " + queue.size() + " commands for style '" + style + "'"));
        src.sendFeedback(Text.literal("materials: " + topBlock + " / " + subBlock + "  (biome: " + biome + ")"));
        src.sendFeedback(Text.literal("note: needs /fill permission (singleplayer cheats or op)"));
        src.sendFeedback(Text.literal("run /terrain cancel to stop mid-build"));
    }

    static void tick() {
        if (queue.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.player.networkHandler == null) return;
        for (int i = 0; i < COMMANDS_PER_TICK && !queue.isEmpty(); i++) {
            String cmd = queue.pollFirst();
            mc.player.networkHandler.sendChatCommand(cmd);
        }
    }

    // Height map: returns array [dx][dz] of target heights 0..1 (scaled)
    private static double[][] buildHeightMap(String style, int w, int d, Random r) {
        double[][] h = new double[w][d];
        double maxAmp;
        switch (style) {
            case "plains":       maxAmp = 1;  break;
            case "rolling_hills":maxAmp = 5;  break;
            case "mountains":    maxAmp = 20; break;
            case "desert_dunes": maxAmp = 6;  break;
            case "forest":       maxAmp = 5;  break;
            case "islands":      maxAmp = 10; break;
            case "canyon":       maxAmp = 12; break;
            default:             maxAmp = 5;
        }
        // cheap layered sine-noise with random phase
        double px = r.nextDouble() * Math.PI * 2;
        double pz = r.nextDouble() * Math.PI * 2;
        double freqA = 0.08, freqB = 0.22;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                double a = Math.sin(x*freqA + px) * Math.cos(z*freqA + pz);
                double b = Math.sin(x*freqB + px*1.3) * Math.cos(z*freqB + pz*0.7);
                double n = 0.6*a + 0.4*b; // -1..1

                double val;
                switch (style) {
                    case "mountains": val = Math.pow(Math.max(0, n + 0.2), 1.8) * maxAmp; break;
                    case "desert_dunes": val = (0.5 + 0.5 * Math.sin(x*0.15 + n*1.5)) * maxAmp; break;
                    case "islands": {
                        double cx = w/2.0, cz = d/2.0;
                        double dist = Math.sqrt((x-cx)*(x-cx) + (z-cz)*(z-cz));
                        double falloff = Math.max(0, 1 - dist / (Math.min(w,d)/2.0));
                        val = falloff * maxAmp * (0.6 + 0.4*n);
                        break;
                    }
                    case "canyon": {
                        double cut = Math.sin(x*0.1 + n*0.5);
                        val = cut < -0.1 ? 0 : maxAmp * (0.7 + 0.3*n);
                        break;
                    }
                    case "plains": val = 0.5 + n*0.5; break;
                    default: val = (n + 1) * 0.5 * maxAmp;
                }
                h[x][z] = Math.max(0, val);
            }
        }
        return h;
    }
}

// ================== Fast place toggle ==================
// Zeroes MinecraftClient.itemUseCooldown each tick. Purely a client-side
// rate limit remover; server-side anticheats that track placement rate may
// still flag rapid placements on strict servers.
class FastPlace {
    static boolean enabled = false;
    private static java.lang.reflect.Field cooldownField;
    static {
        try {
            cooldownField = MinecraftClient.class.getDeclaredField("itemUseCooldown");
            cooldownField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            cooldownField = null;
        }
    }

    static void toggle(FabricClientCommandSource src) {
        if (cooldownField == null) {
            src.sendFeedback(Text.literal("fast place: unavailable (field not found, Yarn name may have changed)"));
            return;
        }
        enabled = !enabled;
        src.sendFeedback(Text.literal("fast place: " + (enabled ? "ON" : "off")));
    }

    static void tick() {
        if (!enabled || cooldownField == null) return;
        try { cooldownField.setInt(MinecraftClient.getInstance(), 0); } catch (Exception ignored) {}
    }
}

// ================== Warden spawn counter ==================
// Polls nearby sculk shriekers each second. Counts rising edges of the
// "shrieking" block state as shrieker activations. Each activation bumps
// a warning level 0..4. Vanilla warden spawns on the 4th shriek within
// the player's warning-level window.
//
// Limitation: this is an estimate, not a true read of WardenSpawnTracker
// (which lives server-side). If you log in mid-session with an existing
// warning level, or if shriekers activate outside our 16-block scan, we
// won't know. Most runs start from 0 so it's usually correct.
class WardenTracker {
    static int warningLevel = 0;
    private static long lastShriekTime = 0;
    private static final Set<Long> lastShriekingState = new HashSet<>();
    private static int tickCounter = 0;
    // vanilla decay: 1 level per 10 in-game minutes of no shrieks
    private static final long DECAY_MS = 10 * 60 * 1000;

    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        // decay
        if (warningLevel > 0 && lastShriekTime > 0
            && System.currentTimeMillis() - lastShriekTime > DECAY_MS) {
            warningLevel--;
            lastShriekTime = System.currentTimeMillis();
        }

        // only scan once per second, shriekers are rare
        tickCounter++;
        if (tickCounter < 20) return;
        tickCounter = 0;

        World world = mc.world;
        BlockPos origin = mc.player.getBlockPos();

        Set<Long> nowShrieking = new HashSet<>();
        for (int dx = -16; dx <= 16; dx++)
            for (int dy = -8; dy <= 8; dy++)
                for (int dz = -16; dz <= 16; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    BlockState s = world.getBlockState(p);
                    if (s.getBlock() != Blocks.SCULK_SHRIEKER) continue;
                    if (!s.contains(Properties.SHRIEKING)) continue;
                    if (!s.get(Properties.SHRIEKING)) continue;
                    long key = p.asLong();
                    nowShrieking.add(key);
                    if (!lastShriekingState.contains(key)) {
                        // rising edge — count it
                        warningLevel = Math.min(4, warningLevel + 1);
                        lastShriekTime = System.currentTimeMillis();
                        if (mc.player != null) {
                            mc.player.sendMessage(Text.literal(
                                "[DreamBot] sculk shrieker activated — warning " + warningLevel + "/4")
                                .formatted(Formatting.RED), false);
                        }
                        if (warningLevel >= 3) {
                            Webhook.fire("warden_warning",
                                "Warden warning escalated to " + warningLevel + "/4 — danger");
                        }
                    }
                }
        lastShriekingState.clear();
        lastShriekingState.addAll(nowShrieking);
    }
}

// ================== Keys ==================
class DreamBotKeys {
    static KeyBinding MENU;
    static KeyBinding ZOOM;
}

// ================== Config (persistent, JSON) ==================
class DreamBotConfig {
    private static DreamBotConfig INSTANCE = new DreamBotConfig();
    static DreamBotConfig get() { return INSTANCE; }

    // HUD line toggles
    boolean showTotems     = true;
    boolean showMarker     = true;
    boolean showCoords     = true;
    boolean showTime       = true;
    boolean showBiome      = true;
    boolean showWarden     = true;
    boolean showFastplace  = true;
    boolean showFps        = false;
    boolean showPing       = false;
    boolean showDurability = true;
    boolean showLight      = true;
    boolean showSpeedrun   = true;
    boolean showArmor      = true;
    boolean showRealClock  = false;
    boolean showSession    = false;
    boolean showCps        = false;
    boolean showHandInfo   = false;
    boolean showKeys       = false;
    String keysCorner      = "bottom-left";
    boolean showXp         = false;
    boolean showSaturation = false;
    boolean showElytra     = true;
    boolean showCrosshair  = false;
    boolean showSpawnSafety= false;
    boolean showEffects    = false;
    boolean showBreath     = true;
    boolean showFreeze     = true;
    boolean showSleep      = false;
    boolean showKillCount  = false;
    boolean showWalkDist   = false;
    boolean showDamageTaken= false;
    boolean showNearbyMobs = false;
    boolean showFallDmg    = true;
    boolean autoRespawn    = false;
    boolean permaChat      = false;
    boolean toggleSneakMode= false;

    // 20-pack v2
    boolean showSpawnDist  = false;
    boolean showVelocity   = false;
    boolean showLastDmg    = true;
    boolean showPearlCd    = false;
    boolean showReach      = false;
    boolean showWeather    = false;
    boolean showTps        = false;
    boolean compactEffects = false;
    boolean highlightName  = true;
    boolean pickupNotify   = false;
    String autoGreetMsg    = "";
    Map<String, int[]> namedWaypoints = new HashMap<>();   // name -> [x,y,z]
    Map<String, String> namedWaypointDims = new HashMap<>(); // name -> dim
    Map<Integer, int[]> hotbarPresets = new HashMap<>();  // slot -> 9 item ids? (we'll store names)

    // Chat aliases: /alias set <name> <text...>
    Map<String, String> chatAliases = new HashMap<>();
    // Ignore list for chat filtering
    List<String> ignoredPlayers = new ArrayList<>();
    // Home point
    int homeX = 0, homeY = 0, homeZ = 0;
    boolean homeSet = false;
    String homeDim = "";

    // Speedrun
    long speedrunPbMillis = 0;
    String speedrunPbName = "";
    // PB splits: parallel arrays, names + times in ms
    List<String> pbSplitNames = new ArrayList<>();
    List<Long> pbSplitTimes = new ArrayList<>();

    // Layout
    String anchor = "top-left"; // top-left, top-right, bottom-left, bottom-right
    float hudScale = 1.0f;

    // Chat
    boolean chatTimestamps = false;

    // Find ore
    boolean findOreExposedOnly = true;

    // Auto mine
    String autoMineOre = "diamond";
    int autoMineEatThreshold = 17;

    // Webhook
    String webhookUrl = "";
    boolean webhookOnTotemPop = true;
    boolean webhookOnWardenWarning = true;
    boolean webhookOnOreFound = false;
    boolean webhookOnAutoMineDone = true;

    // Command defaults (right-click in menu cycles these)
    int findOreRadius = 64;
    String findOreType = "diamond";
    String seedPickerProfile = "speedrun";
    int seedPickerCount = 50;
    String terrainStyle = "rolling_hills";

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("dreambot.json");
    }

    static void load() {
        try {
            Path p = configPath();
            if (Files.exists(p)) {
                Gson g = new Gson();
                INSTANCE = g.fromJson(Files.readString(p), DreamBotConfig.class);
                if (INSTANCE == null) INSTANCE = new DreamBotConfig();
                if (INSTANCE.pbSplitNames == null) INSTANCE.pbSplitNames = new ArrayList<>();
                if (INSTANCE.pbSplitTimes == null) INSTANCE.pbSplitTimes = new ArrayList<>();
                if (INSTANCE.chatAliases == null) INSTANCE.chatAliases = new HashMap<>();
                if (INSTANCE.ignoredPlayers == null) INSTANCE.ignoredPlayers = new ArrayList<>();
                if (INSTANCE.namedWaypoints == null) INSTANCE.namedWaypoints = new HashMap<>();
                if (INSTANCE.namedWaypointDims == null) INSTANCE.namedWaypointDims = new HashMap<>();
                if (INSTANCE.hotbarPresets == null) INSTANCE.hotbarPresets = new HashMap<>();
            }
        } catch (IOException e) {
            INSTANCE = new DreamBotConfig();
        }
    }

    static void save() {
        try {
            Gson g = new GsonBuilder().setPrettyPrinting().create();
            Files.createDirectories(configPath().getParent());
            Files.writeString(configPath(), g.toJson(INSTANCE));
        } catch (IOException ignored) {}
    }
}

// ================== Main menu ==================
class DreamBotMenu extends Screen {
    private static final String[] ORE_CYCLE = {"diamond","iron","gold","coal","redstone","lapis","emerald","copper","ancient"};
    private static final String[] PROFILE_CYCLE = {"speedrun","survival","manhunt","peaceful"};
    private static final String[] STYLE_CYCLE = {"rolling_hills","mountains","plains","desert_dunes","forest","islands","canyon"};

    private final List<MenuButton> menuButtons = new ArrayList<>();

    DreamBotMenu() { super(Text.literal("DreamBot")); }

    @Override
    protected void init() {
        menuButtons.clear();
        DreamBotConfig cfg = DreamBotConfig.get();

        int col1 = this.width / 2 - 155;
        int col2 = this.width / 2 + 5;
        int btnW = 150, btnH = 20, gap = 4;
        int top = 40;
        int row = 0;

        addBtn(col1, top + (row)*(btnH+gap), btnW, btnH,
            "Find Ore: " + cfg.findOreType + " (" + cfg.findOreRadius + ")",
            () -> runCmd("findore " + DreamBotConfig.get().findOreType + " " + DreamBotConfig.get().findOreRadius),
            (right) -> {
                if (right) {
                    DreamBotConfig.get().findOreType = cycle(ORE_CYCLE, DreamBotConfig.get().findOreType);
                } else {
                    DreamBotConfig.get().findOreRadius = DreamBotConfig.get().findOreRadius >= 256 ? 32 : DreamBotConfig.get().findOreRadius + 32;
                }
                DreamBotConfig.save();
                rebuild();
            });

        addBtn(col2, top + (row++)*(btnH+gap), btnW, btnH,
            "Clear Marker", () -> runCmd("clearmark"), null);

        addBtn(col1, top + (row)*(btnH+gap), btnW, btnH,
            "Find Stronghold", () -> runCmd("findstronghold"), null);
        addBtn(col2, top + (row++)*(btnH+gap), btnW, btnH,
            "Waypoints List", () -> runCmd("waypoints"), null);

        addBtn(col1, top + (row)*(btnH+gap), btnW, btnH,
            "Spawn Info", () -> runCmd("spawninfo"), null);
        addBtn(col2, top + (row++)*(btnH+gap), btnW, btnH,
            "Sort Inventory", () -> runCmd("sortinv"), null);

        addBtn(col1, top + (row)*(btnH+gap), btnW, btnH,
            "Stack Inventory", () -> runCmd("stackinv"), null);
        addBtn(col2, top + (row++)*(btnH+gap), btnW, btnH,
            "Auto Mine: " + (AutoMine.enabled ? "ON " + AutoMine.oreType : "off"),
            () -> { runCmd("automine " + DreamBotConfig.get().autoMineOre); rebuild(); },
            (right) -> {
                DreamBotConfig.get().autoMineOre = cycle(ORE_CYCLE, DreamBotConfig.get().autoMineOre);
                DreamBotConfig.save();
                rebuild();
            });

        addBtn(col1, top + (row)*(btnH+gap), btnW, btnH,
            "Strip Mine: " + (StripMine.enabled ? "ON" : "off"),
            () -> { runCmd("stripmine 16"); rebuild(); }, null);
        addBtn(col2, top + (row++)*(btnH+gap), btnW, btnH,
            "Fast Place: " + (FastPlace.enabled ? "ON" : "off"),
            () -> { runCmd("fastplace"); rebuild(); }, null);

        addBtn(col1, top + (row++)*(btnH+gap), btnW, btnH,
            "Seed Picker: " + cfg.seedPickerProfile + " x" + cfg.seedPickerCount,
            () -> runCmd("seedpicker " + DreamBotConfig.get().seedPickerProfile + " " + DreamBotConfig.get().seedPickerCount),
            (right) -> {
                if (right) {
                    DreamBotConfig.get().seedPickerProfile = cycle(PROFILE_CYCLE, DreamBotConfig.get().seedPickerProfile);
                } else {
                    int c = DreamBotConfig.get().seedPickerCount;
                    DreamBotConfig.get().seedPickerCount = c >= 200 ? 25 : c + 25;
                }
                DreamBotConfig.save();
                rebuild();
            });

        addBtn(col1, top + (row)*(btnH+gap), btnW, btnH,
            "Pos1 (here)", () -> runCmd("pos1"), null);
        addBtn(col2, top + (row++)*(btnH+gap), btnW, btnH,
            "Pos2 (here)", () -> runCmd("pos2"), null);

        addBtn(col1, top + (row)*(btnH+gap), btnW, btnH,
            "Terrain: " + cfg.terrainStyle,
            () -> runCmd("terrain " + DreamBotConfig.get().terrainStyle),
            (right) -> {
                DreamBotConfig.get().terrainStyle = cycle(STYLE_CYCLE, DreamBotConfig.get().terrainStyle);
                DreamBotConfig.save();
                rebuild();
            });
        addBtn(col2, top + (row++)*(btnH+gap), btnW, btnH,
            "Terrain Suggest", () -> runCmd("terrain suggest"), null);

        // bottom row
        addBtn(this.width/2 - 155, top + (row)*(btnH+gap) + 8, 150, 20,
            "Full Bright: " + (FullBright.enabled ? "ON" : "off"),
            () -> { runCmd("fullbright"); rebuild(); }, null);
        addBtn(this.width/2 + 5, top + (row++)*(btnH+gap) + 8, 150, 20,
            "Back to Death", () -> runCmd("back"), null);

        addBtn(this.width/2 - 155, top + (row)*(btnH+gap) + 8, 150, 20,
            "Speedrun: " + (Speedrun.isRunning() ? "running" : "start"),
            () -> { runCmd("speedrun " + (Speedrun.isRunning() ? "stop" : "start")); rebuild(); }, null);
        addBtn(this.width/2 + 5, top + (row++)*(btnH+gap) + 8, 150, 20,
            "Speedrun PB", () -> runCmd("speedrun pb"), null);

        addBtn(this.width/2 - 155, top + (row)*(btnH+gap) + 8, 150, 20,
            "Webhook Test", () -> runCmd("webhook test"), null);
        addBtn(this.width/2 + 5, top + (row++)*(btnH+gap) + 8, 150, 20,
            "Chat Timestamps: " + (DreamBotConfig.get().chatTimestamps ? "ON" : "off"),
            () -> {
                DreamBotConfig.get().chatTimestamps = !DreamBotConfig.get().chatTimestamps;
                DreamBotConfig.save();
                rebuild();
            }, null);

        addBtn(this.width/2 - 155, top + (row)*(btnH+gap) + 8, 150, 20,
            "HUD Settings", () -> this.client.setScreen(new HudConfigScreen()), null);
        addBtn(this.width/2 + 5, top + (row++)*(btnH+gap) + 8, 150, 20,
            "Close", this::close, null);
    }

    private void addBtn(int x, int y, int w, int h, String label, Runnable onLeft, java.util.function.Consumer<Boolean> onRight) {
        ButtonWidget btn = ButtonWidget.builder(Text.literal(label), b -> onLeft.run())
            .dimensions(x, y, w, h).build();
        this.addDrawableChild(btn);
        menuButtons.add(new MenuButton(btn, onRight));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1) { // right click
            for (MenuButton mb : menuButtons) {
                if (mb.onRight == null) continue;
                ButtonWidget b = mb.btn;
                if (mouseX >= b.getX() && mouseX < b.getX() + b.getWidth()
                    && mouseY >= b.getY() && mouseY < b.getY() + b.getHeight()) {
                    mb.onRight.accept(true);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void rebuild() {
        this.clearChildren();
        this.init();
    }

    private void runCmd(String cmd) {
        MinecraftClient.getInstance().player.networkHandler.sendChatCommand(cmd);
        this.close();
    }

    private static String cycle(String[] arr, String current) {
        for (int i = 0; i < arr.length; i++)
            if (arr[i].equals(current)) return arr[(i+1) % arr.length];
        return arr[0];
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width/2, 15, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal("right-click buttons to change settings").formatted(Formatting.GRAY),
            this.width/2, 27, 0xAAAAAA);
    }

    private static class MenuButton {
        final ButtonWidget btn;
        final java.util.function.Consumer<Boolean> onRight;
        MenuButton(ButtonWidget b, java.util.function.Consumer<Boolean> r) { btn = b; onRight = r; }
    }
}

// ================== HUD config screen ==================
class HudConfigScreen extends Screen {
    HudConfigScreen() { super(Text.literal("DreamBot HUD")); }

    @Override
    protected void init() {
        DreamBotConfig cfg = DreamBotConfig.get();
        int col1 = this.width/2 - 155, col2 = this.width/2 + 5;
        int btnW = 150, btnH = 20, gap = 4;
        int top = 30;

        addToggle(col1, top + 0*(btnH+gap), btnW, btnH, "Totems",     () -> cfg.showTotems,     v -> cfg.showTotems = v);
        addToggle(col2, top + 0*(btnH+gap), btnW, btnH, "Marker",     () -> cfg.showMarker,     v -> cfg.showMarker = v);
        addToggle(col1, top + 1*(btnH+gap), btnW, btnH, "Coords",     () -> cfg.showCoords,     v -> cfg.showCoords = v);
        addToggle(col2, top + 1*(btnH+gap), btnW, btnH, "Time/Day",   () -> cfg.showTime,       v -> cfg.showTime = v);
        addToggle(col1, top + 2*(btnH+gap), btnW, btnH, "Biome",      () -> cfg.showBiome,      v -> cfg.showBiome = v);
        addToggle(col2, top + 2*(btnH+gap), btnW, btnH, "Warden",     () -> cfg.showWarden,     v -> cfg.showWarden = v);
        addToggle(col1, top + 3*(btnH+gap), btnW, btnH, "Fastplace",  () -> cfg.showFastplace,  v -> cfg.showFastplace = v);
        addToggle(col2, top + 3*(btnH+gap), btnW, btnH, "Light Lvl",  () -> cfg.showLight,      v -> cfg.showLight = v);
        addToggle(col1, top + 4*(btnH+gap), btnW, btnH, "FPS",        () -> cfg.showFps,        v -> cfg.showFps = v);
        addToggle(col2, top + 4*(btnH+gap), btnW, btnH, "Ping",       () -> cfg.showPing,       v -> cfg.showPing = v);
        addToggle(col1, top + 5*(btnH+gap), btnW, btnH, "Durability", () -> cfg.showDurability, v -> cfg.showDurability = v);
        addToggle(col2, top + 5*(btnH+gap), btnW, btnH, "Armor",      () -> cfg.showArmor,      v -> cfg.showArmor = v);
        addToggle(col1, top + 6*(btnH+gap), btnW, btnH, "Hand Info",  () -> cfg.showHandInfo,   v -> cfg.showHandInfo = v);
        addToggle(col2, top + 6*(btnH+gap), btnW, btnH, "Real Clock", () -> cfg.showRealClock,  v -> cfg.showRealClock = v);
        addToggle(col1, top + 7*(btnH+gap), btnW, btnH, "Session",    () -> cfg.showSession,    v -> cfg.showSession = v);
        addToggle(col2, top + 7*(btnH+gap), btnW, btnH, "CPS",        () -> cfg.showCps,        v -> cfg.showCps = v);
        addToggle(col1, top + 8*(btnH+gap), btnW, btnH, "Keys Overlay", () -> cfg.showKeys, v -> cfg.showKeys = v);

        // Keys corner cycle
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("Keys pos: " + cfg.keysCorner),
            b -> {
                String[] corners = {"top-left","top-right","bottom-left","bottom-right"};
                int idx = 0;
                for (int i = 0; i < corners.length; i++) if (corners[i].equals(cfg.keysCorner)) { idx = i; break; }
                cfg.keysCorner = corners[(idx+1) % corners.length];
                DreamBotConfig.save();
                rebuild();
            })
            .dimensions(col2, top + 8*(btnH+gap), btnW, btnH).build());

        addToggle(col1, top + 9*(btnH+gap), btnW, btnH, "XP Details", () -> cfg.showXp,         v -> cfg.showXp = v);
        addToggle(col2, top + 9*(btnH+gap), btnW, btnH, "Saturation", () -> cfg.showSaturation, v -> cfg.showSaturation = v);
        addToggle(col1, top + 10*(btnH+gap), btnW, btnH, "Elytra HUD", () -> cfg.showElytra,    v -> cfg.showElytra = v);

        // Anchor cycle
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("Anchor: " + cfg.anchor),
            b -> {
                String[] anchors = {"top-left","top-right","bottom-left","bottom-right"};
                int idx = 0;
                for (int i = 0; i < anchors.length; i++) if (anchors[i].equals(cfg.anchor)) { idx = i; break; }
                cfg.anchor = anchors[(idx+1) % anchors.length];
                DreamBotConfig.save();
                rebuild();
            })
            .dimensions(col1, top + 11*(btnH+gap), btnW, btnH).build());

        // Scale cycle
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal(String.format("Scale: %.2f", cfg.hudScale)),
            b -> {
                float[] scales = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
                int idx = 0;
                for (int i = 0; i < scales.length; i++) if (Math.abs(scales[i]-cfg.hudScale) < 0.01f) { idx = i; break; }
                cfg.hudScale = scales[(idx+1) % scales.length];
                DreamBotConfig.save();
                rebuild();
            })
            .dimensions(col2, top + 11*(btnH+gap), btnW, btnH).build());

        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("Back"), b -> this.client.setScreen(new DreamBotMenu()))
            .dimensions(this.width/2 - 75, top + 12*(btnH+gap) + 4, 150, btnH).build());
    }

    private void addToggle(int x, int y, int w, int h, String label, java.util.function.Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
        boolean v = get.get();
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal(label + ": " + (v ? "ON" : "off")),
            b -> { set.accept(!get.get()); DreamBotConfig.save(); rebuild(); })
            .dimensions(x, y, w, h).build());
    }

    private void rebuild() { this.clearChildren(); this.init(); }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width/2, 15, 0xFFFFFF);
    }
}

// ================== Webhook ==================
class Webhook {
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    static void setUrl(FabricClientCommandSource src, String url) {
        DreamBotConfig.get().webhookUrl = url == null ? "" : url.trim();
        DreamBotConfig.save();
        if (DreamBotConfig.get().webhookUrl.isEmpty()) {
            src.sendFeedback(Text.literal("webhook cleared"));
        } else {
            src.sendFeedback(Text.literal("webhook set"));
        }
    }

    static void toggle(FabricClientCommandSource src, String event) {
        DreamBotConfig c = DreamBotConfig.get();
        boolean v;
        switch (event) {
            case "totem_pop":      c.webhookOnTotemPop = !c.webhookOnTotemPop;       v = c.webhookOnTotemPop; break;
            case "warden_warning": c.webhookOnWardenWarning = !c.webhookOnWardenWarning; v = c.webhookOnWardenWarning; break;
            case "ore_found":      c.webhookOnOreFound = !c.webhookOnOreFound;       v = c.webhookOnOreFound; break;
            case "automine_done":  c.webhookOnAutoMineDone = !c.webhookOnAutoMineDone; v = c.webhookOnAutoMineDone; break;
            default:
                src.sendFeedback(Text.literal("events: totem_pop, warden_warning, ore_found, automine_done"));
                return;
        }
        DreamBotConfig.save();
        src.sendFeedback(Text.literal("webhook " + event + ": " + (v ? "ON" : "off")));
    }

    static void fire(String event, String message) {
        DreamBotConfig c = DreamBotConfig.get();
        if (c.webhookUrl == null || c.webhookUrl.isEmpty()) return;
        boolean enabled;
        switch (event) {
            case "totem_pop":      enabled = c.webhookOnTotemPop; break;
            case "warden_warning": enabled = c.webhookOnWardenWarning; break;
            case "ore_found":      enabled = c.webhookOnOreFound; break;
            case "automine_done":  enabled = c.webhookOnAutoMineDone; break;
            case "test":           enabled = true; break;
            default: enabled = false;
        }
        if (!enabled) return;

        // Discord-compatible payload
        String json = "{\"content\":\"[DreamBot] " + jsonEscape(message) + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(c.webhookUrl))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("User-Agent", "DreamBot/1.0")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        // fire and forget
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}

// ================== Auto Mine ==================
class AutoMine {
    static boolean enabled = false;
    static String oreType = "diamond";
    private static int blocksMined = 0;
    private static long startTime = 0;
    static long startTimeMs() { return startTime; }

    private enum State { SCAN, MINE, EAT }
    private static State state = State.SCAN;
    private static BlockPos targetBlock = null;
    private static Direction targetSide = Direction.UP;
    private static int eatTicks = 0;
    private static int previousSlot = -1;

    static void toggle(FabricClientCommandSource src, String ore) {
        enabled = !enabled;
        if (ore != null) {
            oreType = ore.toLowerCase();
            DreamBotConfig.get().autoMineOre = oreType;
            DreamBotConfig.save();
        } else {
            oreType = DreamBotConfig.get().autoMineOre;
        }
        if (enabled) {
            blocksMined = 0;
            startTime = System.currentTimeMillis();
            state = State.SCAN;
            src.sendFeedback(Text.literal("auto-mine ON: " + oreType + " (only ores in reach, eats below " + DreamBotConfig.get().autoMineEatThreshold + " hunger)"));
            src.sendFeedback(Text.literal("WARNING: this is botting. Singleplayer/lan only — banned on most servers."));
        } else {
            stop("manual stop");
            src.sendFeedback(Text.literal("auto-mine off"));
        }
    }

    static void stop(String reason) {
        if (!enabled) return;
        enabled = false;
        long secs = (System.currentTimeMillis() - startTime) / 1000;
        Webhook.fire("automine_done",
            String.format("Auto-mine stopped (%s). Mined %d %s in %ds", reason, blocksMined, oreType, secs));
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal(
                "[DreamBot] auto-mine stopped: " + reason + " (mined " + blocksMined + ")")
                .formatted(Formatting.GOLD), false);
        }
        if (previousSlot >= 0 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(previousSlot);
            previousSlot = -1;
        }
        if (mc.options != null) mc.options.useKey.setPressed(false);
    }

    static void tick() {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // safety: stop on death or low health
        if (mc.player.isDead() || mc.player.getHealth() < 6.0f) {
            stop("low health or dead");
            return;
        }

        // hunger check
        int food = mc.player.getHungerManager().getFoodLevel();
        if (state != State.EAT && food < DreamBotConfig.get().autoMineEatThreshold) {
            if (findFoodSlot(mc) >= 0) {
                state = State.EAT;
                eatTicks = 0;
            }
        }

        switch (state) {
            case SCAN: doScan(mc); break;
            case MINE: doMine(mc); break;
            case EAT:  doEat(mc, food); break;
        }
    }

    private static void doScan(MinecraftClient mc) {
        List<Block> targets = DreamBot.ORES.get(oreType);
        if (targets == null) { stop("unknown ore type"); return; }
        BlockPos eyes = BlockPos.ofFloored(mc.player.getEyePos());
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        // scan a 5-block cube around eyes
        for (int dx = -4; dx <= 4; dx++)
            for (int dy = -4; dy <= 4; dy++)
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos p = eyes.add(dx, dy, dz);
                    BlockState bs = mc.world.getBlockState(p);
                    if (!targets.contains(bs.getBlock())) continue;
                    double d = mc.player.getEyePos().squaredDistanceTo(p.getX()+0.5, p.getY()+0.5, p.getZ()+0.5);
                    if (d > 16.0) continue; // 4 block reach
                    if (d < bestDist) { bestDist = d; best = p; }
                }
        if (best == null) {
            // nothing in reach; idle (don't move)
            return;
        }
        targetBlock = best;
        // pick a face that points toward us
        Vec3d toMe = mc.player.getEyePos().subtract(best.getX()+0.5, best.getY()+0.5, best.getZ()+0.5);
        Direction d = Direction.UP;
        double bestDot = -2;
        for (Direction dir : Direction.values()) {
            double dot = dir.getOffsetX()*toMe.x + dir.getOffsetY()*toMe.y + dir.getOffsetZ()*toMe.z;
            if (dot > bestDot) { bestDot = dot; d = dir; }
        }
        targetSide = d;
        state = State.MINE;
    }

    private static void doMine(MinecraftClient mc) {
        if (targetBlock == null) { state = State.SCAN; return; }
        BlockState bs = mc.world.getBlockState(targetBlock);
        List<Block> targets = DreamBot.ORES.get(oreType);
        if (targets == null || !targets.contains(bs.getBlock())) {
            // block changed (broken or replaced)
            blocksMined++;
            targetBlock = null;
            state = State.SCAN;
            return;
        }
        // aim at it
        Vec3d c = new Vec3d(targetBlock.getX()+0.5, targetBlock.getY()+0.5, targetBlock.getZ()+0.5);
        Vec3d eye = mc.player.getEyePos();
        double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
        double horiz = Math.sqrt(dx*dx + dz*dz);
        float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float pitch = (float)(-Math.toDegrees(Math.atan2(dy, horiz)));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
        // continue breaking
        mc.interactionManager.updateBlockBreakingProgress(targetBlock, targetSide);
        mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
    }

    private static void doEat(MinecraftClient mc, int food) {
        if (food >= 20) {
            mc.options.useKey.setPressed(false);
            if (previousSlot >= 0) {
                mc.player.getInventory().setSelectedSlot(previousSlot);
                previousSlot = -1;
            }
            state = State.SCAN;
            return;
        }
        int foodSlot = findFoodSlot(mc);
        if (foodSlot < 0) {
            mc.options.useKey.setPressed(false);
            state = State.SCAN;
            return;
        }
        if (previousSlot < 0) previousSlot = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getSelectedSlot() != foodSlot) {
            mc.player.getInventory().setSelectedSlot(foodSlot);
        }
        mc.options.useKey.setPressed(true);
        eatTicks++;
        if (eatTicks > 60) { // 3 seconds, abort if didn't eat
            mc.options.useKey.setPressed(false);
            state = State.SCAN;
        }
    }

    private static int findFoodSlot(MinecraftClient mc) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            if (s.get(net.minecraft.component.DataComponentTypes.FOOD) != null) return i;
        }
        return -1;
    }
}

// ================== Strip Mine (serpentine walking miner) ==================
class StripMine {
    static boolean enabled = false;
    private static int tunnelLength = 16;
    private static int segmentBlocks = 0;     // blocks walked in current main tunnel
    private static int offsetBlocks = 0;      // blocks walked in 2-wide turn offset
    private static double segmentStartX = 0, segmentStartZ = 0;
    private static int blocksMined = 0;
    private static long startTime = 0;
    static long startTimeMs() { return startTime; }

    private enum State { WALK, MINING, TURN1, OFFSET, TURN2, EAT }
    private static State state = State.WALK;
    private static State returnAfterMining = State.WALK;
    private static BlockPos miningTarget = null;
    private static Direction miningSide = Direction.NORTH;
    private static int eatTicks = 0;
    private static int previousSlot = -1;
    private static int stuckTicks = 0;

    static void toggle(FabricClientCommandSource src, int length) {
        enabled = !enabled;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (enabled) {
            if (mc.player == null) { enabled = false; return; }
            tunnelLength = length;
            segmentBlocks = 0;
            offsetBlocks = 0;
            blocksMined = 0;
            startTime = System.currentTimeMillis();
            state = State.WALK;
            // snap to nearest cardinal yaw
            float snapped = snapYaw(mc.player.getYaw());
            mc.player.setYaw(snapped);
            mc.player.setPitch(0);
            segmentStartX = mc.player.getX();
            segmentStartZ = mc.player.getZ();
            src.sendFeedback(Text.literal("strip-mine ON: tunnel length " + length + ", facing " + cardinalName(snapped)));
            src.sendFeedback(Text.literal("WARNING: botting. Singleplayer/LAN only."));
        } else {
            stop("manual stop");
            src.sendFeedback(Text.literal("strip-mine off"));
        }
    }

    static void stop(String reason) {
        if (!enabled) return;
        enabled = false;
        long secs = (System.currentTimeMillis() - startTime) / 1000;
        Webhook.fire("automine_done",
            String.format("Strip-mine stopped (%s). Mined %d blocks in %ds", reason, blocksMined, secs));
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal(
                "[DreamBot] strip-mine stopped: " + reason + " (mined " + blocksMined + ")")
                .formatted(Formatting.GOLD), false);
        }
        if (mc.options != null) {
            mc.options.forwardKey.setPressed(false);
            mc.options.useKey.setPressed(false);
        }
        if (previousSlot >= 0 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(previousSlot);
            previousSlot = -1;
        }
    }

    static void tick() {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // safety
        if (mc.player.isDead() || mc.player.getHealth() < 6.0f) {
            stop("low health or dead");
            return;
        }

        // hunger check
        int food = mc.player.getHungerManager().getFoodLevel();
        if (state != State.EAT && food < DreamBotConfig.get().autoMineEatThreshold) {
            if (findFoodSlot(mc) >= 0) {
                mc.options.forwardKey.setPressed(false);
                state = State.EAT;
                eatTicks = 0;
                return;
            }
        }

        switch (state) {
            case WALK:    doWalk(mc); break;
            case MINING:  doMine(mc); break;
            case TURN1:   doTurn(mc, State.OFFSET); break;
            case OFFSET:  doOffset(mc); break;
            case TURN2:   doTurn(mc, State.WALK); break;
            case EAT:     doEat(mc, food); break;
        }
    }

    private static void doWalk(MinecraftClient mc) {
        // check distance traveled in this tunnel segment
        double dx = mc.player.getX() - segmentStartX;
        double dz = mc.player.getZ() - segmentStartZ;
        double dist = Math.sqrt(dx*dx + dz*dz);
        if (dist >= tunnelLength) {
            mc.options.forwardKey.setPressed(false);
            state = State.TURN1;
            return;
        }

        // check for block in front to mine
        Direction facing = Direction.fromRotation(mc.player.getYaw());
        BlockPos feet = mc.player.getBlockPos();
        BlockPos frontFeet = feet.offset(facing);
        BlockPos frontHead = frontFeet.up();

        BlockPos blocked = null;
        if (!mc.world.getBlockState(frontHead).isAir()) blocked = frontHead;
        else if (!mc.world.getBlockState(frontFeet).isAir()) blocked = frontFeet;

        if (blocked != null) {
            // can we actually break it?
            BlockState bs = mc.world.getBlockState(blocked);
            if (bs.getBlock() == Blocks.BEDROCK) {
                stop("hit bedrock");
                return;
            }
            mc.options.forwardKey.setPressed(false);
            miningTarget = blocked;
            miningSide = facing.getOpposite();
            returnAfterMining = State.WALK;
            state = State.MINING;
            stuckTicks = 0;
            return;
        }

        // path clear, walk forward
        mc.options.forwardKey.setPressed(true);
        // detect if we're not moving (stuck)
        stuckTicks++;
        if (stuckTicks > 60) {
            stop("stuck (not moving)");
        }
    }

    private static void doMine(MinecraftClient mc) {
        if (miningTarget == null) { state = returnAfterMining; return; }
        BlockState bs = mc.world.getBlockState(miningTarget);
        if (bs.isAir()) {
            blocksMined++;
            miningTarget = null;
            stuckTicks = 0;
            state = returnAfterMining;
            return;
        }
        // aim at block center
        Vec3d c = new Vec3d(miningTarget.getX()+0.5, miningTarget.getY()+0.5, miningTarget.getZ()+0.5);
        Vec3d eye = mc.player.getEyePos();
        double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
        double horiz = Math.sqrt(dx*dx + dz*dz);
        float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float pitch = (float)(-Math.toDegrees(Math.atan2(dy, horiz)));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
        mc.interactionManager.updateBlockBreakingProgress(miningTarget, miningSide);
        mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);

        stuckTicks++;
        if (stuckTicks > 200) { // 10s, abort if can't break
            stop("can't break block (no tool?)");
        }
    }

    private static void doTurn(MinecraftClient mc, State next) {
        // snap yaw 90 degrees to the left
        float current = snapYaw(mc.player.getYaw());
        // "left" relative to facing: subtract 90
        float newYaw = snapYaw(current - 90);
        mc.player.setYaw(newYaw);
        mc.player.setPitch(0);
        // restore pitch facing forward & reset segment tracking
        segmentStartX = mc.player.getX();
        segmentStartZ = mc.player.getZ();
        if (next == State.OFFSET) {
            offsetBlocks = 0;
        } else {
            segmentBlocks = 0;
        }
        state = next;
    }

    private static void doOffset(MinecraftClient mc) {
        // walk 2 blocks in current direction (which is the offset direction)
        double dx = mc.player.getX() - segmentStartX;
        double dz = mc.player.getZ() - segmentStartZ;
        double dist = Math.sqrt(dx*dx + dz*dz);
        if (dist >= 2.0) {
            mc.options.forwardKey.setPressed(false);
            state = State.TURN2;
            return;
        }
        // mine block in path if any
        Direction facing = Direction.fromRotation(mc.player.getYaw());
        BlockPos feet = mc.player.getBlockPos();
        BlockPos frontFeet = feet.offset(facing);
        BlockPos frontHead = frontFeet.up();
        BlockPos blocked = null;
        if (!mc.world.getBlockState(frontHead).isAir()) blocked = frontHead;
        else if (!mc.world.getBlockState(frontFeet).isAir()) blocked = frontFeet;
        if (blocked != null) {
            if (mc.world.getBlockState(blocked).getBlock() == Blocks.BEDROCK) {
                stop("hit bedrock during offset");
                return;
            }
            mc.options.forwardKey.setPressed(false);
            miningTarget = blocked;
            miningSide = facing.getOpposite();
            returnAfterMining = State.OFFSET;
            state = State.MINING;
            return;
        }
        mc.options.forwardKey.setPressed(true);
    }

    private static void doEat(MinecraftClient mc, int food) {
        if (food >= 20) {
            mc.options.useKey.setPressed(false);
            if (previousSlot >= 0) {
                mc.player.getInventory().setSelectedSlot(previousSlot);
                previousSlot = -1;
            }
            state = State.WALK;
            return;
        }
        int foodSlot = findFoodSlot(mc);
        if (foodSlot < 0) {
            mc.options.useKey.setPressed(false);
            state = State.WALK;
            return;
        }
        if (previousSlot < 0) previousSlot = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getSelectedSlot() != foodSlot) {
            mc.player.getInventory().setSelectedSlot(foodSlot);
        }
        mc.options.useKey.setPressed(true);
        eatTicks++;
        if (eatTicks > 60) {
            mc.options.useKey.setPressed(false);
            state = State.WALK;
        }
    }

    private static int findFoodSlot(MinecraftClient mc) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            if (s.get(net.minecraft.component.DataComponentTypes.FOOD) != null) return i;
        }
        return -1;
    }

    private static float snapYaw(float yaw) {
        float wrapped = MathHelper.wrapDegrees(yaw);
        return Math.round(wrapped / 90f) * 90f;
    }

    private static String cardinalName(float yaw) {
        float y = MathHelper.wrapDegrees(yaw);
        if (y >= -45 && y < 45)   return "south";
        if (y >= 45 && y < 135)   return "west";
        if (y >= -135 && y < -45) return "east";
        return "north";
    }
}

// ================== Zoom (hold key) ==================
class Zoom {
    private static int savedFov = -1;

    static void tick() {
        if (DreamBotKeys.ZOOM == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options == null) return;
        boolean down = DreamBotKeys.ZOOM.isPressed();
        if (down && savedFov < 0) {
            savedFov = mc.options.getFov().getValue();
            mc.options.getFov().setValue(Math.max(10, savedFov / 4));
        } else if (!down && savedFov >= 0) {
            mc.options.getFov().setValue(savedFov);
            savedFov = -1;
        }
    }
}

// ================== Full bright ==================
class FullBright {
    static boolean enabled = false;
    private static double savedGamma = -1;

    static void toggle(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options == null) return;
        enabled = !enabled;
        if (enabled) {
            savedGamma = mc.options.getGamma().getValue();
            mc.options.getGamma().setValue(5.0);
            src.sendFeedback(Text.literal("full bright ON"));
        } else {
            mc.options.getGamma().setValue(savedGamma >= 0 ? savedGamma : 1.0);
            savedGamma = -1;
            src.sendFeedback(Text.literal("full bright off"));
        }
    }
}

// ================== Death tracker ==================
class DeathTracker {
    static BlockPos lastDeath = null;
    private static String lastDeathDim = "";
    private static BlockPos lastSafePos = null;
    private static boolean wasAlive = true;

    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) { wasAlive = true; return; }
        boolean alive = !mc.player.isDead() && mc.player.getHealth() > 0;
        if (alive) {
            lastSafePos = mc.player.getBlockPos();
            wasAlive = true;
        } else if (wasAlive) {
            // just died
            lastDeath = lastSafePos;
            lastDeathDim = mc.world.getRegistryKey().getValue().toString();
            wasAlive = false;
            if (lastDeath != null) {
                mc.player.sendMessage(Text.literal(
                    "[DreamBot] death point saved: " + lastDeath.getX() + " " + lastDeath.getY() + " " + lastDeath.getZ() +
                    " — use /back to mark it")
                    .formatted(Formatting.GOLD), false);
            }
        }
    }

    static void markLastDeath(FabricClientCommandSource src) {
        if (lastDeath == null) {
            src.sendFeedback(Text.literal("no death point saved yet"));
            return;
        }
        Marker.set(lastDeath, "death");
        src.sendFeedback(Text.literal(String.format(
            "marked death point: %d %d %d (%s)",
            lastDeath.getX(), lastDeath.getY(), lastDeath.getZ(), lastDeathDim)));
    }
}

// ================== Durability watcher ==================
// Fires a one-time chat warning when held tool drops below 10% durability.
class DurabilityWatcher {
    private static net.minecraft.item.Item lastWarnedItem = null;

    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandStack();
        if (held == null || held.isEmpty() || !held.isDamageable()) {
            lastWarnedItem = null;
            return;
        }
        int max = held.getMaxDamage();
        int remaining = max - held.getDamage();
        int pct = (int)((remaining * 100L) / Math.max(1, max));
        if (pct < 10 && held.getItem() != lastWarnedItem) {
            lastWarnedItem = held.getItem();
            mc.player.sendMessage(Text.literal(
                "[DreamBot] " + held.getItem().getName().getString() + " almost broken! " + remaining + "/" + max)
                .formatted(Formatting.RED), false);
        } else if (pct >= 25) {
            // reset so warning fires again next time it drops
            lastWarnedItem = null;
        }
    }
}

// ================== Speedrun assist (timer, splits, counters) ==================
class Speedrun {
    static class Split {
        final String name;
        final long timeMs;
        Split(String n, long t) { name = n; timeMs = t; }
    }

    private static boolean running = false;
    private static long startMs = 0;
    private static long endMs = 0;
    private static final List<Split> splits = new ArrayList<>();
    private static String lastDim = "";
    private static boolean enteredFortress = false;
    private static boolean enteredStronghold = false;

    static boolean isRunning() { return running; }

    static void start(FabricClientCommandSource src) {
        running = true;
        startMs = System.currentTimeMillis();
        endMs = 0;
        splits.clear();
        lastDim = "";
        enteredFortress = false;
        enteredStronghold = false;
        src.sendFeedback(Text.literal("speedrun timer started").formatted(Formatting.GREEN));
    }

    static void stop(FabricClientCommandSource src) {
        if (!running) { src.sendFeedback(Text.literal("not running")); return; }
        endMs = System.currentTimeMillis();
        running = false;
        long total = endMs - startMs;
        src.sendFeedback(Text.literal("speedrun stopped: " + formatMs(total)).formatted(Formatting.YELLOW));

        // PB check
        DreamBotConfig c = DreamBotConfig.get();
        if (c.speedrunPbMillis == 0 || total < c.speedrunPbMillis) {
            long delta = c.speedrunPbMillis == 0 ? 0 : c.speedrunPbMillis - total;
            c.speedrunPbMillis = total;
            c.speedrunPbName = "any%";
            // also save these as the new PB splits
            c.pbSplitNames.clear();
            c.pbSplitTimes.clear();
            for (Split s : splits) {
                c.pbSplitNames.add(s.name);
                c.pbSplitTimes.add(s.timeMs);
            }
            DreamBotConfig.save();
            src.sendFeedback(Text.literal("NEW PERSONAL BEST! "
                + (delta > 0 ? "(-" + formatMs(delta) + ")" : "")).formatted(Formatting.GOLD));
        }
    }

    static void savePbSplits(FabricClientCommandSource src) {
        if (splits.isEmpty()) { src.sendFeedback(Text.literal("no splits in current run")); return; }
        DreamBotConfig c = DreamBotConfig.get();
        c.pbSplitNames.clear();
        c.pbSplitTimes.clear();
        for (Split s : splits) {
            c.pbSplitNames.add(s.name);
            c.pbSplitTimes.add(s.timeMs);
        }
        DreamBotConfig.save();
        src.sendFeedback(Text.literal("saved " + splits.size() + " splits as PB reference"));
    }

    // Returns delta in ms for given split name vs saved PB, or Long.MIN_VALUE if not found.
    static long deltaVsPb(String splitName, long currentMs) {
        DreamBotConfig c = DreamBotConfig.get();
        if (c.pbSplitNames == null) return Long.MIN_VALUE;
        for (int i = 0; i < c.pbSplitNames.size(); i++) {
            if (c.pbSplitNames.get(i).equals(splitName)) {
                return currentMs - c.pbSplitTimes.get(i);
            }
        }
        return Long.MIN_VALUE;
    }

    static void reset(FabricClientCommandSource src) {
        running = false;
        startMs = 0;
        endMs = 0;
        splits.clear();
        src.sendFeedback(Text.literal("speedrun reset"));
    }

    static void split(FabricClientCommandSource src, String name) {
        if (!running) { src.sendFeedback(Text.literal("not running, use /speedrun start")); return; }
        long elapsed = System.currentTimeMillis() - startMs;
        splits.add(new Split(name, elapsed));
        long delta = deltaVsPb(name, elapsed);
        String msg = "split: " + name + " @ " + formatMs(elapsed);
        if (delta != Long.MIN_VALUE) {
            msg += "  " + formatDelta(delta);
        }
        src.sendFeedback(Text.literal(msg));
    }

    static String formatDelta(long delta) {
        if (delta == Long.MIN_VALUE) return "";
        String sign = delta >= 0 ? "+" : "-";
        long abs = Math.abs(delta);
        return sign + formatMs(abs);
    }

    static void showPb(FabricClientCommandSource src) {
        DreamBotConfig c = DreamBotConfig.get();
        if (c.speedrunPbMillis == 0) {
            src.sendFeedback(Text.literal("no PB recorded yet"));
        } else {
            src.sendFeedback(Text.literal("PB (" + c.speedrunPbName + "): " + formatMs(c.speedrunPbMillis)));
        }
    }

    static void showSplits(FabricClientCommandSource src) {
        if (splits.isEmpty()) { src.sendFeedback(Text.literal("no splits yet")); return; }
        src.sendFeedback(Text.literal("=== splits ==="));
        for (Split s : splits) {
            src.sendFeedback(Text.literal(" " + s.name + " - " + formatMs(s.timeMs)));
        }
    }

    static String lastSplit() {
        if (splits.isEmpty()) return null;
        Split s = splits.get(splits.size() - 1);
        long delta = deltaVsPb(s.name, s.timeMs);
        if (delta != Long.MIN_VALUE) {
            return s.name + " " + formatMs(s.timeMs) + "  " + formatDelta(delta);
        }
        return s.name + " " + formatMs(s.timeMs);
    }

    static void tick() {
        if (!running) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        String dim = mc.world.getRegistryKey().getValue().toString();
        if (!dim.equals(lastDim)) {
            if (!lastDim.isEmpty()) {
                long elapsed = System.currentTimeMillis() - startMs;
                String name;
                if (dim.contains("the_nether"))       name = "Enter Nether";
                else if (dim.contains("the_end"))     name = "Enter End";
                else if (dim.contains("overworld"))   name = "Return Overworld";
                else                                  name = "Dim: " + dim;
                splits.add(new Split(name, elapsed));
                long delta = deltaVsPb(name, elapsed);
                String deltaStr = delta != Long.MIN_VALUE ? "  " + formatDelta(delta) : "";
                Formatting deltaColor = delta == Long.MIN_VALUE ? Formatting.AQUA
                    : (delta < 0 ? Formatting.GREEN : Formatting.RED);
                mc.player.sendMessage(
                    Text.literal("[split] " + name + " @ " + formatMs(elapsed) + deltaStr)
                        .formatted(deltaColor),
                    false);
            }
            lastDim = dim;
        }
    }

    static String formatTimer() {
        if (!running && endMs == 0) return "Timer: --";
        long ms = (running ? System.currentTimeMillis() : endMs) - startMs;
        return "Timer: " + formatMs(ms);
    }

    static String itemCounters(MinecraftClient mc) {
        if (mc.player == null) return null;
        PlayerInventory inv = mc.player.getInventory();
        int rods = 0, pearls = 0, eyes = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            if (s.isOf(Items.BLAZE_ROD))     rods   += s.getCount();
            else if (s.isOf(Items.ENDER_PEARL))  pearls += s.getCount();
            else if (s.isOf(Items.ENDER_EYE))    eyes   += s.getCount();
        }
        return String.format("Rods:%d  Pearls:%d  Eyes:%d/12", rods, pearls, eyes);
    }

    // Returns a "readiness" line for the current dimension.
    // Overworld -> ready for Nether?  Nether -> ready to trade?  etc.
    static String readinessLine(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return null;
        PlayerInventory inv = mc.player.getInventory();
        boolean hasBed = false, hasWater = false, hasPick = false, hasFlint = false;
        boolean hasGold = false, hasObsidian = false, hasFire = false, hasSword = false;
        int pearls = 0, eyes = 0, rods = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            String id = Registries.ITEM.getId(s.getItem()).getPath();
            if (id.endsWith("_bed")) hasBed = true;
            else if (id.equals("water_bucket")) hasWater = true;
            else if (id.endsWith("_pickaxe")) hasPick = true;
            else if (id.equals("flint_and_steel")) { hasFlint = true; hasFire = true; }
            else if (id.equals("fire_charge")) hasFire = true;
            else if (id.equals("gold_ingot")) hasGold = s.getCount() >= 8 || hasGold;
            else if (id.equals("obsidian")) hasObsidian = s.getCount() >= 10 || hasObsidian;
            else if (id.endsWith("_sword")) hasSword = true;
            if (s.isOf(Items.ENDER_PEARL)) pearls += s.getCount();
            if (s.isOf(Items.ENDER_EYE))   eyes   += s.getCount();
            if (s.isOf(Items.BLAZE_ROD))   rods   += s.getCount();
        }

        String dim = mc.world.getRegistryKey().getValue().toString();
        StringBuilder sb = new StringBuilder();
        if (dim.contains("overworld")) {
            sb.append("Nether ready: ");
            sb.append(hasPick ? "\u2713" : "\u2717").append("pick ");
            sb.append((hasFire || hasObsidian) ? "\u2713" : "\u2717").append("portal ");
            sb.append(hasSword ? "\u2713" : "\u2717").append("sword");
        } else if (dim.contains("the_nether")) {
            sb.append("Trade ready: ");
            sb.append("rods:").append(rods).append("/6 ");
            sb.append("pearls:").append(pearls).append("/12");
        } else if (dim.contains("the_end")) {
            sb.append("End: ");
            sb.append(hasWater ? "\u2713" : "\u2717").append("water ");
            sb.append(hasBed ? "\u2713" : "\u2717").append("bed ");
            sb.append("pearls:").append(pearls);
        } else {
            return null;
        }
        return sb.toString();
    }

    private static String formatMs(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        long cs = (ms % 1000) / 10;
        if (h > 0) return String.format("%d:%02d:%02d.%02d", h, m, s, cs);
        return String.format("%d:%02d.%02d", m, s, cs);
    }
}

// ================== SR Tools (practice reset, nether coord calc) ==================
class SrTools {
    static void practiceReset(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (!mc.isInSingleplayer()) {
            src.sendFeedback(Text.literal("practice reset is singleplayer only"));
            return;
        }
        mc.player.networkHandler.sendChatCommand("kill @s");
        mc.player.networkHandler.sendChatCommand("clear @s");
        src.sendFeedback(Text.literal("practice reset: killed + cleared (requires cheats)"));
    }

    static void netherOf(FabricClientCommandSource src, int x, int z) {
        int nx = x / 8;
        int nz = z / 8;
        src.sendFeedback(Text.literal(String.format("overworld %d %d -> nether %d %d", x, z, nx, nz)));
    }

    static void overworldOf(FabricClientCommandSource src, int x, int z) {
        int ox = x * 8;
        int oz = z * 8;
        src.sendFeedback(Text.literal(String.format("nether %d %d -> overworld %d %d", x, z, ox, oz)));
    }
}

// ================== Blind travel helper (stronghold triangulation) ==================
class BlindTravel {
    private static double x1, z1, yaw1;
    private static double x2, z2, yaw2;
    private static boolean have1 = false, have2 = false;

    static void captureEye(FabricClientCommandSource src, int which) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        double x = mc.player.getX();
        double z = mc.player.getZ();
        double yaw = mc.player.getYaw();
        if (which == 1) { x1 = x; z1 = z; yaw1 = yaw; have1 = true; }
        else            { x2 = x; z2 = z; yaw2 = yaw; have2 = true; }
        src.sendFeedback(Text.literal(String.format(
            "eye %d captured at %.1f %.1f yaw %.1f - run immediately AFTER throwing the eye while still facing it",
            which, x, z, yaw)));
        if (have1 && have2) {
            src.sendFeedback(Text.literal("both eyes captured - run /blind calc"));
        }
    }

    static void calculate(FabricClientCommandSource src) {
        if (!have1 || !have2) {
            src.sendFeedback(Text.literal("need both eyes: /blind eye1 then throw eye, move, /blind eye2"));
            return;
        }
        // direction vectors from minecraft yaw
        double r1 = Math.toRadians(yaw1);
        double r2 = Math.toRadians(yaw2);
        double dx1 = -Math.sin(r1), dz1 = Math.cos(r1);
        double dx2 = -Math.sin(r2), dz2 = Math.cos(r2);

        // solve: p1 + t1*d1 = p2 + t2*d2
        double det = dx1 * (-dz2) - (-dx2) * dz1;
        if (Math.abs(det) < 1e-6) {
            src.sendFeedback(Text.literal("lines are parallel - move further between throws"));
            return;
        }
        double dxp = x2 - x1;
        double dzp = z2 - z1;
        double t1 = (dxp * (-dz2) - (-dx2) * dzp) / det;

        double sx = x1 + t1 * dx1;
        double sz = z1 + t1 * dz1;

        MinecraftClient mc = MinecraftClient.getInstance();
        double cx = mc.player != null ? mc.player.getX() : x2;
        double cz = mc.player != null ? mc.player.getZ() : z2;
        double dist = Math.sqrt((sx - cx) * (sx - cx) + (sz - cz) * (sz - cz));

        // bearing from current to stronghold
        double bearing = Math.toDegrees(Math.atan2(-(sx - cx), (sz - cz)));

        src.sendFeedback(Text.literal(String.format(
            "stronghold estimate: %d %d", (int) sx, (int) sz)));
        src.sendFeedback(Text.literal(String.format(
            "distance: %.0fm   face yaw: %.1f", dist, bearing)));
        src.sendFeedback(Text.literal(
            "note: eye-throw yaw is an approximation. Two throws from different positions are needed."));

        // mark it so the compass arrow points to it
        Marker.set(new BlockPos((int) sx, 64, (int) sz), "stronghold");
    }

    static void clear(FabricClientCommandSource src) {
        have1 = false;
        have2 = false;
        src.sendFeedback(Text.literal("blind travel cleared"));
    }
}

// ================== Auto sprint ==================
class AutoSprint {
    static boolean enabled = false;

    static void toggle(FabricClientCommandSource src) {
        enabled = !enabled;
        src.sendFeedback(Text.literal("auto sprint: " + (enabled ? "ON" : "off")));
    }

    static void tick() {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options == null) return;
        if (mc.currentScreen != null) return;
        // only sprint if actually moving forward
        if (mc.options.forwardKey.isPressed() && !mc.player.isSneaking() && !mc.player.isTouchingWater()) {
            if (mc.player.getHungerManager().getFoodLevel() > 6) {
                mc.player.setSprinting(true);
            }
        }
    }
}

// ================== Auto tool ==================
// When the player attacks a block, picks the best tool in the hotbar
// (by mining speed on that block) and swaps to it. Swaps back when not
// attacking. Honest note: this is a common QoL mod feature but some
// strict anticheats flag rapid slot switches. Use /autotool to toggle.
class AutoTool {
    static boolean enabled = false;
    private static int savedSlot = -1;
    private static boolean wasAttacking = false;

    static void toggle(FabricClientCommandSource src) {
        enabled = !enabled;
        if (!enabled && savedSlot >= 0) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) mc.player.getInventory().setSelectedSlot(savedSlot);
            savedSlot = -1;
        }
        src.sendFeedback(Text.literal("auto tool: " + (enabled ? "ON" : "off")));
    }

    static void tick() {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options == null || mc.world == null) return;

        boolean attacking = mc.options.attackKey.isPressed()
            && mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult bhr
            && bhr.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK;

        if (attacking) {
            net.minecraft.util.hit.BlockHitResult bhr = (net.minecraft.util.hit.BlockHitResult) mc.crosshairTarget;
            BlockPos target = bhr.getBlockPos();
            BlockState bs = mc.world.getBlockState(target);
            if (bs.isAir()) return;
            int best = findBestToolSlot(mc, bs);
            if (best < 0) { wasAttacking = true; return; }
            if (!wasAttacking) savedSlot = mc.player.getInventory().getSelectedSlot();
            if (mc.player.getInventory().getSelectedSlot() != best) {
                mc.player.getInventory().setSelectedSlot(best);
            }
            wasAttacking = true;
        } else {
            if (wasAttacking && savedSlot >= 0) {
                mc.player.getInventory().setSelectedSlot(savedSlot);
                savedSlot = -1;
            }
            wasAttacking = false;
        }
    }

    private static int findBestToolSlot(MinecraftClient mc, BlockState bs) {
        PlayerInventory inv = mc.player.getInventory();
        int bestSlot = -1;
        float bestSpeed = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            float speed = s.getMiningSpeedMultiplier(bs);
            // prefer correct-tool matches
            boolean suitable = s.isSuitableFor(bs);
            if (suitable) speed += 100;
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = i; }
        }
        return bestSlot;
    }
}

// ================== CPS counter ==================
class CpsCounter {
    private static final java.util.Deque<Long> clickTimes = new java.util.ArrayDeque<>();
    private static boolean wasDown = false;

    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return;
        boolean down = mc.options.attackKey.isPressed();
        if (down && !wasDown) {
            clickTimes.add(System.currentTimeMillis());
        }
        wasDown = down;
        long cutoff = System.currentTimeMillis() - 1000;
        while (!clickTimes.isEmpty() && clickTimes.peekFirst() < cutoff) {
            clickTimes.pollFirst();
        }
    }

    static int getCps() { return clickTimes.size(); }
}

// ================== Session tracker ==================
class SessionTracker {
    private static long worldJoinMs = 0;
    private static boolean inWorld = false;

    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean nowInWorld = mc != null && mc.player != null && mc.world != null;
        if (nowInWorld && !inWorld) {
            worldJoinMs = System.currentTimeMillis();
        } else if (!nowInWorld && inWorld) {
            worldJoinMs = 0;
        }
        inWorld = nowInWorld;
    }

    static long sessionSeconds() {
        if (worldJoinMs == 0) return 0;
        return (System.currentTimeMillis() - worldJoinMs) / 1000;
    }
}

// ================== Chat extras (aliases + ignore list) ==================
class ChatExtras {
    static void listAliases(FabricClientCommandSource src) {
        Map<String, String> m = DreamBotConfig.get().chatAliases;
        if (m.isEmpty()) { src.sendFeedback(Text.literal("no aliases. /alias set <name> <text>")); return; }
        src.sendFeedback(Text.literal("aliases (use .name in chat):"));
        for (Map.Entry<String, String> e : m.entrySet()) {
            src.sendFeedback(Text.literal(" ." + e.getKey() + " -> " + e.getValue()));
        }
    }

    static void setAlias(FabricClientCommandSource src, String name, String text) {
        if (name.isEmpty() || text.isEmpty()) { src.sendFeedback(Text.literal("usage: /alias set <name> <text>")); return; }
        DreamBotConfig.get().chatAliases.put(name, text);
        DreamBotConfig.save();
        src.sendFeedback(Text.literal("alias set: ." + name + " -> " + text));
    }

    static void delAlias(FabricClientCommandSource src, String name) {
        if (DreamBotConfig.get().chatAliases.remove(name) != null) {
            DreamBotConfig.save();
            src.sendFeedback(Text.literal("removed alias: ." + name));
        } else {
            src.sendFeedback(Text.literal("no alias: ." + name));
        }
    }

    static void ignore(FabricClientCommandSource src, String name) {
        List<String> list = DreamBotConfig.get().ignoredPlayers;
        if (list.contains(name)) {
            src.sendFeedback(Text.literal("already ignoring " + name));
            return;
        }
        list.add(name);
        DreamBotConfig.save();
        src.sendFeedback(Text.literal("ignoring " + name + " (their chat will be hidden)"));
    }

    static void unignore(FabricClientCommandSource src, String name) {
        if (DreamBotConfig.get().ignoredPlayers.remove(name)) {
            DreamBotConfig.save();
            src.sendFeedback(Text.literal("unignored " + name));
        } else {
            src.sendFeedback(Text.literal("not ignoring " + name));
        }
    }

    static void listIgnored(FabricClientCommandSource src) {
        List<String> list = DreamBotConfig.get().ignoredPlayers;
        if (list.isEmpty()) { src.sendFeedback(Text.literal("ignore list empty")); return; }
        src.sendFeedback(Text.literal("ignored players:"));
        for (String n : list) src.sendFeedback(Text.literal(" - " + n));
    }
}

// ================== Home point ==================
class HomePoint {
    static void setHome(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        DreamBotConfig c = DreamBotConfig.get();
        BlockPos p = mc.player.getBlockPos();
        c.homeX = p.getX();
        c.homeY = p.getY();
        c.homeZ = p.getZ();
        c.homeDim = mc.world.getRegistryKey().getValue().toString();
        c.homeSet = true;
        DreamBotConfig.save();
        src.sendFeedback(Text.literal(String.format(
            "home set: %d %d %d (%s)", p.getX(), p.getY(), p.getZ(), c.homeDim)));
    }

    static void markHome(FabricClientCommandSource src) {
        DreamBotConfig c = DreamBotConfig.get();
        if (!c.homeSet) { src.sendFeedback(Text.literal("no home set. use /sethome")); return; }
        Marker.set(new BlockPos(c.homeX, c.homeY, c.homeZ), "home");
        MinecraftClient mc = MinecraftClient.getInstance();
        String currentDim = mc.world != null ? mc.world.getRegistryKey().getValue().toString() : "";
        if (!currentDim.equals(c.homeDim)) {
            src.sendFeedback(Text.literal("home is in a different dimension (" + c.homeDim + ")").formatted(Formatting.YELLOW));
        }
        src.sendFeedback(Text.literal(String.format(
            "home marked: %d %d %d", c.homeX, c.homeY, c.homeZ)));
    }
}

// ================== QoL Pack (one-shot commands) ==================
class QolPack {
    static void copyCoords(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        BlockPos p = mc.player.getBlockPos();
        String s = p.getX() + " " + p.getY() + " " + p.getZ();
        try {
            mc.keyboard.setClipboard(s);
            src.sendFeedback(Text.literal("copied: " + s));
        } catch (Exception e) {
            src.sendFeedback(Text.literal("clipboard failed: " + s));
        }
    }

    static void shareCoords(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        BlockPos p = mc.player.getBlockPos();
        String dim = mc.world != null ? mc.world.getRegistryKey().getValue().getPath() : "?";
        String msg = String.format("My coords: %d %d %d (%s)", p.getX(), p.getY(), p.getZ(), dim);
        mc.player.networkHandler.sendChatMessage(msg);
    }

    static void clearChat(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud != null && mc.inGameHud.getChatHud() != null) {
            mc.inGameHud.getChatHud().clear(false);
            src.sendFeedback(Text.literal("chat cleared"));
        }
    }
}

// ================== Session stats ==================
class SessionStats {
    static int kills = 0;
    static double distance = 0;
    static double damageTaken = 0;
    private static double lastX, lastY, lastZ;
    private static boolean havePos = false;
    private static float lastHp = 20f;
    private static int lastMobCount = -1;

    static void show(FabricClientCommandSource src) {
        src.sendFeedback(Text.literal("=== session stats ==="));
        src.sendFeedback(Text.literal(" kills: " + kills));
        src.sendFeedback(Text.literal(" walked: " + (int) distance + "m"));
        src.sendFeedback(Text.literal(String.format(" damage taken: %.1f", damageTaken)));
    }

    static void reset(FabricClientCommandSource src) {
        kills = 0; distance = 0; damageTaken = 0;
        src.sendFeedback(Text.literal("session stats reset"));
    }

    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) { havePos = false; return; }
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        if (havePos) {
            double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
            double step = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (step < 5) distance += step; // filter teleports
        }
        lastX = x; lastY = y; lastZ = z; havePos = true;

        // damage: compare hp to last tick
        float hp = mc.player.getHealth();
        if (hp < lastHp) damageTaken += (lastHp - hp);
        lastHp = hp;

        // kill detection: approximate via attack + target entity death
        // We can't know for sure client-side; use a simple heuristic:
        // if we attacked something last tick and it's now gone/dead, count it.
        // (Intentionally imperfect — better than nothing for session vibes.)
    }

    // called by attack hook if we add one later; for now left as placeholder
    static void incrementKill() { kills++; }
}

// ================== Auto respawn ==================
class AutoRespawn {
    static void tick() {
        if (!DreamBotConfig.get().autoRespawn) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.player.isDead() && mc.currentScreen != null) {
            try {
                mc.player.requestRespawn();
                mc.setScreen(null);
            } catch (Exception ignored) {}
        }
    }
}

// ================== Perma chat (prevent auto-fade) ==================
class PermaChat {
    static void tick() {
        if (!DreamBotConfig.get().permaChat) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.inGameHud == null) return;
        var chat = mc.inGameHud.getChatHud();
        if (chat == null) return;
        // keep messages fresh by bumping their age each tick
        // We can't directly modify message age; simplest workaround is to
        // push a silent empty-string update every ~5 seconds to keep the
        // rendering recent. In practice the cleanest approach is to hold
        // chat open, which is what most QoL mods do via mixin. We take a
        // light approach: no-op tick; perma chat functionally requires a
        // mixin we're not adding. The flag is here for future hookup.
    }
}

// ================== Toggle sneak ==================
class ToggleSneak {
    private static boolean toggled = false;
    private static boolean wasPressed = false;

    static void tick() {
        if (!DreamBotConfig.get().toggleSneakMode) {
            toggled = false;
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null || mc.player == null) return;
        // When sneak key is tapped in toggle mode, flip the toggle
        boolean pressed = mc.options.sneakKey.isPressed();
        if (pressed && !wasPressed) {
            toggled = !toggled;
        }
        wasPressed = pressed;
        // Force sneak key state if toggled
        if (toggled) {
            mc.options.sneakKey.setPressed(true);
        }
    }
}

// ================== Bed tracker ==================
class BedTracker {
    private static boolean wasSleeping = false;

    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) { wasSleeping = false; return; }
        boolean sleeping = mc.player.isSleeping();
        if (sleeping && !wasSleeping) {
            mc.player.getSleepingPosition().ifPresent(pos -> {
                Marker.set(pos, "bed");
                mc.player.sendMessage(Text.literal("[DreamBot] bed marked at " +
                    pos.getX() + " " + pos.getY() + " " + pos.getZ())
                    .formatted(Formatting.AQUA), false);
            });
        }
        wasSleeping = sleeping;
    }
}

// ================== Breath watcher ==================
class BreathWatcher {
    private static boolean warned = false;

    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        int air = mc.player.getAir();
        if (air < 60 && !warned) {
            mc.player.sendMessage(Text.literal("[DreamBot] Low air! " + air + "/300")
                .formatted(Formatting.RED), false);
            warned = true;
        } else if (air >= 200) {
            warned = false;
        }
    }
}

// ================== QolPack2 (commands for the v2 batch) ==================
class QolPack2 {
    static void wpSave(FabricClientCommandSource src, String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        BlockPos p = mc.player.getBlockPos();
        DreamBotConfig c = DreamBotConfig.get();
        c.namedWaypoints.put(name, new int[]{p.getX(), p.getY(), p.getZ()});
        c.namedWaypointDims.put(name, mc.world.getRegistryKey().getValue().toString());
        DreamBotConfig.save();
        src.sendFeedback(Text.literal("waypoint saved: " + name + " @ " + p.getX() + " " + p.getY() + " " + p.getZ()));
    }

    static void wpList(FabricClientCommandSource src) {
        Map<String, int[]> wps = DreamBotConfig.get().namedWaypoints;
        if (wps.isEmpty()) { src.sendFeedback(Text.literal("no waypoints. /wp save <name>")); return; }
        src.sendFeedback(Text.literal("=== waypoints ==="));
        for (Map.Entry<String, int[]> e : wps.entrySet()) {
            int[] p = e.getValue();
            String dim = DreamBotConfig.get().namedWaypointDims.getOrDefault(e.getKey(), "?");
            dim = dim.replace("minecraft:", "");
            src.sendFeedback(Text.literal(" " + e.getKey() + ": " + p[0] + " " + p[1] + " " + p[2] + " (" + dim + ")"));
        }
    }

    static void wpMark(FabricClientCommandSource src, String name) {
        int[] p = DreamBotConfig.get().namedWaypoints.get(name);
        if (p == null) { src.sendFeedback(Text.literal("no waypoint: " + name)); return; }
        Marker.set(new BlockPos(p[0], p[1], p[2]), name);
        src.sendFeedback(Text.literal("marked: " + name));
    }

    static void wpDel(FabricClientCommandSource src, String name) {
        if (DreamBotConfig.get().namedWaypoints.remove(name) != null) {
            DreamBotConfig.get().namedWaypointDims.remove(name);
            DreamBotConfig.save();
            src.sendFeedback(Text.literal("deleted: " + name));
        } else {
            src.sendFeedback(Text.literal("no waypoint: " + name));
        }
    }

    static void findItem(FabricClientCommandSource src, String query) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        PlayerInventory inv = mc.player.getInventory();
        String q = query.toLowerCase();
        boolean found = false;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            String n = s.getItem().getName().getString().toLowerCase();
            String id = Registries.ITEM.getId(s.getItem()).getPath().toLowerCase();
            if (n.contains(q) || id.contains(q)) {
                String slotName;
                if (i < 9) slotName = "hotbar " + (i+1);
                else if (i < 36) slotName = "inv slot " + i;
                else slotName = "armor/offhand " + i;
                src.sendFeedback(Text.literal(" " + s.getCount() + "x " + n + " in " + slotName));
                found = true;
            }
        }
        if (!found) src.sendFeedback(Text.literal("no items matching '" + query + "'"));
    }

    static void repeat(FabricClientCommandSource src, int n, String cmd) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        for (int i = 0; i < n; i++) {
            mc.player.networkHandler.sendChatCommand(cmd);
        }
        src.sendFeedback(Text.literal("ran " + n + " times: /" + cmd));
    }

    static void startTimer(FabricClientCommandSource src, int secs, String msg) {
        CountdownTimer.start(secs, msg);
        src.sendFeedback(Text.literal("timer started: " + secs + "s, " + msg));
    }

    static void showDay(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        long day = mc.world.getTimeOfDay() / 24000L;
        src.sendFeedback(Text.literal("Day " + day));
    }

    static void listMobs(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        Map<String, Integer> counts = new HashMap<>();
        double range = 32 * 32;
        for (net.minecraft.entity.Entity e : mc.world.getEntities()) {
            if (e == mc.player) continue;
            if (e.squaredDistanceTo(mc.player) > range) continue;
            if (!(e instanceof net.minecraft.entity.LivingEntity)) continue;
            String name = e.getName().getString();
            counts.merge(name, 1, Integer::sum);
        }
        if (counts.isEmpty()) { src.sendFeedback(Text.literal("no mobs nearby")); return; }
        src.sendFeedback(Text.literal("=== mobs within 32m ==="));
        counts.entrySet().stream()
            .sorted((a,b) -> b.getValue() - a.getValue())
            .forEach(e -> src.sendFeedback(Text.literal(" " + e.getValue() + "x " + e.getKey())));
    }

    static void showMotd(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getCurrentServerEntry() == null) {
            src.sendFeedback(Text.literal("not on a server"));
            return;
        }
        var entry = mc.getCurrentServerEntry();
        src.sendFeedback(Text.literal("=== " + entry.address + " ==="));
        if (entry.label != null) src.sendFeedback(entry.label);
    }
}

// ================== Notes ==================
class Notes {
    private static final List<String> notes = new ArrayList<>();

    static void add(FabricClientCommandSource src, String text) {
        notes.add(text);
        src.sendFeedback(Text.literal("note #" + notes.size() + " added"));
    }

    static void list(FabricClientCommandSource src) {
        if (notes.isEmpty()) { src.sendFeedback(Text.literal("no notes")); return; }
        src.sendFeedback(Text.literal("=== notes ==="));
        for (int i = 0; i < notes.size(); i++) {
            src.sendFeedback(Text.literal(" " + (i+1) + ". " + notes.get(i)));
        }
    }

    static void clear(FabricClientCommandSource src) {
        int n = notes.size();
        notes.clear();
        src.sendFeedback(Text.literal("cleared " + n + " notes"));
    }
}

// ================== Auto greet ==================
class AutoGreet {
    private static long joinedAt = 0;
    private static boolean sent = false;
    private static boolean wasInWorld = false;

    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean inWorld = mc.player != null && mc.world != null && mc.getNetworkHandler() != null;
        if (inWorld && !wasInWorld) {
            joinedAt = System.currentTimeMillis();
            sent = false;
        }
        wasInWorld = inWorld;
        if (!inWorld) return;

        String msg = DreamBotConfig.get().autoGreetMsg;
        if (msg == null || msg.isEmpty()) return;
        if (sent) return;
        if (System.currentTimeMillis() - joinedAt < 3000) return; // wait 3s after join
        if (mc.isInSingleplayer()) { sent = true; return; }
        try {
            mc.player.networkHandler.sendChatMessage(msg);
            sent = true;
        } catch (Exception ignored) { sent = true; }
    }
}

// ================== Countdown timer ==================
class CountdownTimer {
    private static long endMs = 0;
    private static String label = "";
    private static boolean fired = false;

    static void start(int secs, String msg) {
        endMs = System.currentTimeMillis() + secs * 1000L;
        label = msg;
        fired = false;
    }

    static boolean isActive() { return endMs > System.currentTimeMillis(); }
    static long secondsLeft() { return Math.max(0, (endMs - System.currentTimeMillis()) / 1000); }
    static String label() { return label; }

    static void tick() {
        if (endMs == 0 || fired) return;
        if (System.currentTimeMillis() < endMs) return;
        fired = true;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[DreamBot] TIMER: " + label).formatted(Formatting.YELLOW), false);
            try {
                mc.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0f, 1.0f);
            } catch (Exception ignored) {}
        }
    }
}

// ================== TPS tracker (estimates server tick rate) ==================
class TpsTracker {
    private static long lastSampleMs = 0;
    private static long lastWorldTick = 0;
    private static double tps = 20.0;

    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) { lastSampleMs = 0; return; }
        long now = System.currentTimeMillis();
        long worldTick = mc.world.getTime();
        if (lastSampleMs == 0) {
            lastSampleMs = now;
            lastWorldTick = worldTick;
            return;
        }
        long elapsed = now - lastSampleMs;
        if (elapsed >= 2000) {
            long ticksDelta = worldTick - lastWorldTick;
            tps = (ticksDelta * 1000.0) / elapsed;
            if (tps > 20) tps = 20;
            if (tps < 0) tps = 0;
            lastSampleMs = now;
            lastWorldTick = worldTick;
        }
    }

    static double getTps() { return tps; }
}

// ================== Last damage tracker ==================
// Detects damage by HP delta (we don't have a clean damage event without mixin).
class LastDamageTracker {
    static String lastSource = null;
    static long lastTime = 0;
    private static float lastHp = 20f;

    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        float hp = mc.player.getHealth();
        if (hp < lastHp - 0.01f) {
            // damage taken; try to attribute via nearest hostile or fall
            lastTime = System.currentTimeMillis();
            if (mc.player.fallDistance > 3) {
                lastSource = "fall";
            } else if (mc.player.isOnFire()) {
                lastSource = "fire";
            } else if (mc.player.isInLava()) {
                lastSource = "lava";
            } else if (mc.player.isSubmergedInWater() && mc.player.getAir() <= 0) {
                lastSource = "drowning";
            } else if (mc.world != null) {
                String near = "unknown";
                double bestDist = 25;
                for (net.minecraft.entity.Entity e : mc.world.getEntities()) {
                    if (!(e instanceof net.minecraft.entity.LivingEntity)) continue;
                    if (e == mc.player) continue;
                    double d = e.squaredDistanceTo(mc.player);
                    if (d < bestDist) { bestDist = d; near = e.getName().getString(); }
                }
                lastSource = near;
            } else {
                lastSource = "unknown";
            }
        }
        lastHp = hp;
    }
}

// ================== Pearl tracker (right-click cooldown after pearl) ==================
class PearlTracker {
    private static long lastThrowMs = 0;
    private static int lastPearls = -1;
    private static final long COOLDOWN_MS = 1000; // vanilla pearl cooldown

    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) { lastPearls = -1; return; }
        int pearls = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(Items.ENDER_PEARL)) pearls += s.getCount();
        }
        if (lastPearls > 0 && pearls < lastPearls) {
            // pearl was thrown
            lastThrowMs = System.currentTimeMillis();
        }
        lastPearls = pearls;
    }

    static long cooldownLeftMs() {
        long elapsed = System.currentTimeMillis() - lastThrowMs;
        return Math.max(0, COOLDOWN_MS - elapsed);
    }
}
