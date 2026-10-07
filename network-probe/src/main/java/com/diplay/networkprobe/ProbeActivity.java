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
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean pending, resumed, awaitingSystemPrompt, prepareWhenResumed, startWhenResumed;
    private TextView state;
    private Button start;
    private RadioButton port80, port18080;
    private int selectedPort = ProbePolicy.DEFAULT_PORT;
    private int pendingPort;
    private AlertDialog warning;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            state.setText(ProbeVpnService.status
                    + "\nNext test URL / 下次测试: " + ProbePolicy.healthUrl(selectedPort)
                    + "\nSession URL / 最近一次测试: " + (ProbeVpnService.sessionPort == 0 ? "NOT_RUN" : ProbePolicy.healthUrl(ProbeVpnService.sessionPort))
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
        title.setText("DiPlay Network Probe · health-only v3\n\n独立网络实验，不需要身份文件或域名。默认关闭。每次启动最多 5 分钟。请保持本页在前台并亮屏；切换应用、锁屏或旋转将停止。\n\n使用 Android VPN 创建临时单个虚拟地址，可能替换现有 VPN 或影响网络。请先自行停止其他 VPN，包括 DiPlay 有线 VPN；不要同时运行。\n\n不设默认路由、不改 DNS、不转发音视频、不读取 TUN 数据。仅在虚拟地址提供公开、无身份信息的 HTTP /health。不要在不可信网络上测试。\n\n本次仅监听所选的 80 或 18080 端口；失败不会切换端口。先停止并等待清理完成，再换端口重新确认。80 是否允许绑定，以本机结果为准；不需要 root，也不更改系统安全设置。\n\n启动后会对本机相同地址和端口做一次限时 HTTP 自检，计数只包含外部连接。自检成功只证明本机 HTTP 路径，不代表热点或车机可达。\n\n先让另一台手机连接本机热点，手动输入下方本次完整 http:// 地址，再测试车机。监听成功不代表热点/车机可达；HTTP 成功也不代表 TLS 或 CarPlay 可用。\n");
        column.addView(title);
        RadioGroup ports = new RadioGroup(this);
        port18080 = new RadioButton(this); port18080.setId(View.generateViewId());
        port18080.setText("18080 · " + ProbePolicy.healthUrl(ProbePolicy.DEFAULT_PORT)); ports.addView(port18080);
        port80 = new RadioButton(this); port80.setId(View.generateViewId());
        port80.setText("80 · " + ProbePolicy.healthUrl(ProbePolicy.HTTP_PORT)); ports.addView(port80);
        ports.check(port18080.getId());
        ports.setOnCheckedChangeListener((group, id) -> {
            selectedPort = id == port80.getId() ? ProbePolicy.HTTP_PORT : ProbePolicy.DEFAULT_PORT;
        });
        column.addView(ports);
        state = new TextView(this); state.setTextIsSelectable(true); column.addView(state);
        start = new Button(this); start.setText("Start 5-minute test / 启动 5 分钟测试"); column.addView(start);
        start.setOnClickListener(view -> confirmStart());
        Button stop = new Button(this); stop.setText("Stop and remove address / 停止并移除地址"); column.addView(stop);
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
        port80.setEnabled(ready);
        port18080.setEnabled(ready);
    }
    private void confirmStart() {
        if (!resumed || pending || ProbeVpnService.running || ProbeVpnService.recoveryRequired
                || ProbeVpnService.GATE.hasPending()) return;
        pending = true;
        pendingPort = selectedPort;
        updateControls();
        warning = new AlertDialog.Builder(this).setTitle("临时 VPN 网络实验")
                .setMessage("本次端口 " + pendingPort + "：" + ProbePolicy.healthUrl(pendingPort) + "。失败不会换端口。\n\n本次创建临时 VPN 地址，可能替换已有 VPN 并短暂影响网络。请停止 DiPlay 有线连接及其他 VPN。5 分钟后自动停止，也可随时点停止。请保持本页前台亮屏，离开或锁屏就停止。Android 17 还需允许本地网络访问。继续后如出现 Android VPN 授权，请由你本人确认。")
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
        int port = pendingPort;
        cancelPendingStart();
        if (ProbeVpnService.running || ProbeVpnService.recoveryRequired) return;
        long grant = ProbeVpnService.GATE.arm();
        try { startForegroundService(new Intent(this, ProbeVpnService.class).setAction(ProbeVpnService.START)
                .putExtra("grant", grant).putExtra(ProbeVpnService.PORT_EXTRA, port)); }
        catch (RuntimeException failure) {
            ProbeVpnService.GATE.cancel();
            ProbeVpnService.status = "Stopped / 已停止: SERVICE_START_DENIED";
        }
    }
    private void cancelPendingStart() {
        pending = false;
        pendingPort = 0;
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
        main.post(refresh);
        if (pending && !awaitingSystemPrompt) {
            if (prepareWhenResumed) prepareVpn();
            else if (startWhenResumed) begin();
        }
    }
    @Override protected void onPause() {
        resumed = false;
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
        cancelPendingStart();
        main.removeCallbacks(refresh);
        super.onDestroy();
    }
}
