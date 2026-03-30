package com.example.wifihotspot.service;

interface IMonitorService {
    boolean startMonitor(String targetSsid, String targetBluetooth);
    void stopMonitor();
    boolean isMonitoring();
    String getLastDetection();
}
