package no.ntnu.online.onlineguru.plugin.control;

import no.ntnu.online.onlineguru.OnlineGuru;
import no.ntnu.online.onlineguru.plugin.model.Plugin;
import no.ntnu.online.onlineguru.utils.Functions;
import no.ntnu.online.onlineguru.utils.IrcWand;
import no.ntnu.online.onlineguru.utils.SimpleIO;
import no.ntnu.online.onlineguru.utils.Wand;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.*;

public class PluginManager {

    private HashMap<String, Plugin> loadedPlugins;
    private EventDistributor eventDistributor;
    private Wand wand;

    private static String SETTINGS_FOLDER = "settings/";
    private static String SETTINGS_FILE = SETTINGS_FOLDER + "plugins.conf";

    static Logger logger = Logger.getLogger(PluginManager.class);

    public PluginManager(EventDistributor eventDistributor, OnlineGuru onlineguru) {
        loadedPlugins = new HashMap<String, Plugin>();
        this.eventDistributor = eventDistributor;
        wand = new IrcWand(onlineguru, this);

        Set<String> activePlugins = new HashSet<String>();
        try {
            activePlugins.addAll(SimpleIO.readFileAsList(SETTINGS_FILE));
        } catch (IOException e) {
            logger.error(e);
            logger.error(String.format("Could not find any plugin settings, please make %s", SETTINGS_FILE));
            System.exit(1);
        }
        if (activePlugins.size() < 1) {
            activePlugins = loadMinimalAndEssentialPlugins();
            try {
                SimpleIO.appendLinesToFile(SETTINGS_FILE, new ArrayList<String>(activePlugins));
            } catch (IOException e) {
                logger.error(e);
                logger.error(String.format("Could not save minimal and essential plugins to %s", SETTINGS_FILE));
            }
        }

        loadPlugins(activePlugins);
        loadDependencies();
    }

    private Set<String> loadMinimalAndEssentialPlugins() {
        logger.warn("Loading minimal and essential plugins for the bot to run, please edit plugins.conf to add more plugins");
        return new HashSet<String>(
                Arrays.asList(
                        "no.ntnu.online.onlineguru.plugin.plugins.auth.AuthPlugin",
                        "no.ntnu.online.onlineguru.plugin.plugins.chanserv.control.ChanServ",
                        "no.ntnu.online.onlineguru.plugin.plugins.channeljoiner.ChannelJoinerPlugin",
                        "no.ntnu.online.onlineguru.plugin.plugins.die.DiePlugin",
                        "no.ntnu.online.onlineguru.plugin.plugins.help.HelpPlugin",
                        "no.ntnu.online.onlineguru.plugin.plugins.nickserv.NickServPlugin",
                        "no.ntnu.online.onlineguru.plugin.plugins.version.VersionPlugin"
                )
        );

    }

    private void loadPlugins(Set<String> activePlugins) {
        for (String plugin : activePlugins) {
            // Additional newlines in the plugins.conf file would result in empty plugins.
            if (plugin.isEmpty()) { continue; }

            // Lines starting with "#" should be ignored, so we can comment.
            if (plugin.startsWith("#")) { continue; }

            try {
                initiatePlugin((Plugin) Class.forName(plugin).newInstance());
            } catch (InstantiationException e) {
                logger.error("Failed to load plugin: " + plugin, e);
                System.exit(2);
            } catch (IllegalAccessException e) {
                logger.error("Failed to load plugin: " + plugin, e);
                System.exit(2);
            } catch (ClassNotFoundException e) {
                logger.error("Failed to load plugin: " + plugin, e);
                System.exit(2);
            }
        }
    }

    private void initiatePlugin(Plugin plugin) {
        plugin.addEventDistributor(eventDistributor);
        plugin.addWand(wand);
        loadedPlugins.put(Functions.getClassName(plugin).toUpperCase(), plugin);
    }

    private void loadDependencies() {
        new DependencyManager(loadedPlugins);
    }

    public Plugin getPlugin(String pluginClassName) {
        pluginClassName = pluginClassName.toUpperCase();
        if (loadedPlugins.containsKey(pluginClassName)) {
            return loadedPlugins.get(pluginClassName);
        } else {
            return null;
        }
    }
}
