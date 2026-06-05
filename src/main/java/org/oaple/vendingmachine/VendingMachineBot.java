package org.oaple.vendingmachine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class VendingMachineBot extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(VendingMachineBot.class);
    private static final String KEY_PREFIX = "vend:key:";
    private static final String DISPENSE_ID = "vend:dispense";
    private static final String CLEAR_ID = "vend:clear";
    private static final Pattern SLOT_CODE = Pattern.compile("[A-Z][0-9]{1,2}");
    private static final Pattern SELECTION_PATTERN = Pattern.compile("\\*\\*Selection:\\*\\* `([A-C_][1-3_])`");
    private static final Pattern DROPBOX_LINK_PATTERN = Pattern.compile("\\*\\*Drop-box:\\*\\* \\[([^]]+)]\\(([^)]+)\\)");
    private static final Pattern DROPBOX_TEXT_PATTERN = Pattern.compile("\\*\\*Drop-box:\\*\\* ([^\\n]+)");
    private static final Pattern IMG_TAG_PATTERN = Pattern.compile("(?i)<img\\b[^>]*\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>");
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("!\\[[^]]*]\\(([^)]+)\\)");
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[[^]]+]\\(([^)]+)\\)");
    private static final Pattern IMGUR_ID_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");
    private static final Color MACHINE_YELLOW = new Color(0xF2B84B);
    private static final Color DROPBOX_GREEN = new Color(0x5BBE7A);
    private static final long DISPENSE_STEP_DELAY_MS = 850;

    private final MachineStore store;
    private final Random random = new SecureRandom();
    private final String ownerId;
    private Machine machine;

    private VendingMachineBot(MachineStore store, String ownerId) throws IOException {
        this.store = store;
        this.ownerId = ownerId == null ? "" : ownerId;
        this.machine = normalizeMachine(store.loadOrCreateDefault());
    }

    public static void main(String[] args) throws Exception {
        BotConfig config = BotConfig.load();
        VendingMachineBot bot = new VendingMachineBot(new MachineStore(config.dataPath()), config.ownerId());

        JDA jda = JDABuilder.createDefault(config.token())
                .setStatus(OnlineStatus.ONLINE)
                .setActivity(Activity.playing("with mystery stock"))
                .addEventListeners(bot)
                .build();

        jda.awaitReady();
        registerCommands(jda, config.guildId());
        LOGGER.info("Discord Vending Machine is online.");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "machine" -> handleMachine(event);
            case "vend" -> handleVend(event);
            case "stock" -> handleStock(event);
            default -> {
            }
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith(KEY_PREFIX)) {
            handleKeypad(event, id.substring(KEY_PREFIX.length()));
            return;
        }
        if (id.equals(CLEAR_ID)) {
            handleClearSelection(event);
            return;
        }
        if (!id.equals(DISPENSE_ID)) {
            return;
        }

        MachineView view = machineView(event.getMessage());
        String code = view.selection();
        if (!completeSelection(code)) {
            event.reply("Pick a row and a number first.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue(hook -> {
            try {
                DispensedItem dispensed = dispense(code);
                dispatchDispense(
                        resolveOutputChannel(event.getGuild(), event.getMessageChannel()),
                        dispensed,
                        hook,
                        delivered -> updateMachineDropBox(event.getMessage(), dispensed, delivered)
                );
            } catch (IllegalArgumentException ex) {
                replyPrivately(hook, ex.getMessage());
            } catch (IOException ex) {
                replyPrivately(hook, "The machine jammed while saving stock data. Check the bot logs.");
            }
        });
    }

    private void handleMachine(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> event.getMessageChannel()
                .sendMessageEmbeds(machineEmbed(machine(), MachineView.empty()))
                .setComponents(keypadButtons("__"))
                .queue(
                        sent -> replyPrivately(hook, "Machine posted."),
                        failure -> replyPrivately(hook, "Could not post the machine. Check the bot logs.")
                ));
    }

    private void handleVend(SlashCommandInteractionEvent event) {
        String code = Objects.requireNonNull(event.getOption("code")).getAsString();
        event.deferReply(true).queue(hook -> {
            try {
                DispensedItem dispensed = dispense(code);
                dispatchDispense(resolveOutputChannel(event.getGuild(), event.getMessageChannel()), dispensed, hook, ignored -> {
                });
            } catch (IllegalArgumentException ex) {
                replyPrivately(hook, ex.getMessage());
            } catch (IOException ex) {
                replyPrivately(hook, "The machine jammed while saving stock data. Check the bot logs.");
            }
        });
    }

    private void handleKeypad(ButtonInteractionEvent event, String key) {
        MachineView view = machineView(event.getMessage());
        String selection = applyKey(view.selection(), key);
        event.editMessageEmbeds(machineEmbed(machine(), view.withSelection(selection)))
                .setComponents(keypadButtons(selection))
                .queue();
    }

    private void handleClearSelection(ButtonInteractionEvent event) {
        MachineView view = machineView(event.getMessage()).withSelection("__");
        event.editMessageEmbeds(machineEmbed(machine(), view))
                .setComponents(keypadButtons(view.selection()))
                .queue();
    }

    private void handleStock(SlashCommandInteractionEvent event) {
        if (!isManager(event.getMember())) {
            event.reply("Only server managers can restock the machine.").setEphemeral(true).queue();
            return;
        }

        try {
            switch (event.getSubcommandName() == null ? "" : event.getSubcommandName()) {
                case "list" -> event.reply(stockSummary()).setEphemeral(true).queue();
                case "reload" -> {
                    reload();
                    event.reply("Reloaded stock from disk.").setEphemeral(true).queue();
                }
                case "add" -> handleStockAdd(event);
                case "clear" -> handleStockClear(event);
                case "enable" -> handleStockEnabled(event, true);
                case "disable" -> handleStockEnabled(event, false);
                case "set-output" -> handleSetOutput(event);
                default -> event.reply("Unknown stock command.").setEphemeral(true).queue();
            }
        } catch (IOException ex) {
            event.reply("Could not save stock data. Check the bot logs.").setEphemeral(true).queue();
        } catch (IllegalArgumentException ex) {
            event.reply(ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleStockAdd(SlashCommandInteractionEvent event) throws IOException {
        String code = Objects.requireNonNull(event.getOption("code")).getAsString();
        String label = Objects.requireNonNull(event.getOption("label")).getAsString();
        String content = Objects.requireNonNull(event.getOption("content")).getAsString();
        String imageUrl = event.getOption("image-url") == null
                ? ""
                : normalizeImageUrl(Objects.requireNonNull(event.getOption("image-url")).getAsString());
        boolean rare = event.getOption("rare") != null && Objects.requireNonNull(event.getOption("rare")).getAsBoolean();
        Slot slot = addStock(code, label, new StockItem(content, imageUrl, rare, 1));
        event.reply("Restocked `" + slot.getCode() + "` with `" + slot.getLabel() + "`.").setEphemeral(true).queue();
    }

    private void handleStockClear(SlashCommandInteractionEvent event) throws IOException {
        String code = Objects.requireNonNull(event.getOption("code")).getAsString();
        clearSlot(code);
        event.reply("Cleared `" + normalizeCode(code) + "`.").setEphemeral(true).queue();
    }

    private void handleStockEnabled(SlashCommandInteractionEvent event, boolean enabled) throws IOException {
        String code = Objects.requireNonNull(event.getOption("code")).getAsString();
        setEnabled(code, enabled);
        event.reply((enabled ? "Enabled " : "Disabled ") + "`" + normalizeCode(code) + "`.").setEphemeral(true).queue();
    }

    private void handleSetOutput(SlashCommandInteractionEvent event) throws IOException {
        String channelId = Objects.requireNonNull(event.getOption("channel-id")).getAsString();
        setOutputChannelId(channelId);
        event.reply("Dispense output channel set to `<#" + cleanSnowflake(channelId) + ">`.").setEphemeral(true).queue();
    }

    private synchronized Machine machine() {
        return machine;
    }

    private synchronized void reload() throws IOException {
        machine = normalizeMachine(store.loadOrCreateDefault());
    }

    private synchronized DispensedItem dispense(String rawCode) throws IOException {
        String code = normalizeCode(rawCode);
        Slot slot = machine.getSlots().get(code);
        if (slot == null) {
            throw new IllegalArgumentException("`" + code + "` is not stocked in this machine.");
        }
        if (!slot.isEnabled()) {
            throw new IllegalArgumentException("`" + code + "` is currently sold out.");
        }
        if (slot.getItems().isEmpty()) {
            throw new IllegalArgumentException("`" + code + "` rattles sadly. Nothing is inside.");
        }

        StockItem item = choose(slot);
        slot.setDispensed(slot.getDispensed() + 1);
        store.save(machine);
        return new DispensedItem(slot, item);
    }

    private synchronized Slot addStock(String rawCode, String label, StockItem item) throws IOException {
        String code = normalizeCode(rawCode);
        validateCode(code);
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Slot label cannot be blank.");
        }
        if (item.getContent() == null || item.getContent().isBlank()) {
            throw new IllegalArgumentException("Stock content cannot be blank.");
        }
        if (item.getWeight() < 1) {
            item.setWeight(1);
        }

        Slot slot = machine.getSlots().computeIfAbsent(code, ignored -> new Slot(code, label.trim()));
        slot.setCode(code);
        slot.setLabel(label.trim());
        slot.setEnabled(true);
        slot.getItems().add(item);
        store.save(machine);
        return slot;
    }

    private synchronized void clearSlot(String rawCode) throws IOException {
        Slot slot = requireSlot(rawCode);
        slot.getItems().clear();
        store.save(machine);
    }

    private synchronized void setEnabled(String rawCode, boolean enabled) throws IOException {
        Slot slot = requireSlot(rawCode);
        slot.setEnabled(enabled);
        store.save(machine);
    }

    private synchronized void setOutputChannelId(String channelId) throws IOException {
        machine.setOutputChannelId(cleanSnowflake(channelId));
        store.save(machine);
    }

    private synchronized String stockSummary() {
        StringBuilder summary = new StringBuilder("```text\n");
        for (Slot slot : machine.getSlots().values()) {
            summary.append(String.format(
                    "%-3s %-14s %3d item(s) %s %d dispensed%n",
                    slot.getCode(),
                    slot.getLabel(),
                    slot.getItems().size(),
                    slot.isEnabled() ? "online " : "offline",
                    slot.getDispensed()
            ));
        }
        summary.append("```");
        return summary.toString();
    }

    private boolean isManager(Member member) {
        if (member == null) {
            return false;
        }
        return member.hasPermission(Permission.MANAGE_SERVER)
                || member.hasPermission(Permission.ADMINISTRATOR)
                || (!ownerId.isBlank() && member.getId().equals(ownerId));
    }

    private MessageChannel resolveOutputChannel(Guild guild, MessageChannel fallback) {
        String outputChannelId = machine().getOutputChannelId();
        if (guild != null && outputChannelId != null && !outputChannelId.isBlank()) {
            TextChannel output = guild.getTextChannelById(outputChannelId);
            if (output != null) {
                return output;
            }
        }
        return fallback;
    }

    private void dispatchDispense(MessageChannel outputChannel, DispensedItem dispensed, InteractionHook hook, Consumer<Message> onDelivered) {
        List<String> steps = machine().getDispenseSequence();
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.get(i).replace("{code}", dispensed.slot().getCode());
            hook.editOriginal(step)
                    .queueAfter(i * DISPENSE_STEP_DELAY_MS, TimeUnit.MILLISECONDS, ignored -> {
                    }, failure -> LOGGER.warn("Could not update private vending machine status.", failure));
        }

        long deliveryDelay = Math.max(DISPENSE_STEP_DELAY_MS, steps.size() * DISPENSE_STEP_DELAY_MS);
        outputChannel.sendMessageEmbeds(dispenseEmbed(dispensed))
                .queueAfter(deliveryDelay, TimeUnit.MILLISECONDS, delivered -> {
                    replyPrivately(
                            hook,
                            "`clunk.` Collect your selection here: [post link](" + delivered.getJumpUrl() + ")"
                    );
                    onDelivered.accept(delivered);
                }, failure -> replyPrivately(hook, "The drop-box jammed while delivering the item. Check the bot logs."));
    }

    private void updateMachineDropBox(Message machineMessage, DispensedItem dispensed, Message delivered) {
        MachineView view = machineView(machineMessage)
                .withSelection("__")
                .withLatestDrop(delivered.getJumpUrl(), dispensed.slot().getCode() + " clunked over here");
        machineMessage.editMessageEmbeds(machineEmbed(machine(), view))
                .setComponents(keypadButtons(view.selection()))
                .queue(null, failure -> LOGGER.warn("Could not update vending machine drop-box link.", failure));
    }

    private void replyPrivately(InteractionHook hook, String message) {
        hook.editOriginal(message)
                .queue(null, failure -> LOGGER.warn("Could not update private vending machine reply.", failure));
    }

    private String normalizeCode(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private Slot requireSlot(String rawCode) {
        String code = normalizeCode(rawCode);
        Slot slot = machine.getSlots().get(code);
        if (slot == null) {
            throw new IllegalArgumentException("`" + code + "` is not stocked in this machine.");
        }
        return slot;
    }

    private void validateCode(String code) {
        if (!SLOT_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Slot codes must look like `A1`, `B2`, or `C10`.");
        }
    }

    private String cleanSnowflake(String raw) {
        String channelId = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (channelId.isBlank()) {
            throw new IllegalArgumentException("Channel ID cannot be blank.");
        }
        return channelId;
    }

    private StockItem choose(Slot slot) {
        int totalWeight = slot.getItems().stream()
                .mapToInt(item -> Math.max(1, item.getWeight()))
                .sum();
        int ticket = random.nextInt(totalWeight);
        int seen = 0;
        for (StockItem item : slot.getItems()) {
            seen += Math.max(1, item.getWeight());
            if (ticket < seen) {
                return item;
            }
        }
        return slot.getItems().getLast();
    }

    private Machine normalizeMachine(Machine loaded) {
        Map<String, Slot> normalized = new LinkedHashMap<>();
        for (Slot slot : loaded.getSlots().values()) {
            String code = normalizeCode(slot.getCode());
            if (code.isBlank()) {
                continue;
            }
            slot.setCode(code);
            slot.getItems().forEach(item -> item.setImageUrl(normalizeImageUrl(item.getImageUrl())));
            normalized.put(code, slot);
        }
        loaded.setSlots(normalized);
        return loaded;
    }

    private static void registerCommands(JDA jda, String guildId) {
        List<CommandData> commands = commands();
        if (guildId != null && !guildId.isBlank()) {
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                LOGGER.warn("GUILD_ID {} was configured, but the bot cannot see that guild. Registering global commands instead.", guildId);
                jda.updateCommands().addCommands(commands).queue();
                return;
            }
            guild.updateCommands().addCommands(commands).queue();
            LOGGER.info("Registered slash commands for guild {}.", guildId);
            return;
        }

        jda.updateCommands().addCommands(commands).queue();
        LOGGER.info("Registered global slash commands.");
    }

    private static List<CommandData> commands() {
        return List.of(
                Commands.slash("machine", "Post the current vending machine display."),
                Commands.slash("vend", "Dispense an item from a slot.")
                        .addOptions(new OptionData(OptionType.STRING, "code", "Slot code, like A1.", true)),
                Commands.slash("stock", "Manage vending machine stock.")
                        .addSubcommands(
                                new SubcommandData("list", "List slots and stock counts."),
                                new SubcommandData("reload", "Reload stock/settings from disk."),
                                new SubcommandData("add", "Add an item to a slot.")
                                        .addOptions(
                                                new OptionData(OptionType.STRING, "code", "Slot code, like A1.", true),
                                                new OptionData(OptionType.STRING, "label", "Slot label, like Cats.", true),
                                                new OptionData(OptionType.STRING, "content", "Text or URL to dispense.", true),
                                                new OptionData(OptionType.STRING, "image-url", "Optional image URL for the embed.", false),
                                                new OptionData(OptionType.BOOLEAN, "rare", "Mark this item as rare.", false)
                                        ),
                                new SubcommandData("clear", "Remove every item from a slot.")
                                        .addOptions(new OptionData(OptionType.STRING, "code", "Slot code, like A1.", true)),
                                new SubcommandData("enable", "Enable a slot.")
                                        .addOptions(new OptionData(OptionType.STRING, "code", "Slot code, like A1.", true)),
                                new SubcommandData("disable", "Disable a slot.")
                                        .addOptions(new OptionData(OptionType.STRING, "code", "Slot code, like A1.", true)),
                                new SubcommandData("set-output", "Set the dispense output channel by ID.")
                                        .addOptions(new OptionData(OptionType.STRING, "channel-id", "Discord text channel ID.", true))
                        )
        );
    }

    private static MessageEmbed machineEmbed(Machine machine, MachineView view) {
        List<Slot> slots = enabledSlots(machine);
        Slot selectedSlot = selectedSlot(machine, view.selection());
        String preview = selectedSlot == null
                ? "Choose a row and number."
                : selectedSlot.getCode() + " - " + selectedSlot.getLabel();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(machine.getTitle())
                .setDescription(
                        "**Selection:** `" + displaySelection(view.selection()) + "`\n"
                                + "**Preview:** " + preview + "\n"
                                + "**Drop-box:** " + dropBoxLabel(view.latestDropUrl(), view.latestDropLabel())
                )
                .setColor(MACHINE_YELLOW)
                .setFooter(slots.size() + " slots stocked")
                .setTimestamp(Instant.now());

        if (view.latestDropUrl() != null && !view.latestDropUrl().isBlank()) {
            embed.setUrl(view.latestDropUrl());
        }

        if (selectedSlot != null) {
            String previewImageUrl = previewImageUrl(selectedSlot);
            if (!previewImageUrl.isBlank()) {
                embed.setThumbnail(previewImageUrl);
            }
        }
        return embed.build();
    }

    private static MessageEmbed dispenseEmbed(DispensedItem dispensed) {
        Slot slot = dispensed.slot();
        StockItem item = dispensed.item();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("CLUNK: " + slot.getCode() + " - " + slot.getLabel())
                .setDescription(item.getContent())
                .setColor(item.isRare() ? MACHINE_YELLOW : DROPBOX_GREEN)
                .setFooter(item.isRare() ? "Rare drop" : "Dispensed")
                .setTimestamp(Instant.now());

        if (item.getImageUrl() != null && !item.getImageUrl().isBlank()) {
            embed.setImage(item.getImageUrl());
        }
        return embed.build();
    }

    private static String normalizeImageUrl(String raw) {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.isBlank()) {
            return "";
        }

        java.util.regex.Matcher imgTagMatcher = IMG_TAG_PATTERN.matcher(normalized);
        if (imgTagMatcher.find()) {
            normalized = imgTagMatcher.group(1);
        }

        java.util.regex.Matcher markdownImageMatcher = MARKDOWN_IMAGE_PATTERN.matcher(normalized);
        if (markdownImageMatcher.find()) {
            normalized = markdownImageMatcher.group(1);
        }

        java.util.regex.Matcher markdownLinkMatcher = MARKDOWN_LINK_PATTERN.matcher(normalized);
        if (markdownLinkMatcher.find()) {
            normalized = markdownLinkMatcher.group(1);
        }

        normalized = normalized.trim()
                .replace("&amp;", "&");

        if (normalized.startsWith("<") && normalized.endsWith(">") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        if (!isHttpImageCandidate(normalized)) {
            return "";
        }

        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

            if ((host.equals("imgur.com") || host.equals("www.imgur.com") || host.equals("m.imgur.com"))) {
                String directImgur = toDirectImgurUrl(uri);
                if (!directImgur.isBlank()) {
                    return directImgur;
                }
            }

            if (host.equals("github.com") && uri.getPath() != null && uri.getPath().startsWith("/user-attachments/assets/")) {
                String query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase(Locale.ROOT);
                if (query.contains("raw=")) {
                    return normalized;
                }
                String separator = normalized.contains("?") ? "&" : "?";
                return normalized + separator + "raw=1";
            }
        } catch (IllegalArgumentException ignored) {
            return "";
        }

        return normalized;
    }

    private static boolean isHttpImageCandidate(String candidate) {
        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String toDirectImgurUrl(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().trim();
        if (path.isBlank() || path.equals("/")) {
            return "";
        }

        String[] parts = path.split("/");
        List<String> cleaned = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                cleaned.add(part);
            }
        }
        if (cleaned.isEmpty()) {
            return "";
        }

        String first = cleaned.getFirst();
        String imageId = switch (first) {
            case "a", "gallery", "t" -> cleaned.size() > 1 ? cleaned.get(1) : "";
            default -> first;
        };

        int dot = imageId.indexOf('.');
        if (dot > 0) {
            imageId = imageId.substring(0, dot);
        }

        if (!IMGUR_ID_PATTERN.matcher(imageId).matches()) {
            return "";
        }
        return "https://i.imgur.com/" + imageId + ".png";
    }

    private static List<ActionRow> keypadButtons(String selection) {
        boolean ready = completeSelection(selection);
        return List.of(
                ActionRow.of(
                        rowButton("A", selection),
                        rowButton("B", selection),
                        rowButton("C", selection)
                ),
                ActionRow.of(
                        columnButton("1", selection),
                        columnButton("2", selection),
                        columnButton("3", selection)
                ),
                ActionRow.of(
                        Button.success(DISPENSE_ID, "Dispense").withDisabled(!ready),
                        Button.secondary(CLEAR_ID, "Clear").withDisabled(selection == null || selection.equals("__"))
                )
        );
    }

    private static List<Slot> enabledSlots(Machine machine) {
        return machine.getSlots().values().stream()
                .filter(Slot::isEnabled)
                .sorted(Comparator.comparing(Slot::getCode))
                .toList();
    }

    private static Button rowButton(String row, String selection) {
        boolean selected = normalizeSelection(selection).charAt(0) == row.charAt(0);
        Button button = Button.primary(KEY_PREFIX + row, row);
        return selected ? button.withStyle(ButtonStyle.SUCCESS) : button;
    }

    private static Button columnButton(String column, String selection) {
        boolean selected = normalizeSelection(selection).charAt(1) == column.charAt(0);
        Button button = Button.primary(KEY_PREFIX + column, column);
        return selected ? button.withStyle(ButtonStyle.SUCCESS) : button;
    }

    private static String dropBoxLabel(String latestDropUrl, String latestDropLabel) {
        String label = latestDropLabel == null || latestDropLabel.isBlank() ? "latest drop below" : latestDropLabel;
        if (latestDropUrl == null || latestDropUrl.isBlank()) {
            return label;
        }
        return "[" + label + "](" + latestDropUrl + ")";
    }

    private static String previewImageUrl(Slot slot) {
        return slot.getItems().stream()
                .map(StockItem::getImageUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse("");
    }

    private static MachineView machineView(Message message) {
        if (message.getEmbeds().isEmpty()) {
            return MachineView.empty();
        }

        MessageEmbed embed = message.getEmbeds().getFirst();
        String description = embed.getDescription();
        if (description == null || description.isBlank()) {
            return MachineView.empty();
        }

        String selection = "__";
        java.util.regex.Matcher selectionMatcher = SELECTION_PATTERN.matcher(description);
        if (selectionMatcher.find()) {
            selection = normalizeSelection(selectionMatcher.group(1));
        }

        String latestDropUrl = "";
        String latestDropLabel = "latest drop below";
        java.util.regex.Matcher dropLinkMatcher = DROPBOX_LINK_PATTERN.matcher(description);
        if (dropLinkMatcher.find()) {
            latestDropLabel = dropLinkMatcher.group(1);
            latestDropUrl = dropLinkMatcher.group(2);
        } else {
            java.util.regex.Matcher dropTextMatcher = DROPBOX_TEXT_PATTERN.matcher(description);
            if (dropTextMatcher.find()) {
                latestDropLabel = dropTextMatcher.group(1).trim();
            }
        }

        return new MachineView(selection, latestDropUrl, latestDropLabel);
    }

    private static String applyKey(String selection, String key) {
        String normalized = normalizeSelection(selection);
        char row = normalized.charAt(0);
        char column = normalized.charAt(1);
        String upperKey = key == null ? "" : key.toUpperCase(Locale.ROOT);

        if (upperKey.matches("[A-C]")) {
            row = upperKey.charAt(0);
        } else if (upperKey.matches("[1-3]")) {
            column = upperKey.charAt(0);
        }
        return "" + row + column;
    }

    private static String normalizeSelection(String selection) {
        String safe = selection == null ? "__" : selection.toUpperCase(Locale.ROOT);
        char row = safe.length() > 0 && safe.charAt(0) >= 'A' && safe.charAt(0) <= 'C' ? safe.charAt(0) : '_';
        char column = safe.length() > 1 && safe.charAt(1) >= '1' && safe.charAt(1) <= '3' ? safe.charAt(1) : '_';
        return "" + row + column;
    }

    private static boolean completeSelection(String selection) {
        return normalizeSelection(selection).matches("[A-C][1-3]");
    }

    private static String displaySelection(String selection) {
        return normalizeSelection(selection).replace('_', '-');
    }

    private static Slot selectedSlot(Machine machine, String selection) {
        if (!completeSelection(selection)) {
            return null;
        }
        return machine.getSlots().get(normalizeSelection(selection));
    }

    private record MachineView(String selection, String latestDropUrl, String latestDropLabel) {
        static MachineView empty() {
            return new MachineView("__", "", "latest drop below");
        }

        MachineView withSelection(String selection) {
            return new MachineView(normalizeSelection(selection), latestDropUrl, latestDropLabel);
        }

        MachineView withLatestDrop(String latestDropUrl, String latestDropLabel) {
            return new MachineView(selection, latestDropUrl == null ? "" : latestDropUrl, latestDropLabel);
        }
    }

    public record BotConfig(String token, String guildId, String ownerId, Path dataPath) {
        public static BotConfig load() {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            String token = read(dotenv, "DISCORD_TOKEN", read(dotenv, "TOKEN", ""));
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Missing DISCORD_TOKEN. Copy .env.example to .env and add your bot token.");
            }

            return new BotConfig(
                    token.trim(),
                    read(dotenv, "GUILD_ID", "").trim(),
                    read(dotenv, "OWNER_ID", "").trim(),
                    Path.of(read(dotenv, "DATA_PATH", "data/machine.json"))
            );
        }

        private static String read(Dotenv dotenv, String key, String fallback) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
            value = dotenv.get(key);
            return value == null ? fallback : value;
        }
    }

    public record DispensedItem(Slot slot, StockItem item) {
    }

    public static final class MachineStore {
        private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        private final Path path;

        public MachineStore(Path path) {
            this.path = path;
        }

        public synchronized Machine loadOrCreateDefault() throws IOException {
            if (Files.notExists(path)) {
                Machine defaultMachine = Machine.defaultMachine();
                save(defaultMachine);
                return defaultMachine;
            }
            return mapper.readValue(path.toFile(), Machine.class);
        }

        public synchronized void save(Machine value) throws IOException {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            mapper.writeValue(path.toFile(), value);
        }
    }

    public static final class Machine {
        private String title = "VENDING MACHINE";
        private String inputChannelId = "";
        private String outputChannelId = "";
        private List<String> dispenseSequence = new ArrayList<>(List.of(
                "`coin inserted for {code}...`",
                "`gears turning...`",
                "`clunk.`"
        ));
        private Map<String, Slot> slots = new LinkedHashMap<>();

        public static Machine defaultMachine() {
            Machine machine = new Machine();
            machine.putDefaultSlot("A1", "Cats", "A tiny cat fact escaped the vending coil.");
            machine.putDefaultSlot("A2", "Dogs", "A good dog appears with absolutely no context.");
            machine.putDefaultSlot("A3", "Otters", "An otter has been dispensed. It looks busy.");
            machine.putDefaultSlot("B1", "Memes", "Fresh meme packet. Contents may settle during shipping.");
            machine.putDefaultSlot("B2", "Wholesome", "You are doing better than the machine expected.");
            machine.putDefaultSlot("B3", "Cringe", "This slot makes a worrying noise, then hands you cringe.");
            machine.putDefaultSlot("C1", "Quotes", "\"The machine believes in your bit.\"");
            machine.putDefaultSlot("C2", "Waifu", "A mysterious character card slides into the dropbox.");
            machine.putDefaultSlot("C3", "Frank", "Frank was here. Frank may still be here.");
            return machine;
        }

        private void putDefaultSlot(String code, String label, String content) {
            Slot slot = new Slot(code, label);
            slot.getItems().add(new StockItem(content, "", false, 1));
            slots.put(code, slot);
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getInputChannelId() {
            return inputChannelId;
        }

        public void setInputChannelId(String inputChannelId) {
            this.inputChannelId = inputChannelId;
        }

        public String getOutputChannelId() {
            return outputChannelId;
        }

        public void setOutputChannelId(String outputChannelId) {
            this.outputChannelId = outputChannelId;
        }

        public List<String> getDispenseSequence() {
            if (dispenseSequence == null || dispenseSequence.isEmpty()) {
                dispenseSequence = new ArrayList<>(List.of("`coin inserted for {code}...`", "`clunk.`"));
            }
            return dispenseSequence;
        }

        public void setDispenseSequence(List<String> dispenseSequence) {
            this.dispenseSequence = dispenseSequence;
        }

        public Map<String, Slot> getSlots() {
            if (slots == null) {
                slots = new LinkedHashMap<>();
            }
            return slots;
        }

        public void setSlots(Map<String, Slot> slots) {
            this.slots = slots;
        }
    }

    public static final class Slot {
        private String code = "";
        private String label = "";
        private boolean enabled = true;
        private long dispensed = 0;
        private List<StockItem> items = new ArrayList<>();

        public Slot() {
        }

        public Slot(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getDispensed() {
            return dispensed;
        }

        public void setDispensed(long dispensed) {
            this.dispensed = dispensed;
        }

        public List<StockItem> getItems() {
            if (items == null) {
                items = new ArrayList<>();
            }
            return items;
        }

        public void setItems(List<StockItem> items) {
            this.items = items;
        }
    }

    public static final class StockItem {
        private String content = "";
        private String imageUrl = "";
        private boolean rare = false;
        private int weight = 1;

        public StockItem() {
        }

        public StockItem(String content, String imageUrl, boolean rare, int weight) {
            this.content = content;
            this.imageUrl = imageUrl;
            this.rare = rare;
            this.weight = weight;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
        }

        public boolean isRare() {
            return rare;
        }

        public void setRare(boolean rare) {
            this.rare = rare;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }
    }
}
