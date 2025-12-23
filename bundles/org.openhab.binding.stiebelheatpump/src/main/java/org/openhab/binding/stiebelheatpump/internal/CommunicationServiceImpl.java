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

import static org.openhab.binding.stiebelheatpump.internal.StiebelHeatPumpBindingConstants.DATE_PATTERN;

import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.stiebelheatpump.exception.InvalidDataException;
import org.openhab.binding.stiebelheatpump.exception.StiebelHeatPumpException;
import org.openhab.binding.stiebelheatpump.protocol.DataParser;
import org.openhab.binding.stiebelheatpump.protocol.ProtocolConnector;
import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition;
import org.openhab.binding.stiebelheatpump.protocol.Request;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Stefan Triller - Initial contribution
 * @author Robin Windey - Improvements and Openhab 4 compatibility
 */
public class CommunicationServiceImpl implements CommunicationService {

    private final Logger logger = LoggerFactory.getLogger(CommunicationServiceImpl.class);

    private final ProtocolConnector connector;
    private final int maxRetry = 5;
    private static final int inputBufferLength = 1024;

    DataParser parser = new DataParser();

    private final SerialPortManager serialPortManager;
    private final String serialPortName;
    private final int baudRate;
    private int waitingTime = 1200;

    public CommunicationServiceImpl(SerialPortManager serialPortManager, String serialPortName, int baudRate,
            int waitingTime, ProtocolConnector connector) {
        this.waitingTime = waitingTime;
        this.baudRate = baudRate;
        this.serialPortName = serialPortName;
        this.serialPortManager = serialPortManager;
        this.connector = connector;
    }

    public void connect() {
        try {
            connector.connect(serialPortManager, serialPortName, baudRate);
        } catch (StiebelHeatPumpException e) {
            logger.error("Could not connect to heatpump", e);
            if (e instanceof SerialPortNotFoundException) {
                try {
                    logger.info("Sleeping for 30s");
                    Thread.sleep(30000);
                } catch (InterruptedException e1) {
                    logger.error("Interrupted", e1);
                }
            }
        }
    }

    public void close() {
        connector.disconnect();
    }

    /**
     * This method parses the version information from the heat pump response
     *
     * @return version string, e.g: 2.06
     */
    public String getVersion(Request versionRequest) throws StiebelHeatPumpException {
        logger.debug("Loading version info ...");
        Map<String, Object> data = readData(versionRequest);
        String versionKey = StiebelHeatPumpBindingConstants.CHANNEL_VERSION;
        return data.get(versionKey).toString();
    }

    /**
     * This method reads all settings defined in the heat pump configuration
     * from the heat pump
     *
     * @param requests List of requests to be processed
     * @param beforeNextRequestCallback If provided, will be called before each request is processed
     * @return map of heat pump setting values
     */
    public Map<String, Object> getRequestData(List<Request> requests, @Nullable Runnable beforeNextRequestCallback)
            throws StiebelHeatPumpException {
        Map<String, Object> data = new HashMap<>();
        for (Request request : requests) {
            if (beforeNextRequestCallback != null) {
                // Can be used to pause between requests to give
                // commands (writes) priority
                beforeNextRequestCallback.run();
            }
            try {
                logger.debug("Loading data for request {}", DataParser.bytesToHex(request.getRequestByte(), false));
                data.putAll(readData(request));
                if (requests.size() > 1) {
                    Thread.sleep(waitingTime);
                }
            } catch (InterruptedException e) {
                throw new StiebelHeatPumpException(e.toString(), e);
            }
        }
        return data;
    }

