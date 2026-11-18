package com.example.pcpower;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页:目标电脑名称 + MQTT 连接参数。
 *
 * - 编辑框回显 PowerManager.getConfig() 现有值,保存走 saveConfig(Config);
 * - 草稿:填一半直接退出时,onPause 把当前表单暂存进 prefs;下次进来自动恢复,
 *   与已保存配置不同时提示「未保存」,点「保存设置」后草稿转正并清除;
 * - 「连接检查」只调 ensureConnected 并显示连接结果,绝不发送 press;
 * - 「添加到控制中心」API 33+ 走 TileHelper.requestAdd,低版本提示手动添加;
 * - 「连接诊断」进入 DiagnosticsActivity。
 */
public class SettingsActivity extends Activity {

    private static final String PREFS = "pcpower_prefs";
    // 草稿键(独立于正式配置键,避免半填的值被当成有效配置去连)
    private static final String D_TARGET = "draft_target";
    private static final String D_BROKER = "draft_broker";
    private static final String D_PORT = "draft_port";
    private static final String D_TLS = "draft_tls";
    private static final String D_USER = "draft_user";
    private static final String D_PASS = "draft_pass";
    private static final String D_PREFIX = "draft_prefix";

    private PowerManager pm;
    private SharedPreferences prefs;
    private EditText editTarget, editBroker, editPort, editUser, editPass, editPrefix;
    private Switch switchTls;
    private TextView textStatus;
    private boolean connHint = false;
    private boolean draftRestored = false;
    private boolean fieldsReady = false;   // onCreate 完成前不响应监听器写草稿

