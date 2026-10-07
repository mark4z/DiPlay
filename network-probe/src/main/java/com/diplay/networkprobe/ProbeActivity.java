package com.diplay.networkprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Build;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.RadioButton;
import android.widget.RadioGroup;

public final class ProbeActivity extends Activity {
    public static volatile boolean isForeground;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean pending, resumed, awaitingSystemPrompt, prepareWhenResumed, startWhenResumed;
    private TextView state;
    private Button start;
    private RadioButton single, dual;
    private int selectedMode = ProbePolicy.SINGLE;
    private int pendingMode;
    private AlertDialog warning;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            state.setText(ProbeVpnService.status
                    + "\nNext mode / 下次模式: " + ProbePolicy.modeName(selectedMode)
                    + "\nFixed URL: " + ProbePolicy.healthUrl(ProbePolicy.DEFAULT_PORT)
                    + "\nSession / 最近一次测试: " + (ProbeVpnService.sessionMode == 0 ? "NOT_RUN" : ProbePolicy.modeName(ProbeVpnService.sessionMode))
                    + "\nInterface snapshots / 接口快照:\n" + ProbeVpnService.interfaceReport
                    + "\nLocal self-check: " + ProbeVpnService.selfCheck + " (local only; not hotspot reachability)"
                    + "\nExternal TCP accepts: " + ProbeVpnService.accepted + "\nExternal HTTP health responses written: " + ProbeVpnService.health
                    + "\nLast result: " + ProbeVpnService.lastError
                    + "\nRemaining seconds: " + Math.max(0, (ProbeVpnService.endsAt - SystemClock.elapsedRealtime()) / 1000));
            updateControls();
            main.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(28, 28, 28, 28);
        scroll.addView(column);
        TextView title = new TextView(this);
        title.setText("DiPlay Network Probe · health-only v4\n\n独立 HTTP 网络实验，默认关闭。单 TUN / 双 TUN 均固定使用 18080。每次最多 5 分钟，启动最多等待 15 秒。保持本页前台并亮屏；切换应用、锁屏、旋转都会停止。\n\n先自行停止其他 VPN，包括 DiPlay 有线 VPN。每次由你确认；Android 如需 VPN 授权，请本人操作。勿启用始终开启或阻止无 VPN 连接。\n\n单模式建立 100.96.23.17/32。双模式随后建立 192.168.247.2/32；Android 通常会停用第一个接口，本实验观察实际结果，不重新启用它。两者仅使用各自 /32 主机路由、allowBypass 和本应用白名单，不设默认路由或 DNS，不读写 TUN、不转发、不修改安全设置。\n\n所有原始描述符只保留到本次测试结束，不复制、不持久保留。Stop 将关闭全部测试描述符。界面显示关闭后的接口快照；如仍可见或清理失败，请在系统设置断开 VPN 并强行停止应用。\n\n本版两个模式配置相同，仅第二次建立不同；它们新增主机路由和 allowBypass，不能直接与 v3 历史结果作因果比较。\n\n先用另一台手机连接热点，再手动打开固定 HTTP 地址；车机请仅在停车时测试。自检 PASS 仅代表本机路径。测试结果不代表通用 Android、TLS、媒体或 CarPlay 兼容性。\n");
        column.addView(title);
        RadioGroup modes = new RadioGroup(this);
        single = new RadioButton(this); single.setId(View.generateViewId());
        single.setText("SINGLE · 单 TUN 对照（默认）"); modes.addView(single);
        dual = new RadioButton(this); dual.setId(View.generateViewId());
        dual.setText("DUAL_HANDOVER · 双 TUN 顺序建立实验"); modes.addView(dual);
        modes.check(single.getId());
        modes.setOnCheckedChangeListener((group, id) -> {
            selectedMode = id == dual.getId() ? ProbePolicy.DUAL : ProbePolicy.SINGLE;
        });
        column.addView(modes);
        state = new TextView(this); state.setTextIsSelectable(true); column.addView(state);
        start = new Button(this); start.setText("Start 5-minute test / 启动 5 分钟测试"); column.addView(start);
        start.setOnClickListener(view -> confirmStart());
        Button stop = new Button(this); stop.setText("Stop all interfaces / 停止并移除全部接口"); column.addView(stop);
        stop.setOnClickListener(view -> {
            cancelPendingStart();
            ProbeVpnService.GATE.cancel();
            requestStop();
        });
        setContentView(scroll);
    }
    private void updateControls() {
        boolean ready = !pending && !ProbeVpnService.running && !ProbeVpnService.recoveryRequired
                && !ProbeVpnService.GATE.hasPending();
        start.setEnabled(ready);
        single.setEnabled(ready);
        dual.setEnabled(ready);
    }
    private void confirmStart() {
        if (!resumed || pending || ProbeVpnService.running || ProbeVpnService.recoveryRequired
                || ProbeVpnService.GATE.hasPending()) return;
        pending = true;
        pendingMode = selectedMode;
        updateControls();
        warning = new AlertDialog.Builder(this).setTitle("临时 VPN 网络实验")
                .setMessage("本次模式 " + ProbePolicy.modeName(pendingMode) + "：" + ProbePolicy.healthUrl(ProbePolicy.DEFAULT_PORT)
                        + "\n" + (pendingMode == ProbePolicy.DUAL ? "先建立主地址，再建立 192.168.247.2/32；第一个接口可能被停用。" : "只建立主地址。")
                        + "\n\n两种模式均仅设置各自 /32 主机路由、allowBypass 和本应用白名单。可能替换现有 VPN 或短暂影响网络，请先停止其他 VPN。最多 5 分钟，启动超时或离开、锁屏即停止。Stop 关闭全部描述符，不保留接口。继续后如出现 Android 本地网络或 VPN 授权，请由你本人确认。")
                .setNegativeButton("取消", (dialog, which) -> cancelPendingStart())
                .setPositiveButton("我了解，启动本次测试", (dialog, which) -> prepareVpn())
                .setOnCancelListener(dialog -> cancelPendingStart()).show();
    }
    private void prepareVpn() {
        if (!pending) return;
        if (!resumed) {
            prepareWhenResumed = true;
            return;
        }
        prepareWhenResumed = false;
        try {
            if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
                awaitingSystemPrompt = true;
                requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"}, 11);
                return;
            }
            ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    cancelPendingStart();
                    ProbeVpnService.status = "Stopped / 已停止: EXISTING_VPN_STOP_IT_FIRST";
                    return;
                }
            }
            Intent consent = VpnService.prepare(this);
            if (consent != null) {
                awaitingSystemPrompt = true;
                startActivityForResult(consent, 10);
            }
            else begin();
        } catch (RuntimeException error) {
            cancelPendingStart();
            ProbeVpnService.status = "Stopped / 已停止: CONSENT_UNAVAILABLE";
        }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 11 || !pending) return;
        awaitingSystemPrompt = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) prepareVpn();
        else { cancelPendingStart(); ProbeVpnService.status = "Stopped / 已停止: LOCAL_NETWORK_PERMISSION_DENIED"; }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 10 || !pending) return;
        awaitingSystemPrompt = false;
        if (result == RESULT_OK) begin();
        else { cancelPendingStart(); ProbeVpnService.status = "Stopped / 已停止: CONSENT_CANCELLED"; }
    }
    private void begin() {
        if (!pending) return;
        if (!resumed) {
            startWhenResumed = true;
            return;
        }
        int mode = pendingMode;
        cancelPendingStart();
        if (ProbeVpnService.running || ProbeVpnService.recoveryRequired) return;
        long grant = ProbeVpnService.GATE.arm();
        try { startForegroundService(new Intent(this, ProbeVpnService.class).setAction(ProbeVpnService.START)
                .putExtra("grant", grant).putExtra(ProbeVpnService.MODE_EXTRA, mode)); }
        catch (RuntimeException failure) {
            ProbeVpnService.GATE.cancel();
            ProbeVpnService.status = "Stopped / 已停止: SERVICE_START_DENIED";
        }
    }
    private void cancelPendingStart() {
        pending = false;
        pendingMode = 0;
        awaitingSystemPrompt = false;
        prepareWhenResumed = false;
        startWhenResumed = false;
        if (warning != null) warning.dismiss();
    }
    private void requestStop() {
        // GATE.cancel() already invalidates START commands not yet delivered. Avoid
        // creating an empty service, or a delayed STOP, while a consent prompt is open.
        if (!ProbeVpnService.running) return;
        try {
            // Android keeps an established VpnService bound. stopService alone need not
            // reach onDestroy, so deliver cancellation before stopping the service.
            startService(new Intent(this, ProbeVpnService.class).setAction(ProbeVpnService.STOP));
        } catch (RuntimeException failure) {
            ProbeVpnService.lastError = "STOP_REQUEST_FAILED";
            ProbeVpnService.status = "Stop request failed; verify VPN disconnected in Android settings / 停止请求失败，请在 Android 设置中确认 VPN 已断开";
            // Best effort only: a bound VPN may stay alive. Keep the failure visible.
            try { stopService(new Intent(this, ProbeVpnService.class)); }
            catch (RuntimeException ignored) { }
        }
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        isForeground = true;
        main.post(refresh);
        if (pending && !awaitingSystemPrompt) {
            if (prepareWhenResumed) prepareVpn();
            else if (startWhenResumed) begin();
        }
    }
    @Override protected void onPause() {
        resumed = false;
        isForeground = false;
        main.removeCallbacks(refresh);
        // Keep the experiment observable. Backgrounding/locking cannot leave a sleeping VPN alive.
        // The system permission/consent prompt is an expected pause; its result is
        // only allowed to continue startup after this Activity resumes.
        if (!awaitingSystemPrompt) cancelPendingStart();
        ProbeVpnService.GATE.cancel();
        requestStop();
        super.onPause();
    }
    @Override protected void onDestroy() {
        resumed = false;
        isForeground = false;
        cancelPendingStart();
        main.removeCallbacks(refresh);
        super.onDestroy();
    }
}