    /**
     * This method set the time of the heat pump to the current time
     *
     * @return true if time has been updated
     */
    public Map<String, Object> setTime(Request timeRequest) throws StiebelHeatPumpException {

        startCommunication();
        Map<String, Object> data = new HashMap<>();

        if (timeRequest == null) {
            logger.warn("Could not find request definition for time settings! Skip setting time.");
            return data;
        }
        try {
            // get time from heat pump
            logger.debug("Loading current time data ...");
            byte[] requestMessage = createRequestMessage(timeRequest.getRequestByte());
            byte[] response = getData(requestMessage);
            data = parser.parseRecords(response, timeRequest);

            // get current time from local machine
            LocalDateTime dt = LocalDateTime.now();
            String formattedString = dt.format(DateTimeFormatter.ofPattern(DATE_PATTERN));
            logger.debug("Current time is : {}", dt);
            // Weekday: Heatpump: 0 (Mo) - 6 (Sun)
            // LocalDateTime: 1 (Mo) - 7 (Sun)
            short weekday = (short) (dt.getDayOfWeek().getValue() - 1);
            short day = (short) dt.getDayOfMonth();
            short month = (short) dt.getMonthValue();
            short year = Short.parseShort(Year.now().format(DateTimeFormatter.ofPattern("uu")));
            short seconds = (short) dt.getSecond();
            short hours = (short) dt.getHour();
            short minutes = (short) dt.getMinute();

            for (RecordDefinition record : timeRequest.getRecordDefinitions()) {
                String channelid = record.getChannelid();

                switch (channelid) {
                    case "weekday":
                        response = parser.composeRecord(weekday, response, record);
                        break;
                    case "hours":
                        response = parser.composeRecord(hours, response, record);
                        break;
                    case "minutes":
                        response = parser.composeRecord(minutes, response, record);
                        break;
                    case "seconds":
                        response = parser.composeRecord(seconds, response, record);
                        break;
                    case "year":
                        response = parser.composeRecord(year, response, record);
                        break;
                    case "month":
                        response = parser.composeRecord(month, response, record);
                        break;
                    case "day":
                        response = parser.composeRecord(day, response, record);
                        break;
                    default:
                        break;
                }
            }

            Thread.sleep(waitingTime);
            logger.info("Time need update. Set time to {}", dt);
            setData(response);
            Thread.sleep(waitingTime);
            response = getData(requestMessage);
            data = parser.parseRecords(response, timeRequest);

            data.put(StiebelHeatPumpBindingConstants.CHANNEL_LASTUPDATE, formattedString);
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                logger.info("Key = {} , Value =  {}", entry.getKey(), entry.getValue());
            }
            return data;

        } catch (InterruptedException e) {
            throw new StiebelHeatPumpException(e.toString());
        }
    }

    /**
     * This method reads all values defined in the request from the heat pump
     *
     * @param request
     *            definition to load the values from
     * @return map of heat pump values according request definition
     */
    private Map<String, Object> readData(Request request) throws StiebelHeatPumpException {
        Map<String, Object> data = new HashMap<>();
        String requestStr = DataParser.bytesToHex(request.getRequestByte(), false);
        logger.debug("RequestByte -> {}", requestStr);
        if (request.getRequestByte2() != null) {
            String requestStr2 = DataParser.bytesToHex(request.getRequestByte2(), false);
            logger.debug("RequestByte2 -> {}", requestStr2);
        }

        boolean success = false;
        int count = 0;
        int MAX_TRIES = 3;
        while (!success && count++ < MAX_TRIES) {
            try {
                byte[] responseAvailable = getData(createRequestMessage(request.getRequestByte()));
                if (parser.headerCheck(responseAvailable)) {
                    // single request
                    if (request.getRequestByte2() == null) {
                        return parser.parseRecords(responseAvailable, request);
                    }
                    // two requests for one value
                    Thread.sleep(waitingTime);
                    byte[] responseAvailable2 = getData(createRequestMessage(request.getRequestByte2()));
                    if (parser.headerCheck(responseAvailable2)) {
                        return parser.parseRecords(responseAvailable, responseAvailable2, request);
                    }
                }
                success = true;
            } catch (StiebelHeatPumpException e) {
                logger.warn("Error reading data for {}: {} -> Retry: {}", requestStr, e, count);
                restartConnector(); // TODO :: refactor and use this for other methods as well
            } catch (InterruptedException e) {
                logger.warn("Read data interrupted", e);
            }
        }
        if (!success) {
            throw new StiebelHeatPumpException(String.format("readData failed %d times!", MAX_TRIES));
        }
        return data;
    }

    /**
     * This method reads the responds of a single request from the heat pump
     *
     * @param request bytes to send to heat pump
     * @return byte[] represented as sting
     */
    public String dumpRequest(byte[] request) throws StiebelHeatPumpException {
        // Check if user provided SET header and if so, replace the GET header by a SET header in the calculated
        // message.
        // This way the "dumpRequest" can be used to send SET requests as well by setting a message like "0180 ...".
        Boolean setRequest = request.length >= 2 && request[0] == DataParser.HEADERSTART
                && request[1] == DataParser.SET;
        if (setRequest) {
            // Remove two header bytes from user (will be added later by createRequestMessage)
            request = Arrays.copyOfRange(request, 2, request.length);
        }

        String requestStr = DataParser.bytesToHex(request, false);
        logger.debug("RequestByte -> {}", requestStr);

        byte[] responseAvailable;
        byte[] requestMessage = createRequestMessage(request, setRequest ? DataParser.SET : DataParser.GET);

        boolean success = false;
        int count = 0;
        int MAX_TRIES = 3;
        while (!success && count++ < MAX_TRIES) {
            try {
                responseAvailable = getData(requestMessage);
                if (parser.headerCheck(responseAvailable)) {
                    analyzeResponse(responseAvailable);
                    return DataParser.bytesToHex(responseAvailable, true);
                }
                success = true;
            } catch (StiebelHeatPumpException e) {
                logger.warn("Error reading data for {}: {} -> Retry: {}", requestStr, e, count);
            }
        }
        if (!success) {
            throw new StiebelHeatPumpException("readData failed 3 times!");
        }
        return "";
    }

    private void analyzeResponse(byte[] response) throws StiebelHeatPumpException {
        // HEADER_START
        // GET
        // CHECKSUM
        // DATA x....y
        // ESCAPE
        // END
        if (response.length < 5) {
            logger.debug("Invalid response: {}", DataParser.bytesToHex(response, true));
            return;
        } else if (response.length == 5) {
            logger.debug("Empty response: {}", DataParser.bytesToHex(response, true));
            return;
        }

        DataParser dp = new DataParser();
        int numDataBytes = response.length - 5;

        // bit positions
        for (int pos = 3; pos <= response.length - 2; pos++) {
            ArrayList<Object> positionValues = new ArrayList<>();
            for (int bitPos = 1; bitPos < 8; bitPos++) { // TODO: <= 8 ?
                RecordDefinition rDef = new RecordDefinition();
                rDef.setPosition(pos);
                rDef.setScale(1.0);
                // this is what we are interested in
                rDef.setBitPosition(bitPos);
                rDef.setLength(1);
                positionValues.add(dp.parseRecord(response, rDef));
            }
            logger.debug("Bitpos:\tFound on pos={}\tvalues=[{}]", pos, Arrays.toString(positionValues.toArray()));
        }

        // each byte
        for (int pos = 3; pos <= response.length - 2; pos++) {
            RecordDefinition rDef = new RecordDefinition();
            rDef.setPosition(pos);
            rDef.setScale(1.0);
            rDef.setBitPosition(0);
            // this is what we are interested in
            rDef.setLength(1);
            Object parsedData = dp.parseRecord(response, rDef);
            logger.debug("1-Byte:\tFound on pos={}\tvalue={}", pos, parsedData);
        }
        if (numDataBytes < 2) {
            return;
        }
        // S, G, C, D, D, D, D, E, E
        // 0, 1, 2, 3, 4, 5, 6, 7, 8 = pos
        // 1, 2, 3, 4, 5, 6, 7 ,8 ,9 = len

        // two bytes
        for (int pos = 3; pos <= response.length - 4; pos += 2) {
            RecordDefinition rDef = new RecordDefinition();
            rDef.setPosition(pos);
            rDef.setScale(1.0);
            rDef.setBitPosition(0);
            // this is what we are interested in
            rDef.setLength(2);
            Object parsedData = dp.parseRecord(response, rDef);
            logger.debug("2-Bytes:\tFound on pos={}\tvalue={}", pos, parsedData);
        }

        if (numDataBytes < 4) {
            return;
        }

        // four bytes
        for (int pos = 3; pos <= response.length - 6; pos += 4) {
            RecordDefinition rDef = new RecordDefinition();
            rDef.setPosition(pos);
            rDef.setScale(1.0);
            rDef.setBitPosition(0);
            // this is what we are interested in
            rDef.setLength(4);
            Object parsedData = dp.parseRecord(response, rDef);
            logger.debug("4-Bytes:\tFound on pos={}\tvalue={}", pos, parsedData);
        }
    }

    /**
     * This method updates the parameter item of a heat pump request
     *
     * @param newValue
     *            the new value of the item
     * @param recordDefinition
     *            describing the record to be updated
     */
    public Map<String, Object> writeData(Object newValue, RecordDefinition recordDefinition) {
        logger.trace("writeData");
        return writeDataValues(List.of(new WriteValueDefinition(newValue, recordDefinition)));
    }

    public Map<String, Object> writeTimeQuaterPair(Object startValue, Object endValue,
            RecordDefinition recordDefinitionStart, RecordDefinition recordDefinitionEnd) {
        logger.trace("writeTimeQuaterPair");
        return writeDataValues(List.of(new WriteValueDefinition(startValue, recordDefinitionStart),
                new WriteValueDefinition(endValue, recordDefinitionEnd)));
    }

    /**
     * dumps response of connected heat pump by request byte
     *
     * @param request
     * @param requestByte
     *
     * @param requestByte
     *            request byte to send to heat pump
     */
    public Map<String, Object> dumpResponse(Request request) {
        Map<String, Object> data = new HashMap<>();
        try {
            data = readData(request);
        } catch (Exception e) {
            String requestStr = String.format("%02X", request.getRequestByte());
            logger.error("Could not get data from heat pump! for request {}", requestStr);
        }
        return data;
    }

    /**
     * Gets data from connected heat pump
     *
     * @param request
     *            request bytes to send to heat pump
     * @return response bytes from heat pump
     *
     *         General overview of handshake between application and serial
     *         interface of heat pump
     *
     *         1. Sending request bytes ,
     *         e.g.: 01 00 FE FD 10 03 for version request
     *         01 -> header start
     *         00 -> get request
     *         FE -> checksum of request
     *         FD -> request byte
     *         10 03 -> Footer ending the communication
     *
     *         2. Receive a data available
     *         10 -> ok
     *         02 -> it does have data,which wants to send now
     *
     *         3. acknowledge sending data
     *         10 -> ok
     *
     *         4. receive data until footer
     *         01 -> header start
     *         00 -> get request
     *         CC -> checksum of send data
     *         FD -> request byte
     *         00 CE -> data, e.g. short value as 2 bytes -> 206 -> 2.06 version
     *         10 03 -> Footer ending the communication
     * @throws StiebelHeatPumpException
     */
    private byte[] getData(byte[] request) throws StiebelHeatPumpException {
        startCommunication();
        if (!establishRequest(request)) {
            return new byte[0];
        }
        try {
            connector.write(DataParser.ESCAPE);
            return receiveData();
        } catch (Exception e) {
            logger.error("Could not get data from heat pump!", e);
            return new byte[0];
        }
    }

    /**
     * Sets setting value in heat pump
     *
     * @param request
     *            request bytes to send to heat pump
     * @return response bytes from heat pump
     *
     *         General overview of handshake between application and serial
     *         interface of heat pump
     *
     *         1. Sending request bytes, e.g update time in heat pump
     *         01 -> header start
     *         80 -> set request
     *         F1 -> checksum of request
     *         FC -> request byte
     *         00 02 0a 22 1b 0e 00 03 1a -> new values according record definition for time
     *         10 03 -> Footer ending the communication
     *
     *         2. Receive response message the confirmation message is ready for sending
     *         10 -> ok
     *         02 -> it does have data, which wants to send now
     *
     *         3. acknowledge sending data
     *         10 -> ok
     *
     *         4. receive confirmation message until footer
     *         01 -> header start
     *         80 -> set request
     *         7D -> checksum of send data
     *         FC -> request byte
     *         10 03 -> Footer ending the communication
     */
    private byte[] setData(byte[] request) {
        try {
            startCommunication();
            establishRequest(request);
            // Acknowledge sending data
            connector.write(DataParser.ESCAPE);

        } catch (Exception e) {
            logger.error("Could not set data to heat pump! {}", e.toString());
            return new byte[0];
        }

        // finally receive data
        return receiveData();
    }

    /**
     * This method start the communication for the request It send the initial
     * handshake and expects a response
     */
    private void startCommunication() throws StiebelHeatPumpException {
        logger.debug("Sending start communication");
        byte response;
        try {
            connector.write(DataParser.STARTCOMMUNICATION);
            response = connector.get();
        } catch (Exception e) {
            throw new StiebelHeatPumpException("Heat pump communication could not be established! " + e.getMessage(),
                    e);
        }
        if (response != DataParser.ESCAPE) {
            var responseStr = String.format("%02X", response);
            throw new StiebelHeatPumpException(
                    "Heat pump is communicating, but did not receive Escape message in initial handshake. Received: "
                            + responseStr);
        }
    }

    /**
     * This method establish the connection for the request It send the request
     * and expects a data available response
     *
     * @param request
     *            to be send to heat pump
     * @return true if data are available from heatpump
     */
    private boolean establishRequest(byte[] request) {
        byte[] buffer = new byte[inputBufferLength];
        int requestRetry = 0;
        int bufferRetry = 0;
        try {
            while (requestRetry < maxRetry) {
                int numBytesReadTotal = 0;
                connector.write(request);
                bufferRetry = 0;
                byte singleByte;
                while (bufferRetry < maxRetry) {
                    try {
                        singleByte = connector.get();
                    } catch (Exception e) {
                        bufferRetry++;
                        logger.debug("Exception while executing connector.get(). Retry reading buffer", e);
                        continue;
                    }
                    buffer[numBytesReadTotal] = singleByte;
                    numBytesReadTotal++;
                    if (buffer[0] != DataParser.DATAAVAILABLE[0] || buffer[1] != DataParser.DATAAVAILABLE[1]) {
                        continue;
                    }
                    return true;
                }
                logger.debug("Retry request");
                requestRetry++;
                startCommunication();
            }

            logger.warn("heat pump has no data available for request!");
            return false;
        } catch (Exception e1) {
            logger.error("Could not get data from heat pump!", e1);
            return false;
        }
    }

    /**
     * This method receive the response from the heat pump It receive single
     * bytes until the end of message s detected
     *
     * @return bytes representing the data send from heat pump
     */
    private byte[] receiveData() {
        byte singleByte;
        int numBytesReadTotal = 0;
        int retry = 0;
        final byte[] buffer = new byte[inputBufferLength];

        logger.debug("Receiving data from heat pump.");

        while (retry < maxRetry) {
            try {
                singleByte = connector.get();
            } catch (Exception e) {
                // reconnect and try again to send request
                retry++;
                continue;
            }

            buffer[numBytesReadTotal] = singleByte;
            numBytesReadTotal++;

            if (numBytesReadTotal > 4 && buffer[numBytesReadTotal - 2] == DataParser.ESCAPE
                    && buffer[numBytesReadTotal - 1] == DataParser.END) {
                // we have reached the end of the response
                logger.debug("Reached end of response message.");
                break;
            }
        }

        final byte[] responseBuffer = new byte[numBytesReadTotal];
        System.arraycopy(buffer, 0, responseBuffer, 0, numBytesReadTotal);
        return parser.fixDuplicatedBytes(responseBuffer);
    }

    /**
     * This creates the request message ready to be send to heat pump
     *
     * @param request
     *            object containing necessary information to build request
     *            message
     * @return request message byte[]
     */
    protected byte[] createRequestMessage(byte[] requestytes) {
        return createRequestMessage(requestytes, DataParser.GET);
    }

    private byte[] createRequestMessage(byte[] requestytes, byte getOrSet) {
        short checkSum;
        byte[] requestMessage = concat(new byte[] { DataParser.HEADERSTART, getOrSet, (byte) 0x00 }, requestytes,
                new byte[] { DataParser.ESCAPE, DataParser.END });
        try {
            // prepare request message
            checkSum = parser.calculateChecksum(requestMessage);
            requestMessage[2] = parser.shortToByte(checkSum)[0];
            requestMessage = parser.addDuplicatedBytes(requestMessage);
        } catch (InvalidDataException e) {
            String requestStr = String.format("%02X", requestytes);
            logger.error("Could not create request [{}] message: {}", requestStr, e.toString());
            logger.error("Exception", e);
        }
        return requestMessage;
    }

    private byte[] concat(byte[]... arrays) {
        // Determine the length of the result array
        int totalLength = 0;
        for (int i = 0; i < arrays.length; i++) {
            totalLength += arrays[i].length;
        }
        // create the result array
        byte[] result = new byte[totalLength];
        // copy the source arrays into the result array
        int currentIndex = 0;
        for (int i = 0; i < arrays.length; i++) {
            System.arraycopy(arrays[i], 0, result, currentIndex, arrays[i].length);
            currentIndex += arrays[i].length;
        }
        return result;
    }

    private void restartConnector() {
        logger.debug("Restarting connector");
        close();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            logger.error("Interrupted", e);
            return;
        }
        connect();
    }

    /**
     * This method updates the parameter item of a heat pump
     * which might consist of multiple values in a single request.
     * Assumes that all RecordDefinitions have the same request bytes.
     */
    private Map<String, Object> writeDataValues(List<WriteValueDefinition> writeValueDefinitions) {
        logger.trace("writeDataValues");
        // We assume that all RecordDefinitions have the same request bytes
        final RecordDefinition firstRecordDefinition = writeValueDefinitions.get(0).getRecordDefinition();
        final String channelIds = String.join(";", writeValueDefinitions.stream()
                .map(WriteValueDefinition::getRecordDefinition).map(RecordDefinition::getChannelid).toList());
        final String newValues = String.join(";",
                writeValueDefinitions.stream().map(def -> def.getNewValue().toString()).toList());

        try {
            // get actual value for the corresponding request, in case settings have changed locally
            // as we do no have individual requests for each settings we need to
            // decode the new value
            // into a current response , the response is available in the
            // connector object
            final byte[] readRequestMessage = createRequestMessage(firstRecordDefinition.getRequestByte());
            final byte[] readResponse = getData(readRequestMessage);

            logger.debug("Read bytes: {}", DataParser.bytesToHex(readResponse, true));

            if (Arrays.equals(readRequestMessage, readResponse)) {
                logger.debug("Current value(s) for {} is already {}.", channelIds, newValues);
                return new HashMap<>();
            }

            // create new set request created from the existing read response
            final byte[] updateRequestMessage = Arrays.copyOf(readResponse, readResponse.length);
            writeValueDefinitions.forEach(
                    def -> parser.composeRecord(def.getNewValue(), updateRequestMessage, def.getRecordDefinition()));

            logger.debug("Setting new value(s) [{}] for channel(s) [{}]", newValues, channelIds);

            Thread.sleep(waitingTime);

            final byte[] setDataResponse = setData(updateRequestMessage);
            logger.debug("Set data response: {}", DataParser.bytesToHex(setDataResponse, true));

            byte[] currentMachineState;
            if (parser.setDataCheck(setDataResponse)) {
                logger.debug("Updated parameter {} successfully.", channelIds);
                // If header check passes, we assume that values have been set
                // so we can decode the new values from the composed update request
                currentMachineState = updateRequestMessage;
            } else {
                logger.warn("Update for parameter {} failed!", channelIds);
                currentMachineState = readResponse; // value hasn't been updated
            }

            return writeValueDefinitions.stream()
                    .map(def -> Map.entry(def.getRecordDefinition().getChannelid(),
                            parser.parseRecord(currentMachineState, def.getRecordDefinition())))
                    .collect(HashMap::new, (accumulator, entry) -> accumulator.put(entry.getKey(), entry.getValue()),
                            HashMap::putAll);

        } catch (InvalidDataException e) {
            logger.error("Invalid data occurred during update of value!", e);
        } catch (InterruptedException e) {
            logger.error("Interrupted", e);
        } catch (StiebelHeatPumpException e) {
            logger.error("Could not set data to heat pump!", e);
        }

        return new HashMap<>();
    }

    private class WriteValueDefinition {
        private final Object newValue;
        private final RecordDefinition recordDefinition;

        public WriteValueDefinition(Object newValue, RecordDefinition recordDefinition) {
            this.newValue = newValue;
            this.recordDefinition = recordDefinition;
        }

        public Object getNewValue() {
            return newValue;
        }

        public RecordDefinition getRecordDefinition() {
            return recordDefinition;
        }
    }
}
