package no.ntnu.online.onlineguru;

import no.fictive.irclib.model.user.Profile;
import no.ntnu.online.onlineguru.exceptions.MalformedSettingsException;
import no.ntnu.online.onlineguru.exceptions.MissingSettingsException;
import no.ntnu.online.onlineguru.utils.SimpleIO;
import no.ntnu.online.onlineguru.utils.settingsreader.Settings;
import no.ntnu.online.onlineguru.utils.settingsreader.SettingsReader;
import org.apache.log4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class VerifySettings {
    static Logger logger = Logger.getLogger(VerifySettings.class);
    private static final String settings_folder = "settings/";
    private static final String settings_file = settings_folder + "settings.conf";

    private static List<ConnectionInformation> conInfoList;

    protected static List<ConnectionInformation> readSettings() {

        conInfoList = new ArrayList<ConnectionInformation>();
        List<Settings> settingsList = null;

        try {
            settingsList = SettingsReader.readSettings(settings_file);
            makeConnectionInformation(settingsList);

        } catch (MalformedSettingsException mse) {

            logger.error(mse.getError(), mse);
            System.exit(1);

        } catch (MissingSettingsException e) {
            try {
                if (settingsList == null) {
                    createSettings();
                    throw new MissingSettingsException("Populate settings.conf before running.");
                }

            } catch (IOException ioE) {
                logger.error("I/O error", ioE);
                System.exit(1);
            } catch (MissingSettingsException mse) {
                logger.error(e.getError(), e);
                System.exit(1);
            }
        }
        return conInfoList;
    }

    private static void makeConnectionInformation(List<Settings> settingsList) {
        ConnectionInformation connectionInformation;
        Profile profile;

        for (Settings settings : settingsList) {
            try {
                connectionInformation = new ConnectionInformation();
                connectionInformation.setServeralias(settings.getSetting("server_alias"));
                connectionInformation.setHostname(settings.getSetting("hostname"));
                connectionInformation.setPort(settings.getSetting("port"));
                connectionInformation.setIpv6(Boolean.parseBoolean(settings.getSetting("ipv6")));

                String bindAddress = settings.getSetting("bind_address");
                if (bindAddress != null && !bindAddress.isEmpty()) {
                    connectionInformation.setBindAddress(bindAddress);
                }


                String channels = settings.getSetting("channels");
                if (channels != null) {
                    for (String channel : channels.split(",")) {
                        connectionInformation.addChannel(channel);
                    }
                }

                profile = makeProfile(settings);
                connectionInformation.setProfile(profile);

                if (connectionInformation.isValid() && profile.isValid()) {
                    conInfoList.add(connectionInformation);
                    logger.info("Settings are in order, connecting..");
                }
                else {
                    logger.error("Settings need to be populated properly.");
                }
            } catch (UnknownHostException unknownHostException) {
                logger.error(String.format("Can't bind to selected hostname, skipping this connection entry. exception '%s'", unknownHostException.getMessage()));
            }
        }
    }

    private static Profile makeProfile(Settings settings) {
        Profile profile = new Profile(
                settings.getSetting("nickname"),
                settings.getSetting("alt_nickname"),
                settings.getSetting("realname"),
                settings.getSetting("ident"),
                settings.getSetting("email")
        );

        profile.setQuitMessage(settings.getSetting("quitmsg"));
        return profile;
    }

    protected static void createSettings() throws IOException {
        SimpleIO.createFolder(settings_folder);
        SimpleIO.createFile(settings_file);

        File file = new File(settings_file);
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));

        writer.write("[network]\n");
        writer.write("server_alias=\n");
        writer.write("hostname=\n");
        writer.write("port=\n");
        writer.write("ipv6=false\n");
        writer.write("#bind_address=\n");
        writer.write("nickname=\n");
        writer.write("alt_nickname=\n");
        writer.write("ident=\n");
        writer.write("realname=\n");
        writer.write("email=\n");
        writer.write("quitmsg=\n");
        writer.write("channels=\n");
        writer.write("# Example:\n");
        writer.write("#channels=#online, #channelwithkey keyhere\n");
        writer.close();
    }

}
