package com.example.pcpower;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * 控制中心「远程开机」磁贴:一次性动作按钮(不是开关)。
 *
 * 点一次 = 收起控制中心并打开结果页,结果页自动发送指令,无需再点确认;
 * 发送逻辑与首页共用 PowerManager.sendPress(),同一条进行中的操作只发一次。
 * 配置缺失时进入设置页;锁屏时由系统先要求解锁再打开结果页。
 */
public class PowerTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        updateTile();
        if (isLocked()) {
            unlockAndRun(this::openAfterUnlock);
        } else {
            openAfterUnlock();
        }
    }

    private void openAfterUnlock() {
        PowerManager pm = PowerManager.get(this);
        PowerManager.Config c = pm.getConfig();
        if (c == null || !c.valid()) {
            Toast.makeText(this, R.string.tile_need_config, Toast.LENGTH_LONG).show();
            openActivity(SettingsActivity.class);
            return;
        }
        openActivity(ResultActivity.class);
    }

    /** API 34+ 用 PendingIntent 重载(方案要求);低版本用 Intent 重载。 */
    private void openActivity(Class<?> cls) {
        // 每次点击带新 nonce,结果页据此判断是否为一次「新的点击」
        Intent i = new Intent(this, cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ResultActivity.EXTRA_NONCE, System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this,
                    (int) (System.currentTimeMillis() & 0xFFFF), i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapse(i);
        }
    }

    /** 磁贴状态:已配置=待机动作(INACTIVE);未配置=不可用。 */
    private void updateTile() {
        Tile t = getQsTile();
        if (t == null) return;
        PowerManager.Config c = PowerManager.get(this).getConfig();
        boolean ok = c != null && c.valid();
        t.setLabel(getString(R.string.app_name));
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_tile));
        t.setState(ok ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        t.updateTile();
    }
}
