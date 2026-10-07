package com.diplay.networkprobe;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Exactly one bind attempt on the selected port. The caller owns and closes the socket. */
final class ProbeListener {
    private ProbeListener() {}

    static boolean bind(ServerSocket listener, int port, BooleanSupplier cancelled) throws IOException {
        ProbePolicy.requireTestPort(port);
        if (cancelled.getAsBoolean()) return false;
        InetAddress address = InetAddress.getByName(ProbePolicy.ADDRESS);
        if (cancelled.getAsBoolean()) return false;
        listener.bind(new InetSocketAddress(address, port), 1);
        return !cancelled.getAsBoolean();
    }

    /** Android supplies errno extraction; JVM tests use synthetic causes without an Android runtime. */
    static String failureDescription(int port, Throwable failure, Function<Throwable, String> errno) {
        String systemError = "errno=UNAVAILABLE";
        Throwable deepest = failure;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            deepest = cause;
            String found = errno.apply(cause);
            if (found != null) { systemError = found; break; }
        }
        String detail = deepest.getClass().getSimpleName() + ": " + deepest.getMessage();
        detail = detail.replace('\n', ' ').replace('\r', ' ');
        if (detail.length() > 200) detail = detail.substring(0, 200);
        return "BIND_FAILED port=" + port + "; " + systemError + "; " + detail;
    }
}
