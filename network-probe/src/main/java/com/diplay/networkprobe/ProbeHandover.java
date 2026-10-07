package com.diplay.networkprobe;

import java.io.Closeable;
import java.io.IOException;

/** Sequential public-API experiment. Own every returned descriptor before another operation. */
final class ProbeHandover {
    interface Establisher { Closeable establish(String address) throws Exception; }
    interface Observer { void observe(String stage); }

    static boolean establish(ProbeSession session, int mode, Establisher establisher,
            Observer observer) throws Exception {
        String[] addresses = ProbePolicy.addresses(mode);
        for (int i = 0; i < addresses.length; i++) {
            if (session.isCancelled()) return false;
            String label = i == 0 ? "PRIMARY" : "COMPATIBILITY";
            observer.observe(label + "_ESTABLISHING");
            if (session.isCancelled()) return false;
            Closeable descriptor = establisher.establish(addresses[i]);
            if (descriptor == null) throw new IOException(label + "_ESTABLISH_REJECTED");
            // Includes a descriptor returned after Stop, revoke, timeout or backgrounding.
            if (!session.own(descriptor)) return false;
            if (session.isCancelled()) return false;
            observer.observe(i == 0 ? "AFTER_PRIMARY" : "AFTER_HANDOVER");
        }
        return !session.isCancelled();
    }

    private ProbeHandover() {}
}