    private final PowerManager.Listener listener = new PowerManager.Listener() {
        @Override
        public void onConnectionChanged(boolean connected) {
            if (connected) connHint = false;
            refreshStatus();
        }

        @Override
        public void onDeviceAvailChanged(PowerManager.DeviceAvail avail) {
        }

        @Override
        public void onOpStateChanged(PowerManager.OpState state, long ts, String detail) {
        }

        @Override
        public void onLog(String line) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        pm = PowerManager.get(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        editTarget = findViewById(R.id.edit_target);
        editBroker = findViewById(R.id.edit_broker);
        editPort = findViewById(R.id.edit_port);
        editUser = findViewById(R.id.edit_user);
        editPass = findViewById(R.id.edit_pass);
        editPrefix = findViewById(R.id.edit_prefix);
        switchTls = findViewById(R.id.switch_tls);
        textStatus = findViewById(R.id.text_conn_status);

        findViewById(R.id.btn_save).setOnClickListener(v -> onSave());
        findViewById(R.id.btn_check).setOnClickListener(v -> onCheck());
        findViewById(R.id.btn_add_tile).setOnClickListener(v -> TileHelper.requestAdd(this));
        findViewById(R.id.btn_diagnostics).setOnClickListener(v ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));

        // 边输入边存草稿:即使闪退/进程被杀也不丢已输入内容(onPause 暂存作兜底)
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                if (fieldsReady) writeDraft();
            }
        };
        editTarget.addTextChangedListener(watcher);
        editBroker.addTextChangedListener(watcher);
        editPort.addTextChangedListener(watcher);
        editUser.addTextChangedListener(watcher);
        editPass.addTextChangedListener(watcher);
        editPrefix.addTextChangedListener(watcher);
        switchTls.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (fieldsReady) writeDraft();
        });
        fieldsReady = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        pm.setListener(listener);
        echoConfig();
        connHint = !pm.isConnected();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        writeDraft();   // 填一半退出也保留输入,下次进来恢复
        pm.setListener(null);
        super.onPause();
    }

    /** 回显:有草稿(与已保存配置不同)优先恢复草稿并提示;否则显示已保存配置。 */
    private void echoConfig() {
        PowerManager.Config c = pm.getConfig();
        if (c == null) return;
        if (hasDraft() && draftDiffers(c)) {
            editTarget.setText(prefs.getString(D_TARGET, ""));
            editBroker.setText(prefs.getString(D_BROKER, ""));
            editPort.setText(prefs.getString(D_PORT, "8883"));
            editUser.setText(prefs.getString(D_USER, ""));
            editPass.setText(prefs.getString(D_PASS, ""));
            editPrefix.setText(prefs.getString(D_PREFIX, ""));
            switchTls.setChecked(prefs.getBoolean(D_TLS, true));
            textStatus.setText(R.string.settings_draft_restored);
            textStatus.setTextColor(getColor(R.color.text_secondary));
            draftRestored = true;
            return;
        }
        editTarget.setText(n(c.targetName));
        editBroker.setText(n(c.broker));
        editPort.setText(n(c.port));
        editUser.setText(n(c.user));
        editPass.setText(n(c.pass));
        editPrefix.setText(n(c.prefix));
        switchTls.setChecked(c.tls);
        draftRestored = false;
    }

    private boolean hasDraft() {
        return prefs.contains(D_BROKER);
    }

    /** 草稿与已保存配置是否有差异。 */
    private boolean draftDiffers(PowerManager.Config c) {
        return !eq(prefs.getString(D_TARGET, ""), c.targetName)
                || !eq(prefs.getString(D_BROKER, ""), c.broker)
                || !eq(prefs.getString(D_PORT, ""), c.port)
                || prefs.getBoolean(D_TLS, true) != c.tls
                || !eq(prefs.getString(D_USER, ""), c.user)
                || !eq(prefs.getString(D_PASS, ""), c.pass)
                || !eq(prefs.getString(D_PREFIX, ""), c.prefix);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 离开页面时暂存当前表单。 */
    private void writeDraft() {
        prefs.edit()
                .putString(D_TARGET, editTarget.getText().toString())
                .putString(D_BROKER, editBroker.getText().toString())
                .putString(D_PORT, editPort.getText().toString())
                .putBoolean(D_TLS, switchTls.isChecked())
                .putString(D_USER, editUser.getText().toString())
                .putString(D_PASS, editPass.getText().toString())
                .putString(D_PREFIX, editPrefix.getText().toString())
                .apply();
    }

    /** 保存成功后草稿转正,清除暂存。 */
    private void clearDraft() {
        prefs.edit()
                .remove(D_TARGET).remove(D_BROKER).remove(D_PORT).remove(D_TLS)
                .remove(D_USER).remove(D_PASS).remove(D_PREFIX)
                .apply();
        draftRestored = false;
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    /** 校验并保存配置;成功返回 true(与旧版规则一致:端口范围 + 前缀禁通配符)。 */
    private boolean saveConfig() {
        String broker = editBroker.getText().toString().trim();
        String port = editPort.getText().toString().trim();
        String prefix = editPrefix.getText().toString().trim();
        if (broker.isEmpty() || port.isEmpty() || prefix.isEmpty()) {
            toast(getString(R.string.check_need_fields));
            return false;
        }
        try {
            int p = Integer.parseInt(port);
            if (p <= 0 || p > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            toast(getString(R.string.check_bad_port));
            return false;
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (prefix.isEmpty() || prefix.contains("+") || prefix.contains("#")) {
            toast(getString(R.string.check_bad_prefix));
            return false;
        }
        // 公网测试服务器匿名无鉴权,只用于诊断联调;正式控制必须用有账号的 Broker(v1.3 同款规则)
        if ("test.mosquitto.org".equalsIgnoreCase(broker)
                || "test.mosquitto.org.".equalsIgnoreCase(broker)) {
            toast(getString(R.string.check_use_diag_for_test));
            return false;
        }
        PowerManager.Config c = new PowerManager.Config();
        c.targetName = editTarget.getText().toString().trim();
        c.broker = broker;
        c.port = port;
        c.tls = switchTls.isChecked();
        c.user = editUser.getText().toString().trim();
        c.pass = editPass.getText().toString();
        c.prefix = prefix;
        pm.saveConfig(c);
        return true;
    }

    private void onSave() {
        if (saveConfig()) {
            clearDraft();
            refreshStatus();
            Toast.makeText(this, R.string.check_saved, Toast.LENGTH_SHORT).show();
        }
    }

    /** 连接检查:先保存,再 ensureConnected,只看连接结果,不发 press。 */
    private void onCheck() {
        if (!saveConfig()) return;
        clearDraft();
        connHint = true;
        pm.ensureConnected();
        refreshStatus();
    }

    private void refreshStatus() {
        // 刚恢复草稿时保留提示文字,不被连接状态覆盖
        if (draftRestored) return;
        if (pm.isConnected()) {
            textStatus.setText(R.string.check_connected);
            textStatus.setTextColor(getColor(R.color.status_ok));
        } else if (connHint) {
            textStatus.setText(R.string.check_connecting);
            textStatus.setTextColor(getColor(R.color.status_wait));
        } else {
            textStatus.setText(R.string.check_disconnected);
            textStatus.setTextColor(getColor(R.color.text_secondary));
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
