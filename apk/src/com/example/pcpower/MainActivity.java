package com.example.pcpower;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 首页:远程开机主界面(原生 View + res 资源驱动)。
 *
 * 状态一律来自 PowerManager 单例:本类只做展示与转发用户操作,
 * 旋转重建后 onResume 从 PowerManager 恢复状态,绝不重复发送命令。
 *
 * 状态流转(措辞与方案一致):
 *  - 发送等待期显示「等待回执…」,按钮禁用;
 *  - 收到 done →「电源键已按下」;failed →「执行失败」;
 *  - 超时 →「结果未知,先查看电脑状态」;
 *  - 全程不显示、也不暗示「Windows 已启动」。
 */
public class MainActivity extends Activity {

    private PowerManager pm;
    private TextView textTarget, textConn, textAvail, textLast, textOp;
    private Button btnStart, btnPressAngle;
    private boolean connHint = false;   // 触发过连接动作但尚未连上时,显示「连接中…」

    /** PowerManager 事件 → 全量刷新界面(状态源唯一,刷新幂等)。 */
    private final PowerManager.Listener listener = new PowerManager.Listener() {
        @Override
        public void onConnectionChanged(boolean connected) {
            if (connected) connHint = false;
            refresh();
        }

        @Override
        public void onDeviceAvailChanged(PowerManager.DeviceAvail avail) {
            refresh();
        }

        @Override
        public void onOpStateChanged(PowerManager.OpState state, long ts, String detail) {
            refresh();
        }

        @Override
        public void onLog(String line) {
            // 首页不展示滚动日志,诊断日志在 DiagnosticsActivity 查看
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        pm = PowerManager.get(this);

        textTarget = findViewById(R.id.text_target);
        textConn = findViewById(R.id.text_conn);
        textAvail = findViewById(R.id.text_avail);
        textLast = findViewById(R.id.text_last);
        textOp = findViewById(R.id.text_op);
        btnStart = findViewById(R.id.btn_start);
        btnPressAngle = findViewById(R.id.btn_press_angle);

        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btnStart.setOnClickListener(v -> onPress());
        btnPressAngle.setOnClickListener(v -> editPressAngles());
    }

    @Override
    protected void onResume() {
        super.onResume();
        pm.setListener(listener);
        // 已配置过 Broker 时自动维持连接(PowerManager 内部保证幂等,不会重复建连)
        PowerManager.Config c = pm.getConfig();
        if (c != null && c.broker != null && !c.broker.trim().isEmpty()) {
            connHint = !pm.isConnected();
            pm.ensureConnected();
        }
        refresh();
    }

    @Override
    protected void onPause() {
        pm.setListener(null);
        super.onPause();
    }

    /** 主按钮:发送开机命令。等待回执期间按钮禁用,这里再双保险。 */
    private void onPress() {
        if (pm.hasInFlight() || pm.getOpState() == PowerManager.OpState.WAITING_RECEIPT) {
            return;   // 等待期不允许再次发送
        }
        if (!pm.isReadyToSend()) {
            connHint = true;
            pm.ensureConnected();
            toast(getString(R.string.press_need_connect));
            refresh();
            return;
        }
        if (!pm.sendPress()) {
            toast(getString(R.string.press_send_failed));
        }
        refresh();
    }

    private void configureAnglePicker(NumberPicker picker, int degrees) {
        picker.setMinValue(PowerManager.MIN_PRESS_DEGREES);
        picker.setMaxValue(PowerManager.MAX_PRESS_DEGREES);
        picker.setWrapSelectorWheel(false);
        picker.setValue(degrees);
    }

    private void editPressAngles() {
        View angles = getLayoutInflater().inflate(R.layout.dialog_press_angles, null);
        NumberPicker press = angles.findViewById(R.id.picker_press_angle);
        NumberPicker release = angles.findViewById(R.id.picker_return_angle);
        configureAnglePicker(press, pm.getPressDegrees());
        configureAnglePicker(release, pm.getReturnDegrees());
        new AlertDialog.Builder(this)
                .setTitle(R.string.press_angle_title)
                .setView(angles)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    press.clearFocus();
                    release.clearFocus();
                    pm.savePressAngles(press.getValue(), release.getValue());
                    refresh();
                })
                .show();
    }

    /** 全量刷新:连接状态 / 设备可用性 / 上次操作 / 当前操作状态 / 按钮可用性。 */
    private void refresh() {
        PowerManager.Config c = pm.getConfig();
        String name = (c != null && c.targetName != null && !c.targetName.trim().isEmpty())
                ? c.targetName.trim() : getString(R.string.target_unnamed);
        textTarget.setText(name);

        // C5 连接状态
        if (pm.isConnected()) {
            textConn.setText(R.string.conn_connected);
            textConn.setTextColor(getColor(R.color.status_ok));
        } else if (connHint) {
            textConn.setText(R.string.conn_connecting);
            textConn.setTextColor(getColor(R.color.status_wait));
        } else {
            textConn.setText(R.string.conn_disconnected);
            textConn.setTextColor(getColor(R.color.status_error));
        }

        // 设备可用性(DeviceAvail → 中文)
        PowerManager.DeviceAvail avail = pm.getDeviceAvail();
        if (avail == PowerManager.DeviceAvail.ONLINE) {
            textAvail.setText(R.string.device_online);
            textAvail.setTextColor(getColor(R.color.status_ok));
        } else if (avail == PowerManager.DeviceAvail.STALE) {
            textAvail.setText(R.string.device_offline);
            textAvail.setTextColor(getColor(R.color.status_warn));
        } else {
            textAvail.setText(R.string.device_unknown);
            textAvail.setTextColor(getColor(R.color.text_secondary));
        }

        // 上次操作结果
        String last = pm.getLastResultText();
        if (last == null || last.trim().isEmpty()) {
            textLast.setText(R.string.last_op_none);
        } else {
            textLast.setText(getString(R.string.last_op_fmt, last.trim()));
        }

        // 当前操作状态流转
        PowerManager.OpState st = pm.getOpState();
        String op;
        int opColor;
        switch (st) {
            case CONNECTING:
                op = getString(R.string.op_connecting);
                opColor = getColor(R.color.status_wait);
                break;
            case WAITING_RECEIPT:
                op = getString(R.string.op_waiting);
                opColor = getColor(R.color.status_wait);
                break;
            case PRESSED:
                op = getString(R.string.op_pressed);
                opColor = getColor(R.color.status_ok);
                break;
            case FAILED:
                op = getString(R.string.op_failed);
                opColor = getColor(R.color.status_error);
                break;
            case UNKNOWN:
                op = getString(R.string.op_unknown);
                opColor = getColor(R.color.status_warn);
                break;
            default:
                op = getString(R.string.op_idle);
                opColor = getColor(R.color.text_secondary);
                break;
        }
        textOp.setText(op);
        textOp.setTextColor(opColor);
        btnPressAngle.setText(getString(R.string.press_angle_button, pm.getPressDegrees(), pm.getReturnDegrees()));
        btnPressAngle.setEnabled(!pm.hasInFlight());

        // 等待期/连接中禁用主按钮
        btnStart.setEnabled(pm.isReadyToSend()
                && !pm.hasInFlight()
                && st != PowerManager.OpState.WAITING_RECEIPT
                && st != PowerManager.OpState.CONNECTING);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
