package com.example.wifihotspot.service

import rikka.shizuku.Shizuku

object ShizukuTest {
    fun isAvailable(): Boolean {
        return Shizuku.pingBinder()
    }
    
    fun isGranted(): Boolean {
        return Shizuku.checkSelfPermission() == 0
    }
    
    fun getProcess() {
        val p = Shizuku.newProcess(arrayOf("echo", "hello"), null, null)
        p.waitFor()
    }
}
