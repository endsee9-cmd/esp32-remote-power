package com.example.pcpower;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 磁贴点击后的简短结果页:自动发送指令并展示连接/发送/等待/失败全程。
 *
 * 应用进程未运行时也能走完整流程(磁贴拉起本页,PowerManager 惰性初始化)。
 * 防重复:每次磁贴点击带新 nonce,旋转重建/同一次点击只会自动发送一次;
 * 连点两次时第二次进入会看到同一条进行中的操作(共用 PowerManager)。
 * 超时显示「结果未知」,绝不自动补发;配置缺失则进入设置。
 */
public class ResultActivity extends Activity {

    public static final String EXTRA_NONCE = "nonce";

    /** 已自动发送过的 nonce(静态:旋转重建后保持同一次点击只发一次)。 */
    private static long sLastNonceHandled;

    private PowerManager pm;
    private TextView textStatus, textHint, textAvail;
    private long nonce;
    private final Handler handler = new Handler();
    private boolean finished;
    private boolean connectTimedOut;

    private final Runnable connectGiveUp = new Runnable() {
        @Override
        public void run() {
            if (pm.isReadyToSend()) return;
            connectTimedOut = true;
            textStatus.setText(R.string.result_connect_fail);
            textStatus.setTextColor(getColor(R.color.status_error));
            textHint.setText(R.string.result_connect_fail_hint);
        }
    };

    private final Runnable autoFinish = new Runnable() {
        @Override
        public void run() {
            if (pm.getOpState() == PowerManager.OpState.PRESSED
                    || pm.getOpState() == PowerManager.OpState.FAILED) {
                finished = true;
                finish();
            }
        }
    };

    private final PowerManager.Listener listener = new PowerManager.Listener() {
        @Override
        public void onConnectionChanged(boolean connected) {
            if (pm.isReadyToSend() && !connectTimedOut) {
                handler.removeCallbacks(connectGiveUp);
                maybeAutoSend();
            }
            refresh();
        }

        @Override
        public void onDeviceAvailChanged(PowerManager.DeviceAvail avail) {
            refresh();
        }

        @Override
        public void onOpStateChanged(PowerManager.OpState state, long ts, String detail) {
            refresh();
            // 明确结果(按下/失败)后 3 秒自动收起;「结果未知」保持页面让人看到提示
            handler.removeCallbacks(autoFinish);
            if (state == PowerManager.OpState.PRESSED || state == PowerManager.OpState.FAILED) {
                handler.postDelayed(autoFinish, 3000);
            }
        }

        @Override
        public void onLog(String line) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);
        pm = PowerManager.get(this);
        nonce = getIntent().getLongExtra(EXTRA_NONCE, 0L);

        textStatus = findViewById(R.id.text_result_status);
        textAvail = findViewById(R.id.text_result_avail);
        textHint = findViewById(R.id.text_result_hint);
        Button btnClose = findViewById(R.id.btn_result_close);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finished = true;
                finish();
            }
        });

        PowerManager.Config c = pm.getConfig();
        if (c == null || !c.valid()) {
            Toast.makeText(this, R.string.tile_need_config, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return;
        }
        pm.ensureConnected();
        handler.postDelayed(connectGiveUp, 20_000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pm.setListener(listener);
        maybeAutoSend();
        refresh();
    }

    @Override
    protected void onPause() {
        pm.setListener(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** 自动发送:同一个 nonce 只发一次;连接就绪后立即发;进行中的操作不重发。 */
    private void maybeAutoSend() {
        if (connectTimedOut || nonce == 0 || nonce == sLastNonceHandled) {
            return;
        }
        if (!pm.isReadyToSend()) {
            textStatus.setText(R.string.result_connecting);
            textStatus.setTextColor(getColor(R.color.status_wait));
            return;
        }
        sLastNonceHandled = nonce;
        if (!pm.hasInFlight()) {
            pm.sendPress();
        }
        refresh();
    }

    /** 状态流转 + 设备可用性提示。 */
    private void refresh() {
        PowerManager.OpState st = pm.getOpState();
        String op;
        int color;
        switch (st) {
            case WAITING_RECEIPT:
                op = getString(R.string.op_waiting);
                color = getColor(R.color.status_wait);
                break;
            case PRESSED:
                op = getString(R.string.op_pressed);
                color = getColor(R.color.status_ok);
                break;
            case FAILED:
                op = getString(R.string.op_failed);
                color = getColor(R.color.status_error);
                break;
            case UNKNOWN:
                op = getString(R.string.op_unknown);
                color = getColor(R.color.status_warn);
                break;
            case CONNECTING:
                op = getString(R.string.op_connecting);
                color = getColor(R.color.status_wait);
                break;
            default:
                op = pm.isConnected() ? getString(R.string.result_sent_none)
                                      : getString(R.string.result_connecting);
                color = getColor(R.color.text_secondary);
                break;
        }
        textStatus.setText(op);
        textStatus.setTextColor(color);

        PowerManager.DeviceAvail avail = pm.getDeviceAvail();
        if (avail == PowerManager.DeviceAvail.STALE) {
            textAvail.setText(R.string.result_offline_hint);
            textAvail.setVisibility(View.VISIBLE);
        } else {
            textAvail.setVisibility(View.GONE);
        }

        if (st == PowerManager.OpState.UNKNOWN) {
            textHint.setText(R.string.warning_press);
            textHint.setVisibility(View.VISIBLE);
        } else if (st == PowerManager.OpState.IDLE && !pm.isConnected()) {
            textHint.setVisibility(View.GONE);
        } else {
            textHint.setVisibility(View.GONE);
        }
    }
}
