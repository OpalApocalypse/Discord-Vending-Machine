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
    private static final String BUTTON_PREFIX = "vend:";
    private static final Pattern SLOT_CODE = Pattern.compile("[A-Z][0-9]{1,2}");
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
        if (!id.startsWith(BUTTON_PREFIX)) {
            return;
        }

        String code = id.substring(BUTTON_PREFIX.length());
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
        event.replyEmbeds(machineEmbed(machine()))
                .setComponents(slotButtons(machine()))
                .queue();
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
        String imageUrl = event.getOption("image-url") == null ? "" : Objects.requireNonNull(event.getOption("image-url")).getAsString();
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
                            "`clunk.`\n[Open the " + dispensed.slot().getCode() + " drop-box post](" + delivered.getJumpUrl() + ")"
                    );
                    onDelivered.accept(delivered);
                }, failure -> replyPrivately(hook, "The drop-box jammed while delivering the item. Check the bot logs."));
    }

    private void updateMachineDropBox(Message machineMessage, DispensedItem dispensed, Message delivered) {
        machineMessage.editMessageEmbeds(machineEmbed(machine(), delivered.getJumpUrl(), dispensed))
                .setComponents(slotButtons(machine()))
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

    private static MessageEmbed machineEmbed(Machine machine) {
        return machineEmbed(machine, "", null);
    }

    private static MessageEmbed machineEmbed(Machine machine, String latestDropUrl, DispensedItem latestDrop) {
        StringBuilder display = new StringBuilder();
        display.append("+-------------------------------+\n");
        display.append("|       DISCORD VENDING         |\n");
        display.append("+---------+---------+---------+\n");

        List<Slot> slots = enabledSlots(machine);
        for (int i = 0; i < slots.size(); i += 3) {
            List<Slot> row = slots.subList(i, Math.min(i + 3, slots.size()));
            display.append("|");
            for (int column = 0; column < 3; column++) {
                appendCell(display, column < row.size() ? row.get(column).getCode() : "");
            }
            display.append("\n|");
            for (int column = 0; column < 3; column++) {
                appendCell(display, column < row.size() ? row.get(column).getLabel() : "");
            }
            display.append("\n+---------+---------+---------+\n");
        }

        display.append("|          DROP-BOX             |\n");
        display.append("| ").append(pad(dropBoxLabel(latestDrop), 29)).append(" |\n");
        display.append("+-------------------------------+\n");

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(machine.getTitle())
                .setDescription("```text\n" + display + "```\nUse `/vend code` or press a slot button.")
                .setColor(MACHINE_YELLOW)
                .setFooter(slots.size() + " slots stocked")
                .setTimestamp(Instant.now());

        if (latestDropUrl != null && !latestDropUrl.isBlank() && latestDrop != null) {
            embed.setUrl(latestDropUrl);
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

    private static List<ActionRow> slotButtons(Machine machine) {
        List<Slot> slots = enabledSlots(machine);
        List<ActionRow> rows = new ArrayList<>();
        int visibleSlots = Math.min(slots.size(), 25);

        for (int i = 0; i < visibleSlots; i += 3) {
            List<Button> row = new ArrayList<>();
            for (Slot slot : slots.subList(i, Math.min(i + 3, visibleSlots))) {
                row.add(Button.primary(BUTTON_PREFIX + slot.getCode(), buttonLabel(slot)));
            }
            rows.add(ActionRow.of(row));
        }
        return rows;
    }

    private static List<Slot> enabledSlots(Machine machine) {
        return machine.getSlots().values().stream()
                .filter(Slot::isEnabled)
                .sorted(Comparator.comparing(Slot::getCode))
                .toList();
    }

    private static String buttonLabel(Slot slot) {
        String label = slot.getCode() + " - " + slot.getLabel();
        return label.length() <= 80 ? label : label.substring(0, 77) + "...";
    }

    private static String dropBoxLabel(DispensedItem latestDrop) {
        if (latestDrop == null) {
            return "latest drop below";
        }
        return latestDrop.slot().getCode() + " clunked over here";
    }

    private static void appendCell(StringBuilder display, String value) {
        display.append(" ").append(pad(value, 7)).append(" |");
    }

    private static String pad(String value, int width) {
        String safe = value == null ? "" : value;
        if (safe.length() > width) {
            return safe.substring(0, width);
        }
        return safe + " ".repeat(width - safe.length());
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
