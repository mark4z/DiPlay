package com.shilapi.xcertplay.browser;

import java.io.Closeable;
import java.io.IOException;

/** Sequential public-API experiment. Own every returned descriptor before another operation. */
public final class BrowserHandover {
    /** A fixed rejection code, distinct from an OEM exception thrown by establish(). */
    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        public final String code;
        private Failure(String code) { super(code); this.code = code; }
    }
    public interface Establisher { Closeable establish(String address) throws Exception; }
    public interface Observer { void observe(String stage); }

    public static boolean establish(BrowserSession session, int mode, Establisher establisher,
            Observer observer) throws Exception {
        String[] addresses = BrowserHttpsPolicy.addresses(mode);
        for (int i = 0; i < addresses.length; i++) {
            if (session.isCancelled()) return false;
            String label = i == 0 ? "PRIMARY" : "COMPATIBILITY";
            observer.observe(label + "_ESTABLISHING");
            if (session.isCancelled()) return false;
            Closeable descriptor = establisher.establish(addresses[i]);
            if (descriptor == null) throw new Failure("VPN_" + label + "_ESTABLISH_REJECTED");
            // Includes a descriptor returned after Stop, revoke, timeout or backgrounding.
            if (!session.own(descriptor)) return false;
            if (session.isCancelled()) return false;
            observer.observe(i == 0 ? "AFTER_PRIMARY" : "AFTER_HANDOVER");
        }
        return !session.isCancelled();
    }

    private BrowserHandover() {}
}
