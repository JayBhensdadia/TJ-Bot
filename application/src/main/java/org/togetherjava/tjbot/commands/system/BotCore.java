package org.togetherjava.tjbot.commands.system;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.interaction.ButtonClickEvent;
import net.dv8tion.jda.api.events.interaction.SelectionMenuEvent;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageUpdateEvent;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.togetherjava.tjbot.commands.*;
import org.togetherjava.tjbot.commands.componentids.ComponentId;
import org.togetherjava.tjbot.commands.componentids.ComponentIdParser;
import org.togetherjava.tjbot.commands.componentids.ComponentIdStore;
import org.togetherjava.tjbot.commands.componentids.InvalidComponentIdFormatException;
import org.togetherjava.tjbot.config.Config;
import org.togetherjava.tjbot.db.Database;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The bot core is the core of command handling in this application.
 * <p>
 * It knows and manages all commands, registers them towards Discord and is the entry point of all
 * events. It forwards events to their corresponding commands and does the heavy lifting on all sort
 * of event parsing.
 * <p>
 * <p>
 * Commands are made available via {@link Features}, then the system has to be added to JDA as an
 * event listener, using {@link net.dv8tion.jda.api.JDA#addEventListener(Object...)}. Afterwards,
 * the system is ready and will correctly forward events to all commands.
 */
public final class BotCore extends ListenerAdapter implements SlashCommandProvider {
    private static final Logger logger = LoggerFactory.getLogger(BotCore.class);
    private static final String RELOAD_COMMAND = "reload";
    private static final ExecutorService COMMAND_SERVICE = Executors.newCachedThreadPool();
    private final Map<String, SlashCommand> nameToSlashCommands;
    private final ComponentIdParser componentIdParser;
    private final ComponentIdStore componentIdStore;

    /**
     * Creates a new command system which uses the given database to allow commands to persist data.
     * <p>
     * Commands are fetched from {@link Features}.
     *
     * @param jda the JDA instance that this command system will be used with
     * @param database the database that commands may use to persist data
     */
    @SuppressWarnings("ThisEscapedInObjectConstruction")
    public BotCore(@NotNull JDA jda, @NotNull Database database) {
        Collection<Feature> features = Features.createFeatures(jda, database);

        // Message receivers
        features.stream()
            .filter(MessageReceiver.class::isInstance)
            .map(MessageReceiver.class::cast)
            .map(MessageReceiverAsEventListener::new)
            .forEach(jda::addEventListener);

        // Event receivers
        features.stream()
            .filter(EventReceiver.class::isInstance)
            .map(EventReceiver.class::cast)
            .forEach(jda::addEventListener);

        // Slash commands
        nameToSlashCommands = features.stream()
            .filter(SlashCommand.class::isInstance)
            .map(SlashCommand.class::cast)
            .collect(Collectors.toMap(SlashCommand::getName, Function.identity()));

        if (nameToSlashCommands.containsKey(RELOAD_COMMAND)) {
            throw new IllegalStateException(
                    "The 'reload' command is a special reserved command that must not be used by other commands");
        }
        nameToSlashCommands.put(RELOAD_COMMAND, new ReloadCommand(this));

        componentIdStore = new ComponentIdStore(database);
        componentIdStore.addComponentIdRemovedListener(BotCore::onComponentIdRemoved);
        componentIdParser = uuid -> componentIdStore.get(UUID.fromString(uuid));
        nameToSlashCommands.values()
            .forEach(slashCommand -> slashCommand
                .acceptComponentIdGenerator(((componentId, lifespan) -> {
                    UUID uuid = UUID.randomUUID();
                    componentIdStore.putOrThrow(uuid, componentId, lifespan);
                    return uuid.toString();
                })));

        if (logger.isInfoEnabled()) {
            logger.info("Available commands: {}", nameToSlashCommands.keySet());
        }
    }

    @Override
    public @NotNull Collection<SlashCommand> getSlashCommands() {
        return Collections.unmodifiableCollection(nameToSlashCommands.values());
    }

    @Override
    public @NotNull Optional<SlashCommand> getSlashCommand(@NotNull String name) {
        return Optional.ofNullable(nameToSlashCommands.get(name));
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        // Register reload on all guilds
        logger.debug("JDA is ready, registering reload command");
        event.getJDA()
            .getGuildCache()
            .forEach(guild -> COMMAND_SERVICE.execute(() -> registerReloadCommand(guild)));
        // NOTE We do not have to wait for reload to complete for the command system to be ready
        // itself
        logger.debug("Bot core is now ready");
    }

    @Override
    public void onSlashCommand(@NotNull SlashCommandEvent event) {
        logger.debug("Received slash command '{}' (#{}) on guild '{}'", event.getName(),
                event.getId(), event.getGuild());
        COMMAND_SERVICE.execute(() -> requireSlashCommand(event.getName()).onSlashCommand(event));
    }

    @Override
    public void onButtonClick(@NotNull ButtonClickEvent event) {
        logger.debug("Received button click '{}' (#{}) on guild '{}'", event.getComponentId(),
                event.getId(), event.getGuild());
        COMMAND_SERVICE.execute(() -> forwardComponentCommand(event, SlashCommand::onButtonClick));
    }

    @Override
    public void onSelectionMenu(@NotNull SelectionMenuEvent event) {
        logger.debug("Received selection menu event '{}' (#{}) on guild '{}'",
                event.getComponentId(), event.getId(), event.getGuild());
        COMMAND_SERVICE
            .execute(() -> forwardComponentCommand(event, SlashCommand::onSelectionMenu));
    }

    private void registerReloadCommand(@NotNull Guild guild) {
        guild.retrieveCommands().queue(commands -> {
            // Has it been registered already?
            if (commands.stream().map(Command::getName).anyMatch(RELOAD_COMMAND::equals)) {
                logger.debug("Command '{}' has already been registered for guild '{}'",
                        RELOAD_COMMAND, guild.getName());
                return;
            }

            logger.debug("Register '{}' for guild '{}'", RELOAD_COMMAND, guild.getName());
            SlashCommand reloadCommand = requireSlashCommand(RELOAD_COMMAND);
            guild.upsertCommand(reloadCommand.getData())
                .queue(command -> logger.debug("Registered '{}' for guild '{}'", RELOAD_COMMAND,
                        guild.getName()));
        }, ex -> handleRegisterErrors(ex, guild));
    }

    /**
     * Forwards the given component event to the associated slash command.
     * <p>
     * <p>
     * An example call might look like:
     *
     * <pre>
     * {@code
     * forwardComponentCommand(event, SlashCommand::onSelectionMenu);
     * }
     * </pre>
     *
     * @param event the component event that should be forwarded
     * @param commandArgumentConsumer the action to trigger on the associated slash command,
     *        providing the event and list of arguments for consumption
     * @param <T> the type of the component interaction that should be forwarded
     */
    private <T extends ComponentInteraction> void forwardComponentCommand(@NotNull T event,
            @NotNull TriConsumer<? super SlashCommand, ? super T, ? super List<String>> commandArgumentConsumer) {
        Optional<ComponentId> componentIdOpt;
        try {
            componentIdOpt = componentIdParser.parse(event.getComponentId());
        } catch (InvalidComponentIdFormatException e) {
            logger
                .error("Unable to route event (#{}) back to its corresponding slash command. The component ID was in an unexpected format."
                        + " All button and menu events have to use a component ID created in a specific format"
                        + " (refer to the documentation of SlashCommand). Component ID was: {}",
                        event.getId(), event.getComponentId(), e);
            // Unable to forward, simply fade out the event
            return;
        }
        if (componentIdOpt.isEmpty()) {
            logger.warn("The event (#{}) has an expired component ID, which was: {}.",
                    event.getId(), event.getComponentId());
            event.reply("Sorry, but this event has expired. You can not use it anymore.")
                .setEphemeral(true)
                .queue();
            return;
        }
        ComponentId componentId = componentIdOpt.orElseThrow();

        SlashCommand command = requireSlashCommand(componentId.commandName());
        logger.trace("Routing a component event with id '{}' back to command '{}'",
                event.getComponentId(), command.getName());
        commandArgumentConsumer.accept(command, event, componentId.elements());
    }

    /**
     * Gets the given slash command by its name and requires that it exists.
     *
     * @param name the name of the command to get
     * @return the command with the given name
     * @throws NullPointerException if the command with the given name was not registered
     */
    private @NotNull SlashCommand requireSlashCommand(@NotNull String name) {
        return Objects.requireNonNull(nameToSlashCommands.get(name));
    }

    private static void handleRegisterErrors(Throwable ex, Guild guild) {
        new ErrorHandler().handle(ErrorResponse.MISSING_ACCESS, errorResponse -> {
            // Find a channel that we have permissions to write to
            // NOTE Unfortunately, there is no better accurate way to find a proper channel
            // where we can report the setup problems other than simply iterating all of them.
            Optional<TextChannel> channelToReportTo = guild.getTextChannelCache()
                .stream()
                .filter(channel -> guild.getPublicRole()
                    .hasPermission(channel, Permission.MESSAGE_WRITE))
                .findAny();

            // Report the problem to the guild
            Config config = Config.getInstance();
            channelToReportTo.ifPresent(textChannel -> textChannel
                .sendMessage("I need the commands scope, please invite me correctly."
                        + " You can join '%s' or visit '%s' for more info, I will leave your guild now."
                            .formatted(config.getDiscordGuildInvite(), config.getProjectWebsite()))
                .queue());

            guild.leave().queue();

            String unableToReportText = channelToReportTo.isPresent() ? ""
                    : " Did not find any public text channel to report the issue to, unable to inform the guild.";
            logger.warn(
                    "Guild '{}' does not have the required command scope, unable to register, leaving it.{}",
                    guild.getName(), unableToReportText, ex);
        }).accept(ex);
    }

    @SuppressWarnings("EmptyMethod")
    private static void onComponentIdRemoved(ComponentId componentId) {
        // NOTE As of now, we do not act on this event, but we could use it
        // in the future to, for example, disable buttons or delete the associated message
    }

    /**
     * Extension of {@link java.util.function.BiConsumer} but for 3 elements.
     * <p>
     * Represents an operation that accepts three input arguments and returns no result. This is the
     * three-arity specialization of {@link java.util.function.Consumer}. Unlike most other
     * functional interfaces, TriConsumer is expected to operate via side effects.
     *
     * @param <A> the type of the first argument to the operation
     * @param <B> the type of the second argument to the operation
     * @param <C> the type of the third argument to the operation
     */
    @FunctionalInterface
    private interface TriConsumer<A, B, C> {
        /**
         * Performs this operation on the given arguments.
         *
         * @param first the first input argument
         * @param second the second input argument
         * @param third the third input argument
         */
        void accept(A first, B second, C third);
    }

    private static final class MessageReceiverAsEventListener implements EventListener {
        private final MessageReceiver messageReceiver;

        MessageReceiverAsEventListener(MessageReceiver messageReceiver) {
            this.messageReceiver = messageReceiver;
        }

        @SuppressWarnings("squid:S2583") // False-positive about the if-else-instanceof, sonar
                                         // thinks the second case is unreachable; but it passes
                                         // without pattern-matching. Probably a bug in SonarLint
                                         // with Java 17.
        @Override
        public void onEvent(@NotNull GenericEvent event) {
            if (event instanceof GuildMessageReceivedEvent receivedEvent) {
                messageReceiver.onMessageReceived(receivedEvent);
            } else if (event instanceof GuildMessageUpdateEvent updateEvent) {
                messageReceiver.onMessageUpdated(updateEvent);
            }
        }
    }
}