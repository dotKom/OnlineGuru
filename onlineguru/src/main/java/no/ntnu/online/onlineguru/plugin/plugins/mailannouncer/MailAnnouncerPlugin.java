package no.ntnu.online.onlineguru.plugin.plugins.mailannouncer;

import no.fictive.irclib.event.container.Event;
import no.fictive.irclib.event.container.command.PrivMsgEvent;
import no.fictive.irclib.event.model.EventType;
import no.ntnu.online.onlineguru.OnlineGuru;
import no.ntnu.online.onlineguru.plugin.control.EventDistributor;
import no.ntnu.online.onlineguru.plugin.model.Plugin;
import no.ntnu.online.onlineguru.plugin.model.PluginWithDependencies;
import no.ntnu.online.onlineguru.plugin.plugins.flags.FlagsPlugin;
import no.ntnu.online.onlineguru.plugin.plugins.flags.model.Flag;
import no.ntnu.online.onlineguru.plugin.plugins.help.HelpPlugin;
import no.ntnu.online.onlineguru.plugin.plugins.mailannouncer.listeners.MailCallbackListener;
import no.ntnu.online.onlineguru.plugin.plugins.mailannouncer.listeners.MailCallbackManager;
import no.ntnu.online.onlineguru.service.services.webserver.Webserver;
import no.ntnu.online.onlineguru.utils.JSONStorage;
import no.ntnu.online.onlineguru.utils.Wand;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Håvard Slettvold
 */
public class MailAnnouncerPlugin implements PluginWithDependencies {

    private Wand wand;

    private FlagsPlugin flagsPlugin;

    private final String database_folder = "database/";
    private final String database_file = database_folder + "mail.json";

    private final Flag editorFlag = Flag.A;
    private final Flag peekingFlag = Flag.a;

    private MailCallback mailCallback;
    private MailCallbackManager mailCallbackManager;

    private Pattern commandPattern = Pattern.compile(
            // Due to the way this regex is designed it will also let through !mail alias <mailinglist> <alias>
            "!mail" +                      // Trigger
            " (\\S+)" +                    // Mailinglist, group 1
            "(?: (#\\S+))?" +              // Channel, group 2
            "(?: (on|off))?",              // Setting, group 3
            Pattern.CASE_INSENSITIVE
    );

    public MailAnnouncerPlugin() {
        // This needs to be initiated here for testing purposes, deal with it.
        mailCallbackManager = new MailCallbackManager();
    }

    @Override
    public String[] getDependencies() {
        // Doing this setup in a method that is solely called by the PluginManager once.
        // The reason is we do not want to execute these actions during tests.
        mailCallbackManager = (MailCallbackManager) JSONStorage.load(database_file, MailCallbackManager.class);
        if (mailCallbackManager == null) {
            mailCallbackManager = new MailCallbackManager();
        }
        mailCallback = new MailCallback(wand, mailCallbackManager);

        // Running the registering with the web server async, it may take time.
        new Thread() {
            @Override
            public void run() {
                // Fetch the webserver instance.
                Webserver webServer = OnlineGuru.serviceLocator.getInstance(Webserver.class);
                // Register this plugins uri.
                webServer.registerWebserverCallback("/plugins/mail", mailCallback);
            }
        }.start();

        // Return dependencies.
        return new String[]{"FlagsPlugin", "HelpPlugin", };
    }

    @Override
    public void loadDependency(Plugin plugin) {
        if (plugin instanceof FlagsPlugin) this.flagsPlugin = (FlagsPlugin) plugin;
        if (plugin instanceof HelpPlugin) {
            HelpPlugin helpPlugin = (HelpPlugin) plugin;
            helpPlugin.addHelp(
                    "!mail",
                    Flag.a,
                    "!mail <list> [on/off] - Turns announcing for list on or off. Or gives information if last parameter is left out.",
                    "!mail alias <list> <alias> - Changes the way lists are being displayed when announced. leave out <alias> to see information.");
        }
    }

    @Override
    public String getDescription() {
        return "Takes requests from the email-script running on dworek and announces incoming mail on irc.";
    }

    @Override
    public void incomingEvent(Event e) {
        if (e.getEventType() == EventType.PRIVMSG) {
            PrivMsgEvent pme = (PrivMsgEvent) e;
            String reply = handleCommand(pme);
            if (reply != null) {
                // Save the config. Not all calls to this method needs a save, but
                // distinguishing each of the calls from eachother is more complicated than
                // just saving in any case.
                // Saving is done in framework-invoked medthods in order to separate out
                // methods for testing and not overriding prod storage when testing.
                JSONStorage.save(database_file, mailCallbackManager);

                wand.sendMessageToTarget(pme.getNetwork(), pme.getTarget(), "[mail] " + reply);
            }
        }
    }

