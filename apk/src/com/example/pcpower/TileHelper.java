package com.example.pcpower;
import android.app.Activity;
import android.content.ComponentName;
import android.os.Build;
import android.widget.Toast;
/**
 * 「添加到控制中心」助手:API 33+ 走 StatusBarManager.requestAddTileService 弹系统确认框;
 * 低版本提示去控制中心编辑页手动添加。
 * 注意:回调直接收到 Integer 结果码(非 Intent),成功码为 TILE_ADDED / ALREADY_ADDED。
 */
public final class TileHelper {
    public static void requestAdd(Activity activity) {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(activity, "请在控制中心编辑页手动添加「远程开机」磁贴", Toast.LENGTH_LONG).show();
            return;
        }
        android.app.StatusBarManager sb = (android.app.StatusBarManager) activity.getSystemService(Activity.STATUS_BAR_SERVICE);
        ComponentName cn = new ComponentName(activity, PowerTileService.class);
        sb.requestAddTileService(cn, activity.getString(R.string.app_name),
                android.graphics.drawable.Icon.createWithResource(activity, R.drawable.ic_tile),
                activity.getMainExecutor(), (code) -> {
                    boolean ok = code != null && (code == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || code == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED);
                    Toast.makeText(activity, ok ? "请在系统弹窗中确认添加" : "添加未成功(" + code + "),可在控制中心编辑页手动添加", Toast.LENGTH_LONG).show();
                });
    }
    private TileHelper() {}
}
