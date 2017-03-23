/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.application;

import android.os.Bundle;

import org.apache.avro.specific.SpecificRecord;
import org.radarcns.android.RadarConfiguration;
import org.radarcns.android.device.BaseDeviceState;
import org.radarcns.android.device.DeviceManager;
import org.radarcns.android.device.DeviceService;
import org.radarcns.android.device.DeviceStatusListener;
import org.radarcns.android.device.DeviceTopics;
import org.radarcns.android.util.PersistentStorage;
import org.radarcns.key.MeasurementKey;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

import static org.radarcns.android.RadarConfiguration.DEFAULT_GROUP_ID_KEY;
import static org.radarcns.android.RadarConfiguration.DEVICE_SERVICES_TO_CONNECT;
import static org.radarcns.android.RadarConfiguration.SOURCE_ID_KEY;

public class ApplicationStatusService extends DeviceService {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationStatusService.class);
    private ApplicationStatusTopics topics;
    private String groupId;
    private String sourceId;
    private String devicesToConnect;

    @Override
    public void onCreate() {
        logger.info("Creating Application Status service {}", this);
        super.onCreate();

        topics = ApplicationStatusTopics.getInstance();
    }

    @Override
    protected DeviceManager createDeviceManager() {
        return new ApplicationStatusManager(this, this, groupId, getSourceId(), getDataHandler(), topics, devicesToConnect);
    }

    @Override
    protected BaseDeviceState getDefaultState() {
        ApplicationState newStatus = new ApplicationState();
        newStatus.setStatus(DeviceStatusListener.Status.DISCONNECTED);
        return newStatus;
    }

    @Override
    protected DeviceTopics getTopics() {
        return topics;
    }

    @Override
    protected List<AvroTopic<MeasurementKey, ? extends SpecificRecord>> getCachedTopics() {
        return Arrays.<AvroTopic<MeasurementKey, ? extends SpecificRecord>>asList(
                topics.getServerTopic(), topics.getRecordCountsTopic(), topics.getUptimeTopic());
    }

    @Override
    protected void onInvocation(Bundle bundle) {
        super.onInvocation(bundle);
        if (RadarConfiguration.hasExtra(bundle, DEFAULT_GROUP_ID_KEY)) {
            groupId = RadarConfiguration.getStringExtra(bundle, DEFAULT_GROUP_ID_KEY);
        }
        if (RadarConfiguration.hasExtra(bundle, DEVICE_SERVICES_TO_CONNECT)) {
            devicesToConnect = RadarConfiguration.getStringExtra(bundle, DEFAULT_GROUP_ID_KEY);
        }
    }

    public String getSourceId() {
        if (sourceId == null) {
            sourceId = PersistentStorage.loadOrStoreUUID(getClass(), SOURCE_ID_KEY);
        }
        return sourceId;
    }
}
