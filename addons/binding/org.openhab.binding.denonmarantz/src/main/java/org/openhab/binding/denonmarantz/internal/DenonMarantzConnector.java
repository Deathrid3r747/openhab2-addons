/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.denonmarantz.internal;

import java.beans.Introspector;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.UnmarshalException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.util.StreamReaderDelegate;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.smarthome.core.library.types.IncreaseDecreaseType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.denonmarantz.config.DenonMarantzConfiguration;
import org.openhab.binding.denonmarantz.internal.communication.entities.Deviceinfo;
import org.openhab.binding.denonmarantz.internal.communication.entities.Main;
import org.openhab.binding.denonmarantz.internal.communication.entities.ZoneStatus;
import org.openhab.binding.denonmarantz.internal.communication.entities.ZoneStatusLite;
import org.openhab.binding.denonmarantz.internal.communication.entities.commands.AppCommandRequest;
import org.openhab.binding.denonmarantz.internal.communication.entities.commands.AppCommandResponse;
import org.openhab.binding.denonmarantz.internal.communication.entities.commands.CommandRx;
import org.openhab.binding.denonmarantz.internal.communication.entities.commands.CommandTx;
import org.openhab.binding.denonmarantz.internal.telnet.DenonMarantzTelnetClient;
import org.openhab.binding.denonmarantz.internal.telnet.DenonMarantzTelnetListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class makes the connection to the receiver and manages it.
 * It is also responsible for sending commands to the receiver.
 *
 * @author Jan-Willem Veldhuis
 * @author Jeroen Idserda - Initial Contribution (1.x Binding)
 */
public class DenonMarantzConnector implements DenonMarantzTelnetListener {

    private static final Logger logger = LoggerFactory.getLogger(DenonMarantzConnector.class);

    private static final int REQUEST_TIMEOUT_MS = 5000; // 5 seconds

    // All regular commands. Example: PW, SICD, SITV, Z2MU
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^([A-Z0-9]{2})(.+)$");

    // Matches all secondary zone commands with a parameter. Example: Z2TUNER
    private static final Pattern ZONE_SUBCOMMAND_PATTERN = Pattern
            .compile("^Z[0-9]{1}((?!ON|OFF|UP|DOWN)([A-Z]){2,})$");

    // Example: E2Counting Crows
    private static final Pattern DISPLAY_PATTERN = Pattern.compile("^(E|A)([0-9]{1})(.+)$");

    // Main URL for the receiver
    private static final String URL_MAIN = "formMainZone_MainZoneXml.xml";

    // Main Zone Status URL
    private static final String URL_ZONE_MAIN = "formMainZone_MainZoneXmlStatus.xml";

    // Secondary zone lite status URL (contains less info)
    private static final String URL_ZONE_SECONDARY_LITE = "formZone%d_Zone%dXmlStatusLite.xml";

    // Device info URL
    private static final String URL_DEVICE_INFO = "Deviceinfo.xml";

    // URL to send app commands to
    private static final String URL_APP_COMMAND = "AppCommand.xml";

    private static final BigDecimal NINETYNINE = new BigDecimal("99");

    private static final BigDecimal POINTFIVE = new BigDecimal("0.5");

    private static final String CONTENT_TYPE_XML = "application/xml";

    private DenonMarantzConfiguration config;

    private final String cmdUrl;

    private final String statusUrl;

    private final HttpClient httpClient;

    private DenonMarantzTelnetClient telnetClient;

    private boolean displayNowplaying = false;

    private DenonMarantzStateChangedListener stateListener;

    private ScheduledFuture<?> pollingJob;

    private ScheduledExecutorService scheduler;

    private DenonMarantzState state;

    private boolean disposing = false;

