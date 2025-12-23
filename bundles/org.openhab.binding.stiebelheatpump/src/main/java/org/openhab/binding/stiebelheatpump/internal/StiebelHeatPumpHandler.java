/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.stiebelheatpump.internal;

import static org.openhab.binding.stiebelheatpump.internal.StiebelHeatPumpBindingConstants.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import javax.measure.quantity.Dimensionless;
import javax.measure.quantity.Energy;
import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.stiebelheatpump.exception.StiebelHeatPumpException;
import org.openhab.binding.stiebelheatpump.protocol.DataParser;
import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition;
import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition.Type;
import org.openhab.binding.stiebelheatpump.protocol.Request;
import org.openhab.binding.stiebelheatpump.protocol.Requests;
import org.openhab.binding.stiebelheatpump.protocol.SerialConnector;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link StiebelHeatPumpHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Peter Kreutzer - Initial contribution
 * @author Robin Windey - Improvements and Openhab 4 compatibility
 */
public class StiebelHeatPumpHandler extends BaseThingHandler {

    private static final Duration RETRY_PORT_DELAY = Duration.ofSeconds(10);
    private static final int RETRY_PORT_RETRIES = 3;

    private Logger logger = LoggerFactory.getLogger(StiebelHeatPumpHandler.class);
    private final SerialPortManager serialPortManager;
    private final ConfigFileLoader configFileLoader;
    private final CommunicationServiceFactory communicationServiceFactory;
    private final ScheduledExecutorService scheduledExecutorService;
    private final ItemChannelLinkRegistry itemChannelLinkRegistry;
    private StiebelHeatPumpConfiguration config;
    private CommunicationService communicationService;
    private ReentrantLock communicationInUse = new ReentrantLock();
    private AtomicInteger commandsPendingCounter = new AtomicInteger(0);
    private Instant lastCommandExecuted = Instant.now();

    /** heat pump request definition */
    private final Requests heatPumpConfiguration;
    private Request versionRequest;
    private Request timeRequest;

    private Requests scheduledRequests = new Requests();

    /** cyclic pooling of sensor/status data from heat pump */
    ScheduledFuture<?> communicateWithHeatPumpJob;

    /** cyclic update of time in the heat pump */
    ScheduledFuture<?> timeRefreshJob;

    ScheduledFuture<?> retryOpenPortJob;
    private int retryPortCounter = 0;

    public StiebelHeatPumpHandler(Thing thing, final SerialPortManager serialPortManager,
            final ConfigFileLoader configFileLoader, final CommunicationServiceFactory communicationServiceFactory,
            final ItemChannelLinkRegistry itemChannelLinkRegistry,
            final @Nullable ScheduledExecutorService scheduledExecutorService) {
        super(thing);
        this.serialPortManager = serialPortManager;
        this.configFileLoader = configFileLoader;
        this.communicationServiceFactory = communicationServiceFactory;
        this.itemChannelLinkRegistry = itemChannelLinkRegistry;
        // used for unit testing:
        this.scheduledExecutorService = (scheduledExecutorService != null) ? scheduledExecutorService : scheduler;

        // get the records from the thing-type configuration file
        this.heatPumpConfiguration = new Requests();
        String configFile = getThing().getThingTypeUID().getId();
        ConfigLocator configLocator = new ConfigLocator(configFile + ".xml", this.configFileLoader);
        heatPumpConfiguration.setRequests(configLocator.getRequests());
    }

    public StiebelHeatPumpHandler(Thing thing, final SerialPortManager serialPortManager,
            final ConfigFileLoader configFileLoader, final CommunicationServiceFactory communicationServiceFactory,
            final ItemChannelLinkRegistry itemChannelLinkRegistry) {
        this(thing, serialPortManager, configFileLoader, communicationServiceFactory, itemChannelLinkRegistry, null);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            // refresh is handled with scheduled polling of data
            return;
        }
        logger.debug("Received command {} for channelUID {} (Thread {})", command, channelUID,
                Thread.currentThread().getName());
        String channelId = channelUID.getId();

