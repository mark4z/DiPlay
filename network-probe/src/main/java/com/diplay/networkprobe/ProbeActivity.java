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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class ProbeActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean pending;
    private TextView state;
    private Button start;
    private AlertDialog warning;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            state.setText(ProbeVpnService.status + "\nURL: http://" + ProbePolicy.ADDRESS + ":" + ProbePolicy.PORT
                    + "/health\nTCP accepts: " + ProbeVpnService.accepted + "\nHTTP health responses written: " + ProbeVpnService.health
                    + "\nLast result: " + ProbeVpnService.lastError
                    + "\nRemaining seconds: " + Math.max(0, (ProbeVpnService.endsAt - SystemClock.elapsedRealtime()) / 1000));
            start.setEnabled(!pending && !ProbeVpnService.running);
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
        title.setText("DiPlay Network Probe · health-only v1\n\n独立网络实验，不需要身份文件或域名。默认关闭。每次启动最多 5 分钟。请保持本页在前台并亮屏；切换应用、锁屏或旋转将停止。\n\n使用 Android VPN 创建临时单个虚拟地址，可能替换现有 VPN 或影响网络。请先自行停止其他 VPN，包括 DiPlay 有线 VPN；不要同时运行。\n\n不设默认路由、不改 DNS、不转发音视频、不读取 TUN 数据。仅在虚拟地址提供公开、无身份信息的 HTTP /health。不要在不可信网络上测试。\n\n先让另一台手机连接本机热点，手动输入下方完整 http:// 地址，再测试车机。监听成功不代表热点/车机可达；HTTP 成功也不代表 TLS 或 CarPlay 可用。\n");
        column.addView(title);
        state = new TextView(this); state.setTextIsSelectable(true); column.addView(state);
        start = new Button(this); start.setText("Start 5-minute test / 启动 5 分钟测试"); column.addView(start);
        start.setOnClickListener(view -> confirmStart());
        Button stop = new Button(this); stop.setText("Stop and remove address / 停止并移除地址"); column.addView(stop);
        stop.setOnClickListener(view -> {
            pending = false;
            ProbeVpnService.GATE.cancel();
            if (warning != null) warning.dismiss();
            stopService(new Intent(this, ProbeVpnService.class));
        });
        setContentView(scroll);
    }
    private void confirmStart() {
        if (pending || ProbeVpnService.running) return;
        pending = true;
        warning = new AlertDialog.Builder(this).setTitle("临时 VPN 网络实验")
                .setMessage("本次创建临时 VPN 地址，可能替换已有 VPN 并短暂影响网络。请停止 DiPlay 有线连接及其他 VPN。5 分钟后自动停止，也可随时点停止。请保持本页前台亮屏，离开或锁屏就停止。Android 17 还需允许本地网络访问。继续后如出现 Android VPN 授权，请由你本人确认。")
                .setNegativeButton("取消", (dialog, which) -> pending = false)
                .setPositiveButton("我了解，启动本次测试", (dialog, which) -> prepareVpn())
                .setOnCancelListener(dialog -> pending = false).show();
    }
    private void prepareVpn() {
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"}, 11);
            return;
        }
        try {
            ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    pending = false;
                    ProbeVpnService.status = "Stopped / 已停止: EXISTING_VPN_STOP_IT_FIRST";
                    return;
                }
            }
            Intent consent = VpnService.prepare(this);
            if (consent != null) startActivityForResult(consent, 10);
            else begin();
        } catch (RuntimeException error) {
            pending = false;
            ProbeVpnService.status = "Stopped / 已停止: CONSENT_UNAVAILABLE";
        }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 11 || !pending) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) prepareVpn();
        else { pending = false; ProbeVpnService.status = "Stopped / 已停止: LOCAL_NETWORK_PERMISSION_DENIED"; }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 10 || !pending) return;
        if (result == RESULT_OK) begin();
        else { pending = false; ProbeVpnService.status = "Stopped / 已停止: CONSENT_CANCELLED"; }
    }
    private void begin() {
        if (!pending) return;
        pending = false;
        long grant = ProbeVpnService.GATE.arm();
        try { startForegroundService(new Intent(this, ProbeVpnService.class).setAction(ProbeVpnService.START).putExtra("grant", grant)); }
        catch (RuntimeException failure) {
            ProbeVpnService.GATE.cancel();
            ProbeVpnService.status = "Stopped / 已停止: SERVICE_START_DENIED";
        }
    }
    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() {
        main.removeCallbacks(refresh);
        // Keep the experiment observable. Backgrounding/locking cannot leave a sleeping VPN alive.
        ProbeVpnService.GATE.cancel();
        stopService(new Intent(this, ProbeVpnService.class));
        super.onPause();
    }
    @Override protected void onDestroy() {
        pending = false;
        if (warning != null) warning.dismiss();
        main.removeCallbacks(refresh);
        super.onDestroy();
    }
}
