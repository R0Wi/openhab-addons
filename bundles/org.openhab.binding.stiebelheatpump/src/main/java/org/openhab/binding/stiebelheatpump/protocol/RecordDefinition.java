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
package org.openhab.binding.stiebelheatpump.protocol;

/**
 * Record definition class for Stiebel heat pump requests.
 *
 * @author Peter Kreutzer
 */

public class RecordDefinition {

    public static enum Type {
        Sensor,
        Status,
        Settings;
    }

    private String channelid;

    private byte[] requestByte;

    private byte[] requestByte2;

    private Type dataType;

    private int position;

    private int length;

    private double scale;

    private int bitPosition;

    private double min;

    private double max;

    private double step;

    private String unit;

    public RecordDefinition() {
    }

    public String getChannelid() {
        return channelid;
    }

    public void setChannelid(String channelid) {
        this.channelid = channelid;
    }

    public byte[] getRequestByte() {
        return requestByte;
    }

    public void setRequestByte(byte[] requestByte) {
        this.requestByte = requestByte;
    }

    public byte[] getRequestByte2() {
        return requestByte2;
    }

    public void setRequestByte2(byte[] requestByte2) {
        this.requestByte2 = requestByte2;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public double getScale() {
        return scale;
    }

    public void setScale(double scale) {
        this.scale = scale;
    }

    public Type getDataType() {
        return dataType;
    }

    public void setDataType(Type dataType) {
        this.dataType = dataType;
    }

    public double getMin() {
        return min;
    }

    public void setMin(double min) {
        this.min = min;
    }

    public double getMax() {
        return max;
    }

    public void setMax(double max) {
        this.max = max;
    }

    public double getStep() {
        return step;
    }

    public void setStep(double step) {
        this.step = step;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public int getBitPosition() {
        return bitPosition;
    }

    public void setBitPosition(int bitPosition) {
        this.bitPosition = bitPosition;
    }
}
