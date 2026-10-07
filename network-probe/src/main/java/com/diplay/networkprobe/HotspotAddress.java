package com.diplay.networkprobe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only interface inspection. Device inspections always run on an I/O worker. */
final class HotspotAddress {
    final String interfaceName;
    final int interfaceIndex, prefix;
    final InetAddress address;

    /** Fixed safe codes only; platform exception messages are never shown. */
    static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }

    HotspotAddress(String name, int index, InetAddress address, int prefix) {
        this.interfaceName = name;
        this.interfaceIndex = index;
        this.address = address;
        this.prefix = prefix;
    }

    String label() { return address.getHostAddress() + " /" + prefix + " · " + interfaceName; }
    String url(int port) { return HotspotPolicy.healthUrl(address, port); }

    boolean same(HotspotAddress other) {
        return other != null && interfaceIndex == other.interfaceIndex
                && interfaceName != null && interfaceName.equals(other.interfaceName)
                && prefix == other.prefix && address != null && address.equals(other.address);
    }

    static List<HotspotAddress> discover(Context context) throws IOException {
        try { return inspect(context); }
        catch (Failure failure) { throw failure; }
        catch (SecurityException failure) { throw new Failure("NETWORK_STATE_PERMISSION_DENIED"); }
        catch (IOException | RuntimeException failure) { throw new Failure("HOTSPOT_INSPECTION_FAILED"); }
    }

    private static List<HotspotAddress> inspect(Context context) throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) throw new Failure("NETWORK_STATE_UNAVAILABLE");
        Set<String> managedNames = new HashSet<>();
        Set<InetAddress> managedAddresses = new HashSet<>();
        Network[] networks = manager.getAllNetworks();
        if (networks == null) throw new Failure("NETWORK_STATE_UNAVAILABLE");
        for (Network network : networks) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            LinkProperties properties = manager.getLinkProperties(network);
            if (caps == null || properties == null) throw new Failure("NETWORK_STATE_CHANGED_RETRY");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                throw new Failure("EXISTING_VPN_STOP_IT_FIRST");
            String managedName = properties.getInterfaceName();
            if (managedName == null || managedName.isEmpty()) throw new Failure("NETWORK_STATE_CHANGED_RETRY");
            managedNames.add(managedName);
            for (LinkAddress link : properties.getLinkAddresses()) managedAddresses.add(link.getAddress());
        }
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) throw new Failure("INTERFACES_UNAVAILABLE");
        List<HotspotAddress> candidates = new ArrayList<>();
        for (NetworkInterface iface : Collections.list(interfaces)) {
            if (!HotspotPolicy.isWifiApName(iface.getName()) || iface.getIndex() <= 0) continue;
            boolean managed = managedNames.contains(iface.getName());
            // Exclude an entire interface if ANY address belongs to a managed Android network.
            for (InetAddress ip : Collections.list(iface.getInetAddresses())) managed |= managedAddresses.contains(ip);
            for (InterfaceAddress link : iface.getInterfaceAddresses()) {
                if (link.getAddress() != null && HotspotPolicy.eligible(iface.getName(), link.getAddress().getAddress(),
                        link.getNetworkPrefixLength(), iface.isUp(), iface.isLoopback(), iface.isPointToPoint(),
                        iface.isVirtual(), link.getBroadcast() != null, managed)) {
                    candidates.add(new HotspotAddress(iface.getName(), iface.getIndex(), link.getAddress(), link.getNetworkPrefixLength()));
                }
            }
        }
        return candidates;
    }

    void verify(Context context) throws IOException {
        for (HotspotAddress candidate : discover(context)) if (same(candidate)) return;
        throw new Failure("HOTSPOT_ADDRESS_CHANGED_OR_UNAVAILABLE");
    }
}