    protected String handleCommand(PrivMsgEvent e) {
        String message = e.getMessage().toLowerCase();
        if (!message.startsWith("!mail")) return null;

        // If the command is regarding aliases, handle it in another method.
        if (message.startsWith("!mail alias")) {
            return handleAliases(e);
        }

        Matcher matcher = commandPattern.matcher(message);
        if (!matcher.find()) return null;

        // Find a channel, or return an error.
        String channel = matcher.group(2);
        if (channel == null) {
            if (e.isChannelMessage()) {
                channel = e.getChannel();
            }
            else {
                return "You must specify a channel when using this command in private messages.";
            }
        }

        // Check if the user has permissions to access.
        Set<Flag> flags = flagsPlugin.getFlags(e.getNetwork(), channel, e.getSender());
        if (!flags.contains(peekingFlag) && !flags.contains(Flag.A)) {
            return "You do not have access to use this command. +a is required to view subscriptions and +A to edit.";
        }

        String mailinglist = matcher.group(1).toLowerCase();
        String setting = matcher.group(3);

        // Get the callback listener for the specified mailinglist.
        MailCallbackListener mcl = mailCallbackManager.get(mailinglist);
        // If it doesn't exist, create a new one.
        if (mcl == null) {
            mcl = new MailCallbackListener();
            mailCallbackManager.put(mailinglist, mcl);
        }

        if (matcher.group(3) == null) {
            if (mcl.isSubscribed(e.getNetwork(), channel)) {
                return channel + " is subscribed to '" + mailinglist + "'.";
            }
            return channel + " is not subscribed to '" + mailinglist + "'.";
        }

        if (!flags.contains(editorFlag)) {
            return "You do not have access to use this command. It requires +A to edit subscriptions.";
        }

        if (matcher.group(3).equals("on")) {
            if (!mcl.createSubscription(e.getNetwork(), channel)) {
                return channel + " is already subscribed to '" + mailinglist + "'.";
            }
            return channel + " is now subscribed to '" + mailinglist + "'.";
        }
        else if (matcher.group(3).equals("off")) {
            if (!mcl.deleteSubscription(e.getNetwork(), channel)) {
                return channel + " is not subscribed to '" + mailinglist + "'.";
            }
            return channel + " is no longer subscribed to '" + mailinglist + "'.";
        }

        return null;
    }

    protected String handleAliases(PrivMsgEvent e) {
        String[] messageParts = e.getMessage().split(" ");

        // If there are not enough parameters, abort.
        if (messageParts.length < 3) return null;

        // Messages to this trigger cannot have more than 4 words.
        if (messageParts.length > 4) return null;

        Set<Flag> flags = flagsPlugin.getFlags(e.getNetwork(), e.getSender());
        // If it got here it means that the message had 4 words. Check if the user has editor access.
        if (!flags.contains(editorFlag) && !flags.contains(Flag.A)) {
            return "You do not have access to use this command. +a is required to view aliases and +A to edit.";
        }

        // If the length is less than 4 it's an informational call.
        // !mail alias <mailinglistOrAlias>
        if (messageParts.length < 4) {
            String mailinglistOrAlias = messageParts[2];
            String alias = mailCallbackManager.getAlias(mailinglistOrAlias);
            String original = mailCallbackManager.getOriginal(mailinglistOrAlias);
            if (alias != null) {
                return "Match found as mailing list: "+ mailinglistOrAlias +" -> "+ alias;
            }
            else if (original != null) {
                return "Match found as alias: "+ original +" -> "+ mailinglistOrAlias;
            }
            else {
                return "No record found for '"+ mailinglistOrAlias +"'.";
            }
        }

        // If it got here it means that the message had 4 words. Check if the user has editor access.
        if (!flags.contains(editorFlag) && !flags.contains(Flag.A)) {
            return "You do not have access to use this command. +A is required to edit.";
        }

        mailCallbackManager.addAlias(messageParts[2], messageParts[3]);
        return "Added alias: "+ messageParts[2] +" -> "+ messageParts[3];
    }

    @Override
    public void addEventDistributor(EventDistributor eventDistributor) {
        eventDistributor.addListener(this, EventType.PRIVMSG);
    }

    @Override
    public void addWand(Wand wand) {
        this.wand = wand;
    }

}
