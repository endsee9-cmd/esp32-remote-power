package com.example.pcpower;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

/**
 * 诊断页:公网联调(runPublicLinkTest)+ 当前 MQTT 连接状态 + 设备可用性
 * + 诊断日志(diagnosticsSnapshot,最新在上,可清空)。
 *
 * 本页只做链路检查,绝不发布 press 开机命令。
 */
public class DiagnosticsActivity extends Activity {

    private PowerManager pm;
    private TextView textConn, textAvail, textLinkResult, textLog;
    private Button btnLinkTest;
    private boolean linkRunning = false;

    private final PowerManager.Listener listener = new PowerManager.Listener() {
        @Override
        public void onConnectionChanged(boolean connected) {
            refreshStatus();
        }

        @Override
        public void onDeviceAvailChanged(PowerManager.DeviceAvail avail) {
            refreshStatus();
        }

        @Override
        public void onOpStateChanged(PowerManager.OpState state, long ts, String detail) {
            // 诊断页不展示开机操作状态
        }

        @Override
        public void onLog(String line) {
            loadLog();   // 联调过程中日志实时上屏
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        pm = PowerManager.get(this);

        textConn = findViewById(R.id.text_conn);
        textAvail = findViewById(R.id.text_avail);
        textLinkResult = findViewById(R.id.text_link_result);
        textLog = findViewById(R.id.text_log);
        btnLinkTest = findViewById(R.id.btn_link_test);

        btnLinkTest.setOnClickListener(v -> startLinkTest());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> {
            pm.clearDiagnostics();
            loadLog();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        pm.setListener(listener);
        refreshStatus();
        loadLog();
    }

    @Override
    protected void onPause() {
        pm.setListener(null);
        super.onPause();
    }

    /** 公网联调:runPublicLinkTest 完成后在回调里显示中文结论。 */
    private void startLinkTest() {
        if (linkRunning) return;
        linkRunning = true;
        btnLinkTest.setEnabled(false);
        textLinkResult.setText(R.string.link_test_running);
        textLinkResult.setTextColor(getColor(R.color.status_wait));
        pm.runPublicLinkTest(summary -> {
            linkRunning = false;
            btnLinkTest.setEnabled(true);
            textLinkResult.setText(summary == null || summary.isEmpty()
                    ? getString(R.string.diag_log_empty) : summary);
            textLinkResult.setTextColor(getColor(R.color.text_primary));
            loadLog();
        });
    }

    /** 当前 MQTT 连接状态 + 设备可用性。 */
    private void refreshStatus() {
        if (pm.isConnected()) {
            textConn.setText(R.string.conn_connected);
            textConn.setTextColor(getColor(R.color.status_ok));
        } else {
            textConn.setText(R.string.conn_disconnected);
            textConn.setTextColor(getColor(R.color.text_secondary));
        }

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
    }

    /** 诊断日志快照(契约约定最新在上),直接按序展示。 */
    private void loadLog() {
        List<String> snap = pm.diagnosticsSnapshot();
        if (snap == null || snap.isEmpty()) {
            textLog.setText(R.string.diag_log_empty);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : snap) {
            if (line == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        textLog.setText(sb.length() == 0 ? getString(R.string.diag_log_empty) : sb.toString());
    }
}