    public DenonMarantzConnector(DenonMarantzConfiguration config, DenonMarantzStateChangedListener stateListener,
            ScheduledExecutorService scheduler) {
        this.config = config;
        this.stateListener = stateListener;
        this.scheduler = scheduler;
        this.state = new DenonMarantzState(stateListener);
        this.cmdUrl = String.format("http://%s:%d/goform/formiPhoneAppDirect.xml?", config.getHost(),
                config.getHttpPort());
        this.statusUrl = String.format("http://%s:%d/goform/", config.getHost(), config.getHttpPort());
        this.httpClient = new HttpClient();

    }

    public DenonMarantzState getState() {
        return state;
    }

    /**
     * Set up a telnet connection to the receiver AND fetch initial state over HTTP.
     */
    public void connect() {
        if (config.isTelnet()) {
            telnetClient = new DenonMarantzTelnetClient(config, this);
            telnetClient.start();
        } else {
            // If Telnet is disabled, schedule polling the AVR using HTTP
            startPolling();
        }
        getInitialState();
    }

    private void startPolling() {
        if (!isPolling()) {
            pollingJob = scheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    refreshHttpProperties();

                }
            }, config.httpPollingInterval, config.httpPollingInterval, TimeUnit.MILLISECONDS);
        }
    }

    private boolean isPolling() {
        return pollingJob != null && !pollingJob.isCancelled();
    }

    private void stopPolling() {
        if (pollingJob != null) {
            pollingJob.cancel(true);
            logger.info("HTTP polling stopped.");
        }
    }

    @Override
    public void telnetClientConnected(boolean connected) {
        if (!connected) {
            if (config.isTelnet() && !disposing) {
                logger.debug("Telnet client disconnected.");
                stateListener.connectionError(
                        "Error connecting to the telnet port. Consider disabling telnet in this Thing's configuration to use HTTP polling instead.");
            }
        }
        /*
         * if (connected && isPolling()) {
         * stopPolling();
         * }
         * if (!connected) {
         * if (config.isTelnet() && !disposing) {
         * logger.warn("Telnet client disconnected. Trying to poll HTTP in the mean time.");
         * startPolling();
         * }
         * }
         */
    }

    /**
     * Shutdown the telnet client (if initialized) and the http client
     */
    public void dispose() {
        logger.debug("disposing connector");
        disposing = true;

        stopPolling();

        if (telnetClient != null) {
            telnetClient.shutdown();
        }

        if (!httpClient.isStopped()) {
            try {
                httpClient.stop();
            } catch (Exception e) {
                logger.debug("Error stopping http client", e);
            }
        }
    }

    public void sendMuteCommand(Command command, int zone) throws UnsupportedCommandTypeException {
        if (zone < 1 || zone > 3) {
            throw new UnsupportedCommandTypeException("Zone must be in range [1-3], zone: " + zone);
        }
        String zonePrefix = zone == 1 ? "" : "Z" + zone;
        String cmd = zonePrefix + "MU";
        if (command == OnOffType.ON) {
            cmd += "ON";
        } else if (command == OnOffType.OFF) {
            cmd += "OFF";
        } else if (command instanceof RefreshType) {
            cmd += "?";
        } else {
            throw new UnsupportedCommandTypeException();
        }
        internalSendCommand(cmd);
    }

    public void sendPowerCommand(Command command, int zone) throws UnsupportedCommandTypeException {
        String zonePrefix;
        switch (zone) {
            case 0:
                zonePrefix = "PW";
                break;
            case 1:
                zonePrefix = "ZM";
                break;
            case 2:
            case 3:
                zonePrefix = "Z" + zone;
                break;
            default:
                throw new UnsupportedCommandTypeException("Zone must be in range [0-3], zone: " + zone);
        }
        String cmd = zonePrefix;
        if (command == OnOffType.ON) {
            cmd += "ON";
        } else if (command == OnOffType.OFF) {
            cmd += (zone == 0) ? "STANDBY" : "OFF";
        } else if (command instanceof RefreshType) {
            cmd += "?";
        } else {
            throw new UnsupportedCommandTypeException();
        }
        internalSendCommand(cmd);
    }

    public void sendVolumeCommand(Command command, int zone) throws UnsupportedCommandTypeException {
        String zonePrefix;
        switch (zone) {
            case 1:
                zonePrefix = "MV";
                break;
            case 2:
            case 3:
                zonePrefix = "Z" + zone;
                break;
            default:
                throw new UnsupportedCommandTypeException("Zone must be in range [1-3], zone: " + zone);
        }
        String cmd = zonePrefix;
        if (command instanceof RefreshType) {
            cmd += "?";
        } else if (command == IncreaseDecreaseType.INCREASE) {
            cmd += "UP";
        } else if (command == IncreaseDecreaseType.DECREASE) {
            cmd += "DOWN";
        } else if (command instanceof PercentType) {
            cmd += toDenonValue(((PercentType) command).toBigDecimal());
        } else {
            throw new UnsupportedCommandTypeException();
        }
        internalSendCommand(cmd);
    }

    /**
     * Gets the current state of all properties from the receiver, including
     * basic configuration info (like the number of zones)
     *
     * @throws IOException
     */
    public void getInitialState() {
        setConfigProperties();
        refreshHttpProperties();
    }

    /*
     * TODO
     * private String getCommandFor(DenonMarantzBindingConfig config, OnOffType onOff) {
     * String commandToSend = null;
     * String property = config.getActualProperty();
     *
     * if (config.isOnOffProperty()) {
     * if (property.equals(DenonMarantzProperty.POWER.getCode())) {
     * if (OnOffType.ON.equals(onOff)) {
     * commandToSend = "PWON";
     * } else {
     * commandToSend = "PWSTANDBY";
     * }
     * } else {
     * commandToSend = property + onOff.name();
     * }
     * } else {
     * if (onOff.equals(OnOffType.ON)) {
     * commandToSend = property;
     * }
     * }
     *
     * return commandToSend;
     * }
     *
     * private String getCommandFor(DenonMarantzBindingConfig config, IncreaseDecreaseType increaseDecreaseType) {
     * String commandToSend = null;
     * String property = config.getActualProperty();
     *
     * if (increaseDecreaseType.equals(IncreaseDecreaseType.INCREASE)) {
     * commandToSend = property + "UP";
     * } else {
     * commandToSend = property + "DOWN";
     * }
     *
     * return commandToSend;
     * }
     *
     * private String getCommandFor(DenonMarantzBindingConfig config, StringType stringType) {
     * String commandToSend = null;
     * String property = config.getActualProperty();
     *
     * if (property.equals(DenonMarantzProperty.INPUT.getCode())) {
     * commandToSend = "SI" + stringType.toString();
     * } else if (property.equals(DenonMarantzProperty.COMMAND.getCode())) {
     * commandToSend = stringType.toString();
     * }
     *
     * return commandToSend;
     * }
     *
     * private String getCommandFor(DenonMarantzBindingConfig config, PercentType percentType) {
     * String property = config.getActualProperty();
     * String commandToSend = property + toDenonValue(percentType.toBigDecimal());
     *
     * return commandToSend;
     * }
     *
     */
    /**
     * This method tries to parse information received over the telnet connection.
     * It can be quite unreliable. Some chars go missing or turn into other chars. That's
     * why each command is validated using a regex.
     *
     * @param line The received command (one line)
     */
    // TODO implement/update telnet command -> state update
    @Override
    public void receivedLine(String line) {
        if (COMMAND_PATTERN.matcher(line).matches()) {

            /*
             * This splits the commandString into the command and the parameter. SICD
             * for example has SI as the command and CD as the parameter.
             */
            String command = line.substring(0, 2);
            String value = line.substring(2, line.length()).trim();

            // Secondary zone commands with a parameter
            if (ZONE_SUBCOMMAND_PATTERN.matcher(line).matches()) {
                command = line.substring(0, 4);
                value = line.substring(4, line.length()).trim();
            }

            logger.debug("Command: {}, value: {}", command, value);

            if (value.equals("ON") || value.equals("OFF")) {
                sendUpdate(command, OnOffType.valueOf(value));
            } else if (value.equals("STANDBY")) {
                sendUpdate(command, OnOffType.OFF);
            } else if (StringUtils.isNumeric(value)) {
                PercentType percent = new PercentType(fromDenonValue(value));
                command = translateVolumeCommand(command);
                sendUpdate(command, percent);
            } else if (command.equals("SI")) {
                sendUpdate(DenonMarantzProperty.INPUT.getCode(), new StringType(value));
                sendUpdate(line, OnOffType.ON);
            } else if (command.equals("MS")) {
                sendUpdate(DenonMarantzProperty.SURROUND_MODE.getCode(), new StringType(value));
            } else if (command.equals("NS")) {
                processTitleCommand(command, value);
            }
        } else {
            logger.debug("Invalid command: '{}'", line);
        }
    }

    private void processTitleCommand(String command, String value) {
        if (DISPLAY_PATTERN.matcher(value).matches()) {
            Integer commandNo = Integer.valueOf(value.substring(1, 2));
            String titleValue = value.substring(2);

            if (commandNo == 0) {
                displayNowplaying = titleValue.contains("Now Playing");
            }

            String nowPlaying = displayNowplaying ? cleanupDisplayInfo(titleValue) : "";

            switch (commandNo) {
                case 1:
                    state.setNowPlayingTrack(nowPlaying);
                    break;
                case 2:
                    state.setNowPlayingArtist(nowPlaying);
                    break;
                case 4:
                    state.setNowPlayingAlbum(nowPlaying);
                    break;
            }
        }
    }

    @Deprecated
    public void sendUpdate(String property, State state) {
        // stateCache.put(property, state);
        // TODO
        // callback.updated(config.getInstance(), property, state);
    }

    private String toDenonValue(BigDecimal percent) {
        // Round to nearest number divisible by 0.5
        percent = percent.divide(POINTFIVE).setScale(0, RoundingMode.UP).multiply(POINTFIVE)
                .min(config.getMainVolumeMax()).max(BigDecimal.ZERO);

        String dbString = String.valueOf(percent.intValue());

        if (percent.compareTo(BigDecimal.TEN) == -1) {
            dbString = "0" + dbString;
        }
        if (percent.remainder(BigDecimal.ONE).equals(POINTFIVE)) {
            dbString = dbString + "5";
        }

        return dbString;
    }

    private BigDecimal fromDenonValue(String string) {
        /*
         * 455 = 45,5
         * 45 = 45
         * 045 = 4,5
         * 04 = 4
         */
        BigDecimal value = new BigDecimal(string);
        if (value.compareTo(NINETYNINE) == 1 || (string.startsWith("0") && string.length() > 2)) {
            value = value.divide(BigDecimal.TEN);
        }

        return value;
    }

    private void internalSendCommand(String command) {
        logger.debug("Sending command '{}'", command);
        if (StringUtils.isBlank(command)) {
            logger.warn("Trying to send empty command");
            return;
        }

        try {
            String url = cmdUrl + URLEncoder.encode(command, Charset.defaultCharset().displayName());
            logger.trace("Calling url {}", url);

            if (!httpClient.isStarted()) {
                httpClient.start();
            }

            httpClient.newRequest(url).timeout(5, TimeUnit.SECONDS).send(new Response.CompleteListener() {
                @Override
                public void onComplete(Result result) {
                    if (result.getResponse().getStatus() != 200) {
                        logger.warn("Error {} while sending command", result.getResponse().getReason());
                    }
                }
            });

        } catch (UnsupportedEncodingException e) {
            logger.warn("Error sending command", e);
        } catch (Exception e) {
            logger.warn("Could not start HTTP client", e);
        }
    }

    private void updateMain() {
        String url = statusUrl + URL_MAIN;
        logger.trace("Refreshing URL: {}", url);

        Main statusMain = getDocument(url, Main.class);
        if (statusMain != null) {
            state.setPower(statusMain.getPower().getValue());
        }
    }

    private void updateMainZone() {
        String url = statusUrl + URL_ZONE_MAIN;
        logger.trace("Refreshing URL: {}", url);

        ZoneStatus mainZone = getDocument(url, ZoneStatus.class);
        if (mainZone != null) {
            state.setInput(mainZone.getInputFuncSelect().getValue());
            state.setMainVolume(mainZone.getMasterVolume().getValue());
            state.setMainZonePower(mainZone.getPower().getValue());
            state.setMute(mainZone.getMute().getValue());

            if (config.inputOptions == null) {
                config.inputOptions = mainZone.getInputFuncList();
            }

            if (mainZone.getSurrMode() == null) {
                logger.debug("Unable to get the SURROUND_MODE. MainZone update may not be correct.");
            } else {
                state.setSurroundProgram(mainZone.getSurrMode().getValue());
            }
        }
    }

    private void updateSecondaryZones() {
        for (int i = 2; i <= config.getZoneCount(); i++) {
            String url = String.format("%s" + URL_ZONE_SECONDARY_LITE, statusUrl, i, i);
            logger.trace("Refreshing URL: {}", url);
            ZoneStatusLite zoneSecondary = getDocument(url, ZoneStatusLite.class);
            if (zoneSecondary != null) {
                switch (i) {
                    // maximum 2 secondary zones are supported
                    case 2:
                        state.setZone2Power(zoneSecondary.getPower().getValue());
                        state.setZone2Volume(zoneSecondary.getMasterVolume().getValue());
                        state.setZone2Mute(zoneSecondary.getMute().getValue());
                        state.setZone2Input(zoneSecondary.getInputFuncSelect().getValue());
                        break;
                    case 3:
                        state.setZone3Power(zoneSecondary.getPower().getValue());
                        state.setZone3Volume(zoneSecondary.getMasterVolume().getValue());
                        state.setZone3Mute(zoneSecondary.getMute().getValue());
                        state.setZone3Input(zoneSecondary.getInputFuncSelect().getValue());
                        break;
                }
            }
        }
    }

    private void updateDisplayInfo() {
        String url = statusUrl + URL_APP_COMMAND;
        logger.trace("Refreshing URL: {}", url);

        AppCommandRequest request = AppCommandRequest.of(CommandTx.CMD_NET_STATUS);
        AppCommandResponse response = postDocument(url, AppCommandResponse.class, request);

        if (response != null) {
            CommandRx titleInfo = response.getCommands().get(0);
            state.setNowPlayingArtist(titleInfo.getText("artist"));
            state.setNowPlayingAlbum(titleInfo.getText("album"));
            state.setNowPlayingTrack(titleInfo.getText("track"));
        }
    }

    private boolean setConfigProperties() {
        String url = statusUrl + URL_DEVICE_INFO;
        logger.debug("Refreshing URL: {}", url);

        Deviceinfo deviceinfo = getDocument(url, Deviceinfo.class);
        if (deviceinfo != null) {
            config.setZoneCount(deviceinfo.getDeviceZones());
        }

        /**
         * The maximum volume is received from the telnet connection in the
         * form of the MVMAX property. It is not always received reliable however,
         * so we're using a default for now.
         */
        config.setMainVolumeMax(DenonMarantzConfiguration.MAX_VOLUME);

        logger.debug("Zones: {}", config.getZoneCount());

        // if deviceinfo is null, something went wrong (and is logged in getDocument catch blocks)
        return (deviceinfo != null);
    }

    private void refreshHttpProperties() {
        logger.trace("Refreshing Denon status");

        updateMain();
        updateMainZone();
        updateSecondaryZones();
        updateDisplayInfo();
    }

    /**
     * Translate the volume command from the receiver to the openHAB property.
     *
     * Z2 -> Z2ZV
     * Z3 -> Z3ZV, etc
     *
     * @param command The command from the receiver
     * @return The property name in openHAB
     */
    private String translateVolumeCommand(String command) {
        if (command.matches("Z[0-9]")) {
            command = command + DenonMarantzProperty.ZONE_VOLUME.getCode();
        }

        return command;
    }

    /**
     * Display info could contain some garbled text, attempt to clean it up.
     */
    private String cleanupDisplayInfo(String titleValue) {
        byte firstByteRemoved[] = Arrays.copyOfRange(titleValue.getBytes(), 1, titleValue.getBytes().length);
        titleValue = new String(firstByteRemoved).replaceAll("[\u0000-\u001f]", "");
        return titleValue;
    }

    private <T> T getDocument(String uri, Class<T> response) {
        try {
            String result = doHttpRequest("GET", uri, null);
            logger.trace("result of getDocument for uri '{}':\r\n{}", uri, result);

            if (StringUtils.isNotBlank(result)) {
                JAXBContext jc = JAXBContext.newInstance(response);
                XMLInputFactory xif = XMLInputFactory.newInstance();
                XMLStreamReader xsr = xif.createXMLStreamReader(IOUtils.toInputStream(result));
                xsr = new PropertyRenamerDelegate(xsr);

                @SuppressWarnings("unchecked")
                T obj = (T) jc.createUnmarshaller().unmarshal(xsr);

                return obj;
            }
        } catch (UnmarshalException e) {
            logger.debug("Failed to unmarshal xml document: {}", e.getMessage());
        } catch (JAXBException e) {
            logger.debug("Unexpected error occurred during unmarshalling of document: {}", e.getMessage());
        } catch (XMLStreamException e) {
            logger.debug("Communication error: {}", e.getMessage());
        } catch (IOException e) {
            logger.debug("IO error while retrieving document: {}", e);
            stateListener.connectionError("IO error while connecting to AVR: " + e.getMessage());
        }

        return null;
    }

    private <T, S> T postDocument(String uri, Class<T> response, S request) {
        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(request.getClass());
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
            StringWriter sw = new StringWriter();
            jaxbMarshaller.marshal(request, sw);

            String result = doHttpRequest("POST", uri, sw.toString());

            if (StringUtils.isNotBlank(result)) {
                JAXBContext jcResponse = JAXBContext.newInstance(response);

                @SuppressWarnings("unchecked")
                T obj = (T) jcResponse.createUnmarshaller().unmarshal(IOUtils.toInputStream(result));

                return obj;
            }
        } catch (JAXBException e) {
            logger.debug("Encoding error in post", e);
        } catch (IOException e) {
            logger.debug("IO error while sending document: {}", e);
            stateListener.connectionError("IO error while sending command to AVR: " + e.getMessage());
        }

        return null;
    }

    private String doHttpRequest(String method, String uri, String request) throws IOException {

        HttpURLConnection connection = (HttpURLConnection) new URL(uri).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(REQUEST_TIMEOUT_MS);
        connection.setReadTimeout(REQUEST_TIMEOUT_MS);
        connection.addRequestProperty("Content-Type", CONTENT_TYPE_XML);

        if (request != null) {
            connection.setDoOutput(true);
            connection.getOutputStream().write(request.getBytes());
        }

        InputStream is = connection.getInputStream();
        String ret = IOUtils.toString(is);

        connection.disconnect();

        return ret;
    }

    private static class PropertyRenamerDelegate extends StreamReaderDelegate {

        public PropertyRenamerDelegate(XMLStreamReader xsr) {
            super(xsr);
        }

        @Override
        public String getAttributeLocalName(int index) {
            return Introspector.decapitalize(super.getAttributeLocalName(index));
        }

        @Override
        public String getLocalName() {
            return Introspector.decapitalize(super.getLocalName());
        }
    }

}
