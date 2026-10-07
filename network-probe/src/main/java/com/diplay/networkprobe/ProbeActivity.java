package com.diplay.networkprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.net.Uri;
import android.content.ContentResolver;
import java.util.Arrays;
import java.util.List;
import java.io.IOException;
import java.io.InputStream;
import java.io.Closeable;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
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
    private RadioButton plain, single, dual, http, https;
    private Button importButton, forgetButton, cancelImportButton, scanButton;
    private HotspotAddress selectedHotspot, pendingHotspot;
    private boolean scanning, scanCancelled;
    private String hotspotStatus = "请打开手机热点，读取并确认实际热点网关";
    private AlertDialog hotspotChooser;
    private ProbeTlsIdentity identity;
    private ProbeSession importTask;
    private ProbeTlsIdentity importCandidate;
    private String importStatus = "未导入 / NOT_IMPORTED";
    private Uri chainUri;
    private boolean selectingFile, openKeyWhenResumed, activityDestroyed;
    private int importPickerRequest;
    private int selectedPort = ProbePolicy.HTTPS_PORT, pendingPort;
    private final Runnable importTimeout = () -> cancelImport("IMPORT_TIMEOUT");
    private int selectedMode = ProbePolicy.HOTSPOT;
    private int pendingMode;
    private AlertDialog warning;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean ordinary = selectedMode == ProbePolicy.HOTSPOT;
            state.setText((ordinary ? PlainHotspotProbe.status : ProbeVpnService.status)
                    + "\nNext mode / 下次模式: " + ProbePolicy.modeName(selectedMode)
                    + "\nURL: " + selectedUrl()
                    + "\nHotspot / 热点: " + hotspotStatus
                    + "\nTLS import / 证书: " + importStatus
                    + (identity != null && !identity.supportsHostname(selectedHostname()) ? "\n当前证书不覆盖所选域名，请重新导入" : "")
                    + "\nSession / 最近一次测试: " + (ordinary ? PlainHotspotProbe.sessionUrl :
                        ProbeVpnService.sessionMode == 0 ? "NOT_RUN" : ProbePolicy.modeName(ProbeVpnService.sessionMode))
                    + (ordinary ? "" : "\nInterface snapshots / 接口快照:\n" + ProbeVpnService.interfaceReport)
                    + "\nLocal self-check: " + (ordinary ? PlainHotspotProbe.selfCheck : ProbeVpnService.selfCheck)
                    + " (local only; not hotspot reachability)"
                    + "\nExternal TCP accepts: " + (ordinary ? PlainHotspotProbe.accepted : ProbeVpnService.accepted)
                    + "\nExternal health responses written: " + (ordinary ? PlainHotspotProbe.health : ProbeVpnService.health)
                    + "\nLast result: " + (ordinary ? PlainHotspotProbe.lastError : ProbeVpnService.lastError)
                    + "\nRemaining seconds: " + Math.max(0, ((ordinary ? PlainHotspotProbe.endsAt : ProbeVpnService.endsAt) - SystemClock.elapsedRealtime()) / 1000));
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
        title.setText("DiPlay Network Probe · local HTTPS v5\n\n独立 HTTPS/HTTP 实验，打开不启动。每次最多 5 分钟，启动最多 15 秒。保持本页前台并亮屏；离开、锁屏、旋转均停止。\n\n默认普通热点模式，不创建 VPN。读取并确认本机实际热点网关后，HTTPS 使用 https://test.mark4z.asia:9999/health，HTTP 对照为该网关的 :18080/health。VPN 单/双 TUN 模式另用 tesla.mark4z.asia:9999，绑定 100.99.9.9。每次只运行所选协议，不自动回退。所选域名 DNS 必须由外部浏览器正常解析到当前所选模式的监听 IP，本应用不改 DNS。普通模式用 test.mark4z.asia，VPN 模式用 tesla.mark4z.asia；证书必须覆盖对应域名。\n\n先在手机文件选择器依次选 PEM fullchain（叶证书在前）和私钥。支持 RSA PRIVATE KEY / PRIVATE KEY；不导入 ZIP，不支持加密私钥。仅内存使用，不备份、导出、写盘或持久保存文件权限。取消或失败保留当前证书；离开本页或进程结束即清除，需要重新导入。文件选择与系统 VPN 授权期间暂时保留。\n\n证书域名、有效期、用途、证书链与私钥匹配均在手机检查。HTTPS 自检使用正常系统信任与域名校验，拒绝绕过证书警告。此前公开过的私钥应先撤销并换新后再用于本实验。\n\n先自行停止其他 VPN，包括 TeslaMirror 可能保留的接口。单模式建立 100.99.9.9/32；双模式随后建立 192.168.247.2/32。地址冲突则拒绝启动；不会清除其他应用接口。仅各自 /32 主机路由、allowBypass 和本应用白名单，无默认路由、无 DNS 设置、无转发。旧接口可能被 Android 停用。\n\n所有描述符只保留到本次测试结束；Stop 全部关闭。清理未确认时禁止重启。勿开启始终开启 VPN；勿在驾驶或不可信网络中测试。先用另一台手机连接热点测试，再在停车时使用车机。\n");
        column.addView(title);
        RadioGroup modes = new RadioGroup(this);
        plain = new RadioButton(this); plain.setId(View.generateViewId());
        plain.setText("HOTSPOT_NO_VPN · 普通热点，不创建 VPN（默认）"); modes.addView(plain);
        single = new RadioButton(this); single.setId(View.generateViewId());
        single.setText("SINGLE · 单 TUN 对照"); modes.addView(single);
        dual = new RadioButton(this); dual.setId(View.generateViewId());
        dual.setText("DUAL_HANDOVER · 双 TUN 顺序建立实验"); modes.addView(dual);
        modes.check(plain.getId());
        modes.setOnCheckedChangeListener((group, id) -> {
            selectedMode = id == plain.getId() ? ProbePolicy.HOTSPOT : id == dual.getId() ? ProbePolicy.DUAL : ProbePolicy.SINGLE;
        });
        column.addView(modes);
        scanButton = new Button(this); scanButton.setText("Read hotspot gateway / 读取并选择热点网关");
        scanButton.setOnClickListener(view -> scanHotspot()); column.addView(scanButton);
        RadioGroup transports = new RadioGroup(this);
        https = new RadioButton(this); https.setId(View.generateViewId());
        https.setText("HTTPS :9999 · 需先导入证书（默认）"); transports.addView(https);
        http = new RadioButton(this); http.setId(View.generateViewId());
        http.setText("HTTP :18080 · 明文对照"); transports.addView(http);
        transports.check(https.getId());
        transports.setOnCheckedChangeListener((group, id) -> selectedPort = id == http.getId()
                ? ProbePolicy.DEFAULT_PORT : ProbePolicy.HTTPS_PORT);
        column.addView(transports);
        importButton = new Button(this); importButton.setText("Import fullchain + key / 导入证书链和私钥");
        importButton.setOnClickListener(view -> chooseChain()); column.addView(importButton);
        cancelImportButton = new Button(this); cancelImportButton.setText("Cancel import / 取消导入并保留原证书");
        cancelImportButton.setOnClickListener(view -> cancelImport("IMPORT_CANCELLED")); column.addView(cancelImportButton);
        forgetButton = new Button(this); forgetButton.setText("Forget certificate / 清除内存证书");
        forgetButton.setOnClickListener(view -> forgetIdentity()); column.addView(forgetButton);
        state = new TextView(this); state.setTextIsSelectable(true); column.addView(state);
        start = new Button(this); start.setText("Start 5-minute test / 启动 5 分钟测试"); column.addView(start);
        start.setOnClickListener(view -> confirmStart());
        Button stop = new Button(this); stop.setText("Stop all interfaces / 停止并移除全部接口"); column.addView(stop);
        stop.setOnClickListener(view -> {
            cancelPendingStart();
            ProbeVpnService.cancelStart();
            requestStop();
        });
        setContentView(scroll);
    }
    private void updateControls() {
        boolean ready = !scanning && !selectingFile && importTask == null && !pending && !ProbeVpnService.running && !ProbeVpnService.recoveryRequired
                && !ProbeVpnService.GATE.hasPending() && !PlainHotspotProbe.running && !PlainHotspotProbe.recoveryRequired;
        start.setEnabled(ready && (selectedMode != ProbePolicy.HOTSPOT || selectedHotspot != null)
                && (selectedPort != ProbePolicy.HTTPS_PORT || identity != null && identity.supportsHostname(selectedHostname())));
        plain.setEnabled(ready);
        scanButton.setEnabled(ready && selectedMode == ProbePolicy.HOTSPOT);
        http.setEnabled(ready); https.setEnabled(ready);
        importButton.setEnabled(ready); forgetButton.setEnabled(ready && identity != null);
        cancelImportButton.setEnabled(importTask != null || selectingFile);
        single.setEnabled(ready);
        dual.setEnabled(ready);
    }
    private void confirmStart() {
        if (!resumed || scanning || selectingFile || importTask != null || pending || PlainHotspotProbe.running || PlainHotspotProbe.recoveryRequired || ProbeVpnService.running || ProbeVpnService.recoveryRequired
                || ProbeVpnService.GATE.hasPending()) return;
        if (selectedPort == ProbePolicy.HTTPS_PORT && (identity == null || !identity.supportsHostname(selectedHostname()))) return;
        if (selectedMode == ProbePolicy.HOTSPOT && selectedHotspot == null) return;
        pending = true;
        pendingMode = selectedMode;
        pendingPort = selectedPort;
        pendingHotspot = selectedHotspot;
        updateControls();
        boolean ordinary = pendingMode == ProbePolicy.HOTSPOT;
        String detail = ordinary ?
                "不创建或调用 VPN，不改变 DNS/路由。仅监听已选热点地址 " + pendingHotspot.label()
                + "。请确认它与连接热点的另一台手机所见网关完全一致；不要选择上游 Wi-Fi 或蜂窝地址。\n"
                + "HTTPS 请将 test.mark4z.asia 的 DNS 由你本人设置为此当前网关；地址变化后须重查。证书须覆盖 test.mark4z.asia。"
                : "两种 VPN 模式均仅设置各自 /32 主机路由、allowBypass 和本应用白名单。可能替换现有 VPN，先自行停止其他 VPN。"
                + (pendingMode == ProbePolicy.DUAL ? "先建立主地址，再建立 192.168.247.2/32；第一个接口可能被停用。" : "只建立主地址。");
        warning = new AlertDialog.Builder(this).setTitle(ordinary ? "普通热点 HTTPS/HTTP 实验" : "临时 VPN 网络实验")
                .setMessage("本次模式 " + ProbePolicy.modeName(pendingMode) + "：" + selectedUrl()
                        + "\n\n" + detail
                        + "\n\n最多 5 分钟，启动超时或离开、锁屏即停止。Stop 关闭全部本次资源。若出现 Android 本地网络权限，请本人确认。仅 VPN 模式可能请求 VPN 授权。不要绕过证书警告。")
                .setNegativeButton("取消", (dialog, which) -> cancelPendingStart())
                .setPositiveButton("已核对，启动本次测试", (dialog, which) -> prepareVpn())
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
            // Ordinary mode never invokes VpnService.prepare or starts the VPN service.
            if (pendingMode == ProbePolicy.HOTSPOT) { begin(); return; }
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
            reportStartFailure("CONSENT_UNAVAILABLE");
        }
    }
    private void reportStartFailure(String code) {
        boolean ordinary = pendingMode == ProbePolicy.HOTSPOT;
        cancelPendingStart();
        if (ordinary) { PlainHotspotProbe.status = "Stopped / 已停止: " + code; PlainHotspotProbe.lastError = code; }
        else { ProbeVpnService.status = "Stopped / 已停止: " + code; ProbeVpnService.lastError = code; }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 11 || !pending) return;
        awaitingSystemPrompt = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) prepareVpn();
        else reportStartFailure("LOCAL_NETWORK_PERMISSION_DENIED");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 20 || request == 21) {
            if (!selectingFile || request != importPickerRequest) return;
            if (result != RESULT_OK || data == null || data.getData() == null) {
                cancelImport("IMPORT_CANCELLED"); return;
            }
            Uri selected = data.getData();
            if (!"content".equals(selected.getScheme())) { cancelImport("IMPORT_CONTENT_URI_REQUIRED"); return; }
            if (request == 20) {
                chainUri = selected;
                openKeyWhenResumed = true;
                if (resumed) chooseKey();
            } else {
                selectingFile = false;
                importPickerRequest = 0;
                beginImport(chainUri, selected);
                chainUri = null;
            }
            return;
        }
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
        int port = pendingPort;
        HotspotAddress hotspot = pendingHotspot;
        cancelPendingStart();
        if (ProbeVpnService.running || ProbeVpnService.recoveryRequired || PlainHotspotProbe.running || PlainHotspotProbe.recoveryRequired) return;
        if (mode == ProbePolicy.HOTSPOT) {
            if (hotspot != null) PlainHotspotProbe.start(this, hotspot, port, port == ProbePolicy.HTTPS_PORT ? identity : null);
            return;
        }
        long grant = ProbeVpnService.armStart(port == ProbePolicy.HTTPS_PORT ? identity : null);
        try { startForegroundService(new Intent(this, ProbeVpnService.class).setAction(ProbeVpnService.START)
                .putExtra("grant", grant).putExtra(ProbeVpnService.MODE_EXTRA, mode).putExtra(ProbeVpnService.PORT_EXTRA, port)); }
        catch (RuntimeException failure) {
            ProbeVpnService.cancelStart();
            ProbeVpnService.status = "Stopped / 已停止: SERVICE_START_DENIED";
        }
    }
    private void cancelPendingStart() {
        pending = false;
        pendingMode = 0;
        pendingPort = 0;
        pendingHotspot = null;
        awaitingSystemPrompt = false;
        prepareWhenResumed = false;
        startWhenResumed = false;
        if (warning != null) warning.dismiss();
    }
    private String selectedHostname() {
        return selectedMode == ProbePolicy.HOTSPOT ? ProbePolicy.HOTSPOT_HOSTNAME : ProbePolicy.HOSTNAME;
    }
    private String selectedUrl() {
        if (selectedMode != ProbePolicy.HOTSPOT) return ProbePolicy.healthUrl(selectedPort);
        if (selectedPort == ProbePolicy.HTTPS_PORT) return "https://" + ProbePolicy.HOTSPOT_HOSTNAME + ":9999/health";
        return selectedHotspot == null ? "先选择热点网关 / SELECT_HOTSPOT_GATEWAY" : selectedHotspot.url(selectedPort);
    }
    private void scanHotspot() {
        if (!resumed || scanning || selectingFile || importTask != null || pending || ProbeVpnService.running || PlainHotspotProbe.running) return;
        scanning = true;
        scanCancelled = false;
        hotspotStatus = "READING_INTERFACES / 正在读取本机接口";
        updateControls();
        background(() -> {
            try {
                List<HotspotAddress> choices = HotspotAddress.discover(getApplicationContext());
                main.post(() -> {
                    scanning = false;
                    if (activityDestroyed || !resumed || scanCancelled) return;
                    selectedHotspot = null;
                    if (choices.isEmpty()) { hotspotStatus = "NO_SAFE_HOTSPOT_CANDIDATE / 未发现可确认的热点接口"; return; }
                    String[] labels = new String[choices.size()];
                    for (int i = 0; i < choices.size(); i++) labels[i] = choices.get(i).label();
                    hotspotChooser = new AlertDialog.Builder(this).setTitle("选择与另一台手机所见热点网关一致的地址")
                            .setItems(labels, (dialog, which) -> {
                                selectedHotspot = choices.get(which);
                                hotspotStatus = selectedHotspot.label() + "；启动前会再次核验";
                            }).setNegativeButton("取消", null).show();
                });
            } catch (IOException | RuntimeException failure) {
                main.post(() -> {
                    scanning = false;
                    if (!activityDestroyed && resumed && !scanCancelled) {
                        selectedHotspot = null;
                        hotspotStatus = "HOTSPOT_DISCOVERY_FAILED / 请打开热点并停止其他 VPN，再重试";
                    }
                });
            }
        }, "hotspot-address-read");
    }
    private void chooseChain() {
        if (!resumed || scanning || pending || importTask != null || selectingFile || PlainHotspotProbe.running || ProbeVpnService.running) return;
        selectingFile = true;
        importStatus = "请选择 PEM fullchain（随后选择私钥）；取消保留原证书";
        launchPicker(20);
        updateControls();
    }
    private void chooseKey() {
        openKeyWhenResumed = false;
        if (!selectingFile || chainUri == null || activityDestroyed) return;
        importStatus = "请选择对应私钥；仅内存使用，取消保留原证书";
        launchPicker(21);
    }
    private void launchPicker(int request) {
        importPickerRequest = request;
        try {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            startActivityForResult(picker, request);
        } catch (RuntimeException failure) { cancelImport("FILE_PICKER_UNAVAILABLE"); }
    }
    private void beginImport(Uri chain, Uri key) {
        if (chain == null || importTask != null || activityDestroyed) { cancelImport("IMPORT_CANCELLED"); return; }
        ContentResolver resolver = getApplicationContext().getContentResolver();
        final String expectedHostname = selectedHostname();
        importStatus = "IMPORT_VALIDATING / 正在本机校验";
        importCandidate = null;
        // Completion waits for the worker and all stream closes; repeated clicks cannot stack tasks.
        ProbeSession task = new ProbeSession(action -> background(action, "tls-import-close"),
                () -> main.post(this::finishImport));
        importTask = task;
        main.postDelayed(importTimeout, 15_000);
        background(() -> {
            byte[] chainBytes = null, keyBytes = null;
            try {
                if (task.isCancelled()) return;
                chainBytes = readDocument(resolver, chain, 64 * 1024, task);
                if (task.isCancelled()) return;
                keyBytes = readDocument(resolver, key, 16 * 1024, task);
                if (task.isCancelled()) return;
                ProbeTlsIdentity candidate = ProbeTlsIdentity.read(chainBytes, keyBytes, expectedHostname);
                main.post(() -> {
                    if (importTask == task && !task.isCancelled() && !activityDestroyed) {
                        importCandidate = candidate;
                        importStatus = "IMPORT_VALIDATED";
                    }
                });
            } catch (ProbeTlsIdentity.Failure failure) {
                postImportFailure(task, failure.code);
            } catch (IOException | RuntimeException failure) {
                postImportFailure(task, "IMPORT_READ_FAILED_OR_TOO_LARGE");
            } finally {
                if (chainBytes != null) Arrays.fill(chainBytes, (byte) 0);
                if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
                // Publish/cancel on the main queue after the validation result, in queue order.
                main.post(() -> {
                    if (importTask == task) task.cancel();
                    task.workerFinished();
                });
            }
        }, "tls-import-validation");
    }
    private static byte[] readDocument(ContentResolver resolver, Uri uri, int limit, ProbeSession task)
            throws IOException {
        CancellationSignal signal = new CancellationSignal();
        Closeable cancelOpen = signal::cancel;
        // Cancellation is owned before invoking a potentially slow DocumentsProvider.
        if (!task.own(cancelOpen)) throw new IOException("IMPORT_CANCELLED");
        ParcelFileDescriptor descriptor = null;
        try {
            if (task.isCancelled()) throw new IOException("IMPORT_CANCELLED");
            descriptor = resolver.openFileDescriptor(uri, "r", signal);
            if (descriptor == null) throw new IOException("IMPORT_READ_FAILED");
            InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            descriptor = null; // The stream owns this exact descriptor; no duplication.
            return ProbeImportIO.read(stream, limit, task);
        } finally {
            if (descriptor != null && task.own(descriptor)) task.closeAsync(descriptor);
            task.closeAsync(cancelOpen);
        }
    }
    private void postImportFailure(ProbeSession task, String code) {
        main.post(() -> { if (importTask == task && !task.isCancelled()) importStatus = code; });
    }
    private void finishImport() {
        main.removeCallbacks(importTimeout);
        ProbeSession finished = importTask;
        importTask = null;
        if (!activityDestroyed && finished != null && !finished.didCloseFail() && importCandidate != null) {
            identity = importCandidate; // Atomic replacement only after all validation and closes succeeded.
            importStatus = "READY / 已导入: " + identity.description();
        } else if (finished != null && finished.didCloseFail()) importStatus = "IMPORT_CLOSE_FAILED";
        if (importCandidate == null && identity != null) importStatus += "; PREVIOUS_CERTIFICATE_PRESERVED";
        importCandidate = null;
        if (!activityDestroyed) updateControls();
    }
    private void cancelImport(String code) {
        selectingFile = false;
        openKeyWhenResumed = false;
        importPickerRequest = 0;
        chainUri = null;
        importCandidate = null;
        if (importTask != null) importTask.cancel();
        importStatus = code + (identity == null ? "; NO_CERTIFICATE" : "; PREVIOUS_CERTIFICATE_PRESERVED");
    }
    private void forgetIdentity() {
        identity = null;
        importCandidate = null;
        importStatus = "未导入 / NOT_IMPORTED";
    }
    private static void background(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
    }
    private void requestStop() {
        PlainHotspotProbe.stop("USER_OR_ACTIVITY_STOP");
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
        if (openKeyWhenResumed) chooseKey();
        if (pending && !awaitingSystemPrompt) {
            if (prepareWhenResumed) prepareVpn();
            else if (startWhenResumed) begin();
        }
    }
    @Override protected void onPause() {
        resumed = false;
        isForeground = false;
        main.removeCallbacks(refresh);
        scanCancelled = true;
        if (hotspotChooser != null) hotspotChooser.dismiss();
        // Keep the experiment observable. Backgrounding/locking cannot leave a sleeping VPN alive.
        // The system permission/consent prompt is an expected pause; its result is
        // only allowed to continue startup after this Activity resumes.
        if (!awaitingSystemPrompt) cancelPendingStart();
        ProbeVpnService.cancelStart();
        requestStop();
        if (!awaitingSystemPrompt && !selectingFile) {
            cancelImport("IMPORT_CANCELLED");
            forgetIdentity();
        }
        super.onPause();
    }
    @Override protected void onDestroy() {
        resumed = false;
        isForeground = false;
        cancelPendingStart();
        main.removeCallbacks(refresh);
        activityDestroyed = true;
        ProbeVpnService.cancelStart();
        requestStop();
        cancelImport("IMPORT_CANCELLED");
        forgetIdentity();
        super.onDestroy();
    }
}