        if (communicationService == null) {
            logger.warn("Communication service is not initialized, cannot handle command.");
            return;
        }

        commandsPendingCounter.incrementAndGet();
        communicationInUse.lock();

        // Use configured delay also for commands
        while (Duration.between(lastCommandExecuted, Instant.now())
                .compareTo(Duration.ofMillis(config.waitingTime)) < 0) {
            try {
                logger.trace("Delay command");
                Thread.sleep(100);
            } catch (InterruptedException e) {
                logger.warn("Thread interrupted while delaying command(s).");
                return;
            }
        }

        try {
            Map<String, Object> data = null;
            switch (channelId) {
                case CHANNEL_SETTIME:
                    data = communicationService.setTime(timeRequest);
                    updateState(channelUID, OnOffType.OFF);
                    break;
                case CHANNEL_DUMPRESPONSE:
                    for (byte requestByte : DEBUGBYTES) {
                        byte[] debugBytes = new byte[] { requestByte };
                        Request request = heatPumpConfiguration.getRequestByByte(debugBytes);
                        if (request == null) {
                            String requestStr = DataParser.bytesToHex(debugBytes);
                            logger.debug("Could not find request for {} in the thingtype definition.", requestStr);
                            request = new Request("Debug", "Debug dump response", debugBytes, null);
                        }
                        communicationService.dumpResponse(request);
                        Thread.sleep(config.waitingTime);
                    }
                    updateState(channelUID, OnOffType.OFF);
                    break;
                case CHANNEL_REQUESTBYTES:
                    String requestStr = command.toString();
                    byte[] debugBytes = DataParser.hexStringToByteArray(requestStr);
                    logger.debug("Dump responds for request byte {}!", requestStr);
                    String respondStr = communicationService.dumpRequest(debugBytes);
                    updateRespondChannel(respondStr);
                    logger.debug("Response from heatpump: {}", respondStr);
                    // updateState(channelUID, new StringType(requestStr));
                    break;
                default:
                    // do checks if valid definition is available
                    var recordDefinition = heatPumpConfiguration.getRecordDefinitionByChannelId(channelId);
                    if (recordDefinition == null) {
                        logger.warn("No record definition found for channelid {}!", channelId);
                        return;
                    }
                    if (recordDefinition.getDataType() != Type.Settings) {
                        logger.warn("The record {} can not be set as it is not a setable value!", channelId);
                        return;
                    }

                    var channelType = getThing().getChannel(channelUID).getChannelTypeUID().toString();

                    if (CHANNELTYPE_TIMESETTING_QUATER.equals(channelType)) {
                        /*
                         * Channel type: timesetting quater. Needs special handling
                         * because we always need to set both Start and End simulatenously.
                         */
                        data = handleTimeQuaterCommand(channelUID, command, recordDefinition);
                    } else {
                        /*
                         * "Regular" single value write
                         */
                        data = handleSingleValueCommand(channelUID, command, recordDefinition);
                    }
                    updateChannels(data);
            }
        } catch (Exception e) {
            logger.error("Exception occurred during execution: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
        } finally {
            lastCommandExecuted = Instant.now();
            communicationInUse.unlock();
            commandsPendingCounter.decrementAndGet();
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        scheduleRequestForChannel(channelUID, true);
    }

    @Override
    public void channelUnlinked(ChannelUID channelUID) {
        String channelId = channelUID.getId();
        Request request = heatPumpConfiguration.getRequestByChannelId(channelId);
        if (request == null) {
            logger.debug("No Request found for channelid {}!", channelId);
            return;
        }
        if (scheduledRequests.getRequests().contains(request)) {
            scheduledRequests.getRequests().remove(request);
        }
    }

    @Override
    public void initialize() {
        logger.debug("Initializing handler for thing {}", getThing().getUID());

        categorizeHeatPumpConfiguration();
        updateRefreshRequests();

        this.config = getConfigAs(StiebelHeatPumpConfiguration.class);
        if (!validateConfiguration(config)) {
            return;
        }

        Runnable cancelRetryOpenPortJob = () -> {
            if (retryOpenPortJob != null && !retryOpenPortJob.isCancelled()) {
                retryOpenPortJob.cancel(true);
                retryOpenPortJob = null;
            }
        };

        SerialPortIdentifier portId = serialPortManager.getIdentifier(config.port);
        if (portId == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, "Port is not known!");
            if (retryPortCounter >= RETRY_PORT_RETRIES) {
                logger.error("Serial port {} was not found after {} retries.", config.port, RETRY_PORT_RETRIES);
                cancelRetryOpenPortJob.run();
                return;
            }
            logger.debug("Serial port {} was not found, retrying in {}.", config.port, RETRY_PORT_DELAY);
            retryOpenPortJob = scheduledExecutorService.schedule(this::initialize, RETRY_PORT_DELAY.getSeconds(),
                    TimeUnit.SECONDS);
            retryPortCounter++;
            return;
        }

        retryPortCounter = 0;
        cancelRetryOpenPortJob.run();

        communicationService = communicationServiceFactory.create(serialPortManager, config.port, config.baudRate,
                config.waitingTime, new SerialConnector());

        scheduledExecutorService.schedule(() -> this.getInitialHeatPumpSettings(), 0, TimeUnit.SECONDS);
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.HANDLER_CONFIGURATION_PENDING,
                "Waiting for messages from device");
    }

    @Override
    public void dispose() {
        if (timeRefreshJob != null && !timeRefreshJob.isCancelled()) {
            timeRefreshJob.cancel(true);
        }
        timeRefreshJob = null;

        if (retryOpenPortJob != null && !retryOpenPortJob.isCancelled()) {
            retryOpenPortJob.cancel(true);
        }
        retryOpenPortJob = null;

        if (communicateWithHeatPumpJob != null && !communicateWithHeatPumpJob.isCancelled()) {
            communicateWithHeatPumpJob.cancel(true);
        }
        communicateWithHeatPumpJob = null;

        if (communicationService != null) {
            communicationService.close();
        }
        if (communicationInUse.isLocked()) {
            communicationInUse.unlock();
        }
    }

    private boolean validateConfiguration(StiebelHeatPumpConfiguration config) {
        if (config.port == null || config.port.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, "Port must be set!");
            return false;
        }

        if (config.baudRate < 9600 || config.baudRate > 115200) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                    "BaudRate must be between 9600 and 115200");
            return false;
        }

        if (config.refresh < 10) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                    "Refresh rate must be larger than 10");
            return false;
        }

        if (config.waitingTime <= 0) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                    "Waiting time between requests must be larger than 0");
            return false;
        }

        return true;
    }

    /**
     * This method reads initial information from the heat pump. It reads
     * the configuration file and loads all defined record definitions of sensor
     * data, status information , actual time settings and setting parameter
     * values for the thing type definition.
     *
     * @return true if heat pump information could be successfully connected and read
     */
    private void getInitialHeatPumpSettings() {

        // get version information from the heat pump
        communicationService.connect();
        try {
            String thingFirmwareVersion = getThing().getProperties().get(Thing.PROPERTY_FIRMWARE_VERSION);
            String version = communicationService.getVersion(versionRequest);
            logger.info("Heat pump has version {}", version);
            if (!thingFirmwareVersion.equals(version)) {
                logger.error("Thingtype version of heatpump {} is not the same as the heatpump version {}",
                        thingFirmwareVersion, version);
                return;
            }
        } catch (Exception e) {
            logger.warn("Communication problem with heatpump", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Communication problem with heatpump");
            communicationService.close();
            return;
        }

        updateStatus(ThingStatus.ONLINE);
        startHeatpumpCommunication();
        startTimeRefresh();
    }

    private void startHeatpumpCommunication() {
        communicateWithHeatPumpJob = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            Instant start = Instant.now();
            if (scheduledRequests.getRequests().isEmpty()) {
                logger.debug("nothing to update, refresh list is empty");
                return;
            }

            sendRequestsAndUpdateChannels(scheduledRequests.getRequests());

            Instant end = Instant.now();
            logger.debug("Data refresh took {} seconds.", Duration.between(start, end).getSeconds());

        }, 10, config.refresh, TimeUnit.SECONDS);
    }

    private void sendRequestsAndUpdateChannels(List<Request> requests) {
        communicationInUse.lock();
        logger.debug("Refresh data of heat pump (Thread {}).", Thread.currentThread().getName());
        Runnable beforeNextRequestCallback = () -> {
            if (commandsPendingCounter.get() <= 0) {
                return;
            }
            try {
                Instant now = Instant.now();
                logger.debug("Pausing get requests for pending command(s).");
                communicationInUse.unlock(); // Let waiting command pass
                while (commandsPendingCounter.get() > 0) {
                    Thread.sleep(100);
                }
                communicationInUse.lock();
                Thread.sleep(config.waitingTime);
                logger.debug("Continue get requests after waiting {}ms for pending command(s).",
                        Duration.between(now, Instant.now()).toMillis());

            } catch (InterruptedException e) {
                logger.warn("Thread interrupted while waiting for pending command(s).");
                return;
            }
        };
        try {
            Map<String, Object> data = communicationService.getRequestData(requests, beforeNextRequestCallback);
            // do the channel update immediately within the communicationInUse guard to avoid clashing
            updateChannels(data);
        } catch (Exception e) {
            logger.error("Exception occurred during execution of sendRequests.", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
        } finally {
            communicationInUse.unlock();
        }
        logger.debug("Data refresh of heat pump finished.");
    }

    /**
     * This method set the time in the heat pump to system time on a scheduler
     * once a week
     */
    private void startTimeRefresh() {
        timeRefreshJob = scheduledExecutorService.scheduleWithFixedDelay(() -> {

            communicationInUse.lock();
            logger.debug("Refresh time of heat pump.");
            try {
                Map<String, Object> time = communicationService.setTime(timeRequest);
                updateChannels(time);
            } catch (StiebelHeatPumpException e) {
                logger.debug("{}", e.getMessage());
            } finally {
                communicationInUse.unlock();
            }
        }, 1, 7, TimeUnit.DAYS);
    }

    /**
     * This method updates the query data to the channels
     *
     * @param data
     *            Map<String, String> of data coming from heat pump
     */
    private void updateChannels(Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            logger.debug("Data {} has value {}", entry.getKey(), entry.getValue());
            String channelId = entry.getKey();
            Channel ch = getThing().getChannel(channelId);
            if (ch == null) {
                logger.debug("For channelid {} no configuration found. Review channel definitions.", channelId);
                continue;
            }
            ChannelUID channelUID = ch.getUID();
            ChannelTypeUID channelTypeUID = ch.getChannelTypeUID();
            String channelType = channelTypeUID.toString();

            if (channelType.equalsIgnoreCase(CHANNELTYPE_TIMESETTING)
                    | channelType.equalsIgnoreCase(CHANNELTYPE_ERRORTIME)) {
                updateTimeChannel(entry.getValue().toString(), channelUID);
                continue;
            }
            if (channelType.equalsIgnoreCase(CHANNELTYPE_TIMESETTING_QUATER)) {
                updateTimeQuaterChannel((Number) entry.getValue(), channelUID);
                continue;
            }
            if (channelType.equalsIgnoreCase(CHANNELTYPE_ERRORDATE)) {
                updateDateChannel(entry.getValue().toString(), channelUID);
                continue;
            }
            if (channelType.equalsIgnoreCase(CHANNELTYPE_SWITCHSETTING)) {
                updateSwitchSettingChannel((boolean) entry.getValue(), channelUID);
                continue;
            }
            if (channelType.equalsIgnoreCase(CHANNELTYPE_CONTACTSTATUS)) {
                updateContactChannel((boolean) entry.getValue(), channelUID);
                continue;
            }
            if (entry.getValue() instanceof Number) {
                updateStatus((Number) entry.getValue(), channelUID);
            }
            if (entry.getValue() instanceof Boolean) {
                updateSwitchSettingChannel((boolean) entry.getValue(), channelUID);
            }
        }
        LocalDateTime dt = LocalDateTime.now();
        String formattedString = dt.format(DateTimeFormatter.ofPattern(DATE_PATTERN));
        updateState(CHANNEL_LASTUPDATE, new StringType(formattedString));
        updateStatus(ThingStatus.ONLINE);
    }

    private void updateStatus(Number value, ChannelUID channelUID) {
        String itemType = getThing().getChannel(channelUID).getAcceptedItemType();
        if (value instanceof Double) {
            switch (itemType) {
                case "Number:Temperature":
                    QuantityType<Temperature> temperature = new QuantityType<>(value, SIUnits.CELSIUS);
                    updateState(channelUID, temperature);
                    break;
                case "Number:Energy":
                    QuantityType<Energy> energy = new QuantityType<>(value, Units.WATT_HOUR);
                    updateState(channelUID, energy);
                    break;
                case "Number:Dimensionless:Percent":
                    QuantityType<Dimensionless> percent = new QuantityType<>(value, Units.PERCENT);
                    updateState(channelUID, percent);
                    break;
                case "String":
                    updateState(channelUID, new StringType(value.toString()));
                    break;
                default:
                    updateState(channelUID, new DecimalType((Double) value));
            }
            return;
        }
        if (value instanceof Short) {
            updateState(channelUID, new DecimalType((short) value));
        }
        if (value instanceof Integer) {
            updateState(channelUID, new DecimalType((int) value));
        }
    }

    private void updateSwitchSettingChannel(Boolean setting, ChannelUID channelUID) {
        if (Boolean.TRUE.equals(setting)) {
            updateState(channelUID, OnOffType.ON);
        } else {
            updateState(channelUID, OnOffType.OFF);
        }
    }

    private void updateContactChannel(Boolean setting, ChannelUID channelUID) {
        if (Boolean.TRUE.equals(setting)) {
            updateState(channelUID, OpenClosedType.OPEN);
        } else {
            updateState(channelUID, OpenClosedType.CLOSED);
        }
    }

    private void updateTimeChannel(String timeString, ChannelUID channelUID) {
        String newTime = String.format("%04d", Integer.parseInt(timeString));
        newTime = new StringBuilder(newTime).insert(newTime.length() - 2, ":").toString();
        updateState(channelUID, new StringType(newTime));
    }

    private void updateDateChannel(String dateString, ChannelUID channelUID) {
        String newDate = String.format("%04d", Integer.parseInt(dateString));
        newDate = new StringBuilder(newDate).insert(newDate.length() - 2, "-").toString();
        updateState(channelUID, new StringType(newDate));
    }

    private void updateTimeQuaterChannel(Number value, ChannelUID channelUID) {
        if (StiebelHeatPumpBindingConstants.RESET_TIME_QUATER.equals((short) value.intValue())) {
            // A slot which is not set normally has value -128 (hex 0x80)
            updateState(channelUID, UnDefType.NULL);
            return;
        }

        var fullHours = value.intValue() / 4;
        var minutes = (value.intValue() % 4) * 15;

        if (fullHours >= 24 || fullHours < 0) {
            logger.warn("Invalid time quater value: {}. Setting channel {} to NULL", value, channelUID);
            updateState(channelUID, UnDefType.NULL);
            return;
        }

        var dt = new DateTimeType(LocalDateTime.of(1970, 1, 1, fullHours, minutes).atZone(ZoneId.systemDefault()));
        var newTime = new StringType(dt.toString());
        updateState(channelUID, newTime);
    }

    private void updateRespondChannel(String responds) {
        for (Channel channel : getThing().getChannels()) {
            ChannelUID channelUID = channel.getUID();
            String channelStr = channelUID.getId();
            if (CHANNEL_RESPONDBYTES.equalsIgnoreCase(channelStr)) {
                updateState(channelUID, new StringType(responds));
                return;
            }
        }
    }

    /**
     * This method categorize the heat pump configuration into setting, sensor
     * and status
     *
     * @return true if heat pump configuration for version could be found and
     *         loaded
     */
    private boolean categorizeHeatPumpConfiguration() {
        for (Request request : heatPumpConfiguration.getRequests()) {
            String requestStr = DataParser.bytesToHex(request.getRequestByte());
            logger.debug("Request : RequestByte -> {}", requestStr);

            if (Arrays.equals(request.getRequestByte(), REQUEST_VERSION)) {
                versionRequest = request;
                logger.debug("set version request : {}", requestStr);
            }
            if (timeRequest == null && Arrays.equals(request.getRequestByte(), REQUEST_TIME)) {
                timeRequest = request;
                logger.debug("set time request : {}", requestStr);
            }
        }
        if (versionRequest == null || timeRequest == null) {
            logger.debug("version or time request could not be found in configuration");
            return false;
        }
        return true;
    }

    private void updateRefreshRequests() {
        for (Channel channel : getThing().getChannels()) {
            ChannelUID channelUID = channel.getUID();
            String channelId = channelUID.getIdWithoutGroup();
            logger.debug("Checking channel {} for refresh.", channelId);

            Request request = heatPumpConfiguration.getRequestByChannelId(channelId);
            if (request != null) {
                // String requestStr = DataParser.bytesToHex(request.getRequestByte());
                RecordDefinition record = request.getRecordDefinitionByChannelId(channelId);
                if (record == null) {
                    logger.warn("Could not find valid record definition for {}, please verify thing definition.",
                            channelId);
                    continue;
                }
                // rewrite channel IDs to include channel groups
                record.setChannelid(channelUID.getId());
            }

            if (!isLinked(channelUID)) {
                logger.debug("Channel {} is not linked, not scheduling request.", channelUID.getId());
                continue;
            }

            scheduleRequestForChannel(channelUID, false);
        }
    }

    private void scheduleRequestForChannel(ChannelUID channelUID, boolean refreshNow) {
        logger.debug("Checking schedule for {}", channelUID.getId());
        Request request = heatPumpConfiguration.getRequestByChannelId(channelUID.getId());
        if (request != null) {
            String requestStr = DataParser.bytesToHex(request.getRequestByte());
            RecordDefinition record = request.getRecordDefinitionByChannelId(channelUID.getId());
            if (record == null) {
                logger.warn("Could not find valid record definition for {},  please verify thing definition.",
                        channelUID.getId());
                return;
            }

            if (!scheduledRequests.getRequests().contains(request)) {
                scheduledRequests.getRequests().add(request);
                logger.debug("Request {} added to sensor/status refresh scheduler.", requestStr);
            }

            if (refreshNow) {
                List<Request> requestList = Collections.singletonList(request);
                sendRequestsAndUpdateChannels(requestList);
            }
        }
    }

    private Map<String, Object> handleTimeQuaterCommand(ChannelUID channelUID, Command command,
            RecordDefinition recordDefinitionStartOrEnd) throws StiebelHeatPumpException {
        // We use StringType for time quaters because it's "nullable" (DateTimeType is
        // not).
        if (!(command instanceof StringType stringCommand)) {
            logger.warn("Command ({}) is not a StringType, cannot set time quater channel.", command.getClass());
            return Map.of();
        }

        final String channelUidStr = channelUID.toString();

        ChannelUID channelUidOther;
        Boolean commandedItemIsStart = false;
        RecordDefinition recordDefinitionStart, recordDefinitionEnd;

        // Search the corresponding channel for the other time quater.
        if (channelUidStr.matches(".*Start$")) {
            channelUidOther = new ChannelUID(channelUidStr.replaceAll("(.*)Start$", "$1End"));
            recordDefinitionStart = recordDefinitionStartOrEnd;
            recordDefinitionEnd = heatPumpConfiguration.getRecordDefinitionByChannelId(channelUidOther.getId());
            commandedItemIsStart = true;
        } else if (channelUidStr.matches(".*End$")) {
            channelUidOther = new ChannelUID(channelUidStr.replaceAll("(.*)End$", "$1Start"));
            recordDefinitionEnd = recordDefinitionStartOrEnd;
            recordDefinitionStart = heatPumpConfiguration.getRecordDefinitionByChannelId(channelUidOther.getId());
        } else {
            logger.warn("Implementation error: channel {} is not a valid time quater channel.", channelUID.getId());
            return Map.of();
        }

        Short valueStart, valueEnd;
        var commandedItemTimeString = command.toString();

        if (isUninitializedTimeQuaterValue(commandedItemTimeString)) {
            // If the channel has been commanded to "empty", reset the whole pair.
            valueStart = valueEnd = StiebelHeatPumpBindingConstants.RESET_TIME_QUATER;
        } else {
            var linkedItemOther = itemChannelLinkRegistry.getLinkedItems(channelUidOther).stream().findFirst()
                    .orElse(null);
            if (linkedItemOther == null) {
                logger.warn("No linked item found for channel {}. Both Start and End channels need to be linked",
                        channelUidOther.getId());
                return new HashMap<>();
            }

            var stateOther = linkedItemOther.getState();
            var otherItemTimeString = stateOther.toString();

            Short valueCommandedItem = null;
            Short valueOther = null;

            try {
                valueCommandedItem = calculateTimeQuaterValue(stringCommand);
                if (isUninitializedTimeQuaterValue(otherItemTimeString)) {
                    // If the second channel of the pair is not set, we set
                    // it to the same value as the first one to create a "valid pair".
                    valueOther = valueCommandedItem;
                } else {
                    valueOther = calculateTimeQuaterValue(stateOther);
                }
            } catch (Exception e) {
                if (e instanceof IllegalArgumentException || e instanceof IndexOutOfBoundsException) {
                    logger.warn("Could not parse time quater value", e);
                    return Map.of();
                }
                throw e;
            }

            if (commandedItemIsStart) {
                valueStart = valueCommandedItem;
                valueEnd = valueOther;
            } else {
                valueStart = valueOther;
                valueEnd = valueCommandedItem;
            }
        }

        return communicationService.writeTimeQuaterPair(valueStart, valueEnd, recordDefinitionStart,
                recordDefinitionEnd);
    }

    private Map<String, Object> handleSingleValueCommand(ChannelUID channelUID, Command command,
            RecordDefinition recordDefinition) throws StiebelHeatPumpException {
        Object value = null;
        if (command instanceof OnOffType) {
            // the command come from a switch type , we need to map ON and OFF to 0 and 1
            // values
            value = true;
            if (command.equals(OnOffType.OFF)) {
                value = false;
            }
        }
        if (command instanceof QuantityType<?> newQtty) {
            value = newQtty.doubleValue();
        }
        if (command instanceof DecimalType dec) {
            value = dec.doubleValue();
        }
        if (command instanceof StringType) {
            DateTimeFormatter strictTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
                    .withResolverStyle(ResolverStyle.STRICT);
            try {
                LocalTime time = LocalTime.parse(command.toString(), strictTimeFormatter);
                value = (short) (time.getHour() * 100 + time.getMinute());
            } catch (DateTimeParseException e) {
                logger.info("Time string is not valid! : {}", e.getMessage());
            }
        }
        return communicationService.writeData(value, recordDefinition);
    }

    private short calculateTimeQuaterValue(State itemState) throws IllegalArgumentException {
        var dt = new DateTimeType(itemState.toString());
        var zonedDateTime = dt.getZonedDateTime(ZoneId.systemDefault());
        var hours = zonedDateTime.getHour();
        var minutes = zonedDateTime.getMinute();
        return (short) (hours * 4 + minutes / 15);
    }

    private Boolean isUninitializedTimeQuaterValue(String itemStateString) {
        return itemStateString.isEmpty()
                || UnDefType.NULL.toString().toLowerCase().equals(itemStateString.toLowerCase());
    }
}
