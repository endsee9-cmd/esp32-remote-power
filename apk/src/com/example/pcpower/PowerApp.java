package com.example.pcpower;

import android.app.Application;

/** 应用入口:尽早初始化 PowerManager 单例,首页与磁贴共用同一份配置与状态。 */
public class PowerApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager.get(this);
    }
}
