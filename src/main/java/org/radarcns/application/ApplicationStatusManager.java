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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Pair;

import org.radarcns.android.data.DataCache;
import org.radarcns.android.data.TableDataHandler;
import org.radarcns.android.device.BaseDeviceState;
import org.radarcns.android.device.BaseServiceConnection;
import org.radarcns.android.device.DeviceManager;
import org.radarcns.android.device.DeviceServiceBinder;
import org.radarcns.android.device.DeviceServiceProvider;
import org.radarcns.android.device.DeviceStatusListener;
import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.key.MeasurementKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static android.content.Context.BIND_WAIVE_PRIORITY;
import static org.radarcns.android.device.DeviceService.SERVER_RECORDS_SENT_NUMBER;
import static org.radarcns.android.device.DeviceService.SERVER_RECORDS_SENT_TOPIC;
import static org.radarcns.android.device.DeviceService.SERVER_STATUS_CHANGED;

public class ApplicationStatusManager implements DeviceManager {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationStatusManager.class);
    private static final long APPLICATION_UPDATE_INTERVAL_DEFAULT = 300; // seconds

    private final TableDataHandler dataHandler;
    private final Context context;

    private final ApplicationStatusService applicationStatusService;

    private final DataCache<MeasurementKey, ApplicationServerStatus> serverStatusTable;
    private final DataCache<MeasurementKey, ApplicationUptime> uptimeTable;
    private final DataCache<MeasurementKey, ApplicationExternalTime> ntpTimeTable;
    private final DataCache<MeasurementKey, ApplicationRecordCounts> recordCountsTable;

    private final ApplicationState deviceStatus;

    private String deviceName;
    private ScheduledFuture<?> serverStatusUpdateFuture;
    private final ScheduledExecutorService executor;

    private final List<BaseServiceConnection<BaseDeviceState>> services;
    private final List<Class<?>> serviceClasses;

    private final long creationTimeStamp;
    private final SntpClient sntpClient;
    private final String ntpServer;

    private boolean isRegistered = false;

    private final BroadcastReceiver serverStatusListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(SERVER_STATUS_CHANGED)) {
                final ServerStatusListener.Status status = ServerStatusListener.Status.values()[intent.getIntExtra(SERVER_STATUS_CHANGED, 0)];
                deviceStatus.setServerStatus(status);
            } else if (intent.getAction().equals(SERVER_RECORDS_SENT_TOPIC)) {
                int numberOfRecordsSent = intent.getIntExtra(SERVER_RECORDS_SENT_NUMBER, 0);
                if (numberOfRecordsSent != -1) {
                    deviceStatus.addRecordsSent(numberOfRecordsSent);
                }
            }
        }
    };

    public ApplicationStatusManager(Context context, ApplicationStatusService applicationStatusService, String groupId, String sourceId, TableDataHandler dataHandler, ApplicationStatusTopics topics, String devicesToConnect, String ntpServer) {
        this.dataHandler = dataHandler;
        this.serverStatusTable = dataHandler.getCache(topics.getServerTopic());
        this.uptimeTable = dataHandler.getCache(topics.getUptimeTopic());
        this.recordCountsTable = dataHandler.getCache(topics.getRecordCountsTopic());
        this.ntpTimeTable = dataHandler.getCache(topics.getExternalTimeTopic());

        sntpClient = new SntpClient();
        if (ntpServer == null || ntpServer.trim().isEmpty()) {
            this.ntpServer = null;
        } else {
            this.ntpServer = ntpServer.trim();
        }


        this.applicationStatusService = applicationStatusService;

        this.context = context;
//        sensorManager = null;
        this.deviceStatus = new ApplicationState();
        this.deviceStatus.getId().setUserId(groupId);
        this.deviceStatus.getId().setSourceId(sourceId);
        deviceName = context.getString(R.string.app_name);
//        updateStatus(DeviceStatusListener.Status.READY);

        serviceClasses = new ArrayList<>();
        Scanner sc = new Scanner(devicesToConnect);
        while (sc.hasNext()) {
            String providerName = sc.next();
            if (providerName.charAt(0) == '.') {
                providerName = "org.radarcns" + providerName;
            }
            try {
                DeviceServiceProvider provider = (DeviceServiceProvider) Class.forName(providerName).newInstance();
                serviceClasses.add(provider.getServiceClass());
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | ClassCastException ex) {
                logger.error("Cannot load provider {}", providerName, ex);
            }
        }
        services = new ArrayList<>(serviceClasses.size());
        creationTimeStamp = System.currentTimeMillis();

        // Scheduler TODO: run executor with existing thread pool/factory
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void start(@NonNull Set<String> acceptableIds) {
        logger.info("Starting ApplicationStatusManager");
        for (Class clazz : serviceClasses) {
            Intent serviceIntent = new Intent(context, clazz);
            BaseServiceConnection<BaseDeviceState> conn = new BaseServiceConnection<>(BaseDeviceState.CREATOR, clazz.getName());
            services.add(conn);
            context.bindService(serviceIntent, conn, BIND_WAIVE_PRIORITY);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVER_STATUS_CHANGED);
        filter.addAction(SERVER_RECORDS_SENT_TOPIC);
        context.registerReceiver(serverStatusListener, filter);

        // Application status
        setApplicationStatusUpdateRate(APPLICATION_UPDATE_INTERVAL_DEFAULT);

        isRegistered = true;
        updateStatus(DeviceStatusListener.Status.CONNECTED);
    }

    public final synchronized void setApplicationStatusUpdateRate(final long period) {
        if (serverStatusUpdateFuture != null) {
            serverStatusUpdateFuture.cancel(false);
        }

        serverStatusUpdateFuture = executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                logger.info("Updating application status");
                try {
                    processServerStatus();
                    processUptime();
                    processRecordsSent();
                    processReferenceTime();
                } catch (Exception e) {
                    logger.error("Failed to update application status", e);
                }
            }
        }, 0, period, TimeUnit.SECONDS);

        logger.info("App status updater: listener activated and set to a period of {}", period);
    }

    @Override
    public boolean isClosed() {
        return !isRegistered;
    }

    @Override
    public BaseDeviceState getState() {
        return deviceStatus;
    }

    @Override
    public String getName() {
        return deviceName;
    }

    public void processReferenceTime() {
        if (ntpServer != null) {
            if (sntpClient.requestTime(ntpServer, 5000)) {
                double delay = sntpClient.getRoundTripTime() / 1000d;
                double time = System.currentTimeMillis() / 1000d;
                double ntpTime =  (sntpClient.getNtpTime() + SystemClock.elapsedRealtime()
                        - sntpClient.getNtpTimeReference()) / 1000d;
                ApplicationExternalTime value = new ApplicationExternalTime(time, time, ntpTime,
                        ntpServer, ExternalTimeProtocol.SNTP, delay);

                dataHandler.addMeasurement(ntpTimeTable, deviceStatus.getId(), value);
            }
        }
    }

    public void processServerStatus() {
        double timeReceived = System.currentTimeMillis() / 1_000d;

        ServerStatus status;
        switch (deviceStatus.getServerStatus()) {
            case CONNECTED:
            case READY:
            case UPLOADING:
                status = ServerStatus.CONNECTED;
                break;
            case DISCONNECTED:
            case DISABLED:
            case UPLOADING_FAILED:
                status = ServerStatus.DISCONNECTED;
                break;
            default:
                status = ServerStatus.UNKNOWN;
        }
        String ipAddress = getIpAddress();
        logger.info("Server Status: {}; Device IP: {}", status, ipAddress);

        ApplicationServerStatus value = new ApplicationServerStatus(timeReceived, timeReceived, status, ipAddress);

        dataHandler.addMeasurement(serverStatusTable, deviceStatus.getId(), value);
    }

    private String getIpAddress() {
        // Find Ip via NetworkInterfaces. Works via wifi, ethernet and mobile network
        InetAddress result = null;
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress()) {
                        // This finds both xx.xx.xx ip and rmnet. Last one is always ip.
                        result = inetAddress;
                    }
                }
            }
        } catch (SocketException ex) {
            logger.warn("No IP Address could be determined", ex);
            result = null;
        }
        if (result != null) {
            return result.getHostAddress();
        } else {
            return null;
        }
    }

    public void processUptime() {
        double timeReceived = System.currentTimeMillis() / 1_000d;

        double uptime = (System.currentTimeMillis() - creationTimeStamp)/1000d;
        ApplicationUptime value = new ApplicationUptime(timeReceived, timeReceived, uptime);

        dataHandler.addMeasurement(uptimeTable, deviceStatus.getId(), value);
    }

    public void processRecordsSent() {
        double timeReceived = System.currentTimeMillis() / 1_000d;

        Pair<Long, Long> localRecords = ((DeviceServiceBinder)applicationStatusService.getBinder()).numberOfRecords();
        int recordsCachedUnsent = localRecords.first == -1L ? 0 : localRecords.first.intValue();
        int recordsCachedSent = localRecords.second == -1L ? 0 : localRecords.second.intValue();

        for (BaseServiceConnection<?> conn : services) {
            if (conn.hasService()) {
                try {
                    Pair<Long, Long> numRecords = conn.numberOfRecords();
                    if (numRecords.first != -1L) {
                        recordsCachedUnsent += numRecords.first.intValue();
                    }
                    if (numRecords.second != -1L) {
                        recordsCachedSent += numRecords.second.intValue();
                    }
                } catch (RemoteException e) {
                    logger.warn("Failed to get server status from connection");
                }
            }
        }

        int recordsCached = recordsCachedUnsent + recordsCachedSent;
        int recordsSent = deviceStatus.getRecordsSent();

        logger.info("Number of records: {sent: {}, unsent: {}, cached: {}}",
                recordsSent, recordsCachedUnsent, recordsCached);
        ApplicationRecordCounts value = new ApplicationRecordCounts(timeReceived, timeReceived,
                recordsCached, recordsSent, recordsCachedUnsent);
        dataHandler.addMeasurement(recordCountsTable, deviceStatus.getId(), value);
    }

    @Override
    public void close() throws IOException {
        logger.info("Closing ApplicationStatusManager");
        executor.shutdown();
        context.unregisterReceiver(serverStatusListener);
        for (BaseServiceConnection<BaseDeviceState> conn : services) {
            context.unbindService(conn);
        }
        isRegistered = false;
        updateStatus(DeviceStatusListener.Status.DISCONNECTED);
    }

    private synchronized void updateStatus(DeviceStatusListener.Status status) {
        this.deviceStatus.setStatus(status);
        this.applicationStatusService.deviceStatusUpdated(this, status);
    }
}