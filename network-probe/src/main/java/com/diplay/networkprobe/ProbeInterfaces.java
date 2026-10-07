package com.diplay.networkprobe;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.HashMap;
import java.util.Map;

/** Only observes our test addresses and remembered interface identities; never changes them. */
final class ProbeInterfaces {
    interface Lookup {
        Info byAddress(String address) throws Exception;
        Info byName(String name) throws Exception;
    }
    static final class Info {
        final String name;
        final int index;
        final boolean up;
        Info(String name, int index, boolean up) { this.name = name; this.index = index; this.up = up; }
    }
    static final class Snapshot {
        final String text;
        final boolean visible, failed;
        Snapshot(String text, boolean visible, boolean failed) {
            this.text = text; this.visible = visible; this.failed = failed;
        }
    }
    private final int mode;
    private final Lookup lookup;
    private final Map<String, Info> identities = new HashMap<>();

    ProbeInterfaces(int mode) {
        this(mode, new Lookup() {
            private Info read(NetworkInterface iface) throws Exception {
                return iface == null ? null : new Info(iface.getName(), iface.getIndex(), iface.isUp());
            }
            @Override public Info byAddress(String address) throws Exception {
                return read(NetworkInterface.getByInetAddress(InetAddress.getByName(address)));
            }
            @Override public Info byName(String name) throws Exception { return read(NetworkInterface.getByName(name)); }
        });
    }
    ProbeInterfaces(int mode, Lookup lookup) { this.mode = mode; this.lookup = lookup; }

    // Startup worker, then final cleanup worker only after startup has exited. Never main-thread I/O.
    Snapshot snapshot() {
        StringBuilder result = new StringBuilder();
        boolean visible = false, failed = false;
        for (String address : ProbePolicy.addresses(mode)) {
            if (result.length() != 0) result.append("\n");
            result.append(address).append("/32: ");
            try {
                Info byAddress = lookup.byAddress(address);
                Info original = identities.get(address);
                if (original == null && byAddress != null) {
                    original = byAddress;
                    identities.put(address, original);
                }
                Info iface = original == null ? byAddress : lookup.byName(original.name);
                visible |= byAddress != null || iface != null;
                if (iface == null) result.append("NOT_VISIBLE");
                else if (original != null && iface.index != original.index) result.append("IDENTITY_CHANGED");
                else result.append(iface.name).append(" index=").append(iface.index)
                        .append(iface.up ? " UP" : " DOWN")
                        .append(byAddress != null && byAddress.index == iface.index
                                ? " ADDRESS_PRESENT" : " ADDRESS_NOT_VISIBLE");
                if (original != null) result.append(" (original=").append(original.name)
                        .append("#").append(original.index).append(")");
            } catch (Exception failure) {
                failed = true;
                result.append("READ_FAILED (").append(failure.getClass().getSimpleName()).append(")");
            }
        }
        return new Snapshot(result.toString(), visible, failed);
    }
}
