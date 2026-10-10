// JNI transport adapted from WheelPlay (GPL-3.0), with upstream packetizers.
// https://github.com/fython/wheelplay/tree/c1badd5711159fe0ae9057773fbc240d0e462191
#include <jni.h>
#include <rtc/rtc.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>
#include <unordered_map>

extern "C" int diplaySendMedia(int, const char*, int);

namespace {
constexpr int MAX_SDP = 6144;
constexpr int MAX_FRAME = 4 * 1024 * 1024;
struct Utf {
    JNIEnv* env; jstring value; const char* bytes;
    Utf(JNIEnv* e, jstring v) : env(e), value(v), bytes(v ? e->GetStringUTFChars(v, nullptr) : nullptr) {
        if (!bytes) throw std::runtime_error("Missing string");
    }
    ~Utf() { env->ReleaseStringUTFChars(value, bytes); }
};
struct Peer;
std::mutex callbacksMutex;
std::unordered_map<int, Peer*> callbackPeers;
struct Peer {
    JavaVM* vm = nullptr;
    jobject owner = nullptr;
    jmethodID event = nullptr;
    int pc = -1, track = -1;
    std::atomic<bool> closing{false};
    std::atomic<int> candidates{0};
    std::string address;
    uint32_t timestampOrigin = 0;
    void emit(const char* kind, const char* value = "", const char* extra = "") {
        if (closing.load()) return;
        JNIEnv* env = nullptr;
        const int status = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        const bool attach = status == JNI_EDETACHED;
        if ((attach && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) ||
            (!attach && status != JNI_OK)) return;
        if (env->PushLocalFrame(3) == JNI_OK) {
            auto k = env->NewStringUTF(kind);
            auto v = env->NewStringUTF(value);
            auto x = env->NewStringUTF(extra);
            if (k && v && x) env->CallVoidMethod(owner, event, k, v, x);
            // Adapter only queues events; native deletion never runs on this callback.
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->PopLocalFrame(nullptr);
        }
        if (attach) vm->DetachCurrentThread();
    }
    void dispose(JNIEnv* env) {
        {
            // Callbacks resolve IDs under this lock, never dereference cached user pointers.
            // Waiting for the lock drains an in-flight callback before dropping the JNI owner.
            std::lock_guard<std::mutex> guard(callbacksMutex);
            closing.store(true);
            callbackPeers.erase(track);
            callbackPeers.erase(pc);
        }
        if (track >= 0) { rtcDeleteTrack(track); track = -1; }
        if (pc >= 0) { rtcClosePeerConnection(pc); rtcDeletePeerConnection(pc); pc = -1; }
        if (owner) { env->DeleteGlobalRef(owner); owner = nullptr; }
    }
};
template<class F> void callback(int id, F&& action) {
    std::lock_guard<std::mutex> guard(callbacksMutex);
    auto found = callbackPeers.find(id);
    if (found != callbackPeers.end()) action(found->second);
}
void check(int result) {
    if (result < 0) throw std::runtime_error("WebRTC operation failed: " + std::to_string(result));
}
void fail(JNIEnv* env, const std::exception& e) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
Peer* peer(jlong handle) {
    if (!handle) throw std::runtime_error("Closed peer");
    return reinterpret_cast<Peer*>(handle);
}
bool usableBindAddress(const char* text) {
    // The caller selects an actual local interface; kernel bind is authoritative.
    // Do not reject RFC6598/VPN or globally numbered LAN interfaces by prefix.
    std::string address(text);
    const auto scope = address.find('%');
    if (scope != std::string::npos) {
        if (scope == 0 || scope + 1 == address.size() || address.find('%', scope + 1) != std::string::npos)
            return false;
        address.resize(scope); // Keep the original scoped address for getaddrinfo/bind.
    }
    unsigned char b[16]{};
    if (inet_pton(AF_INET, address.c_str(), b) == 1)
        return scope == std::string::npos && b[0] != 0 && b[0] != 127 && b[0] < 224;
    if (inet_pton(AF_INET6, address.c_str(), b) == 1) {
        bool zeroPrefix = true;
        for (int i = 0; i < 15; ++i) zeroPrefix = zeroPrefix && b[i] == 0;
        return b[0] != 0xff && !(zeroPrefix && b[15] <= 1);
    }
    return false;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_browser_NativeBrowserRtcPeer_nativeCreate(
    JNIEnv* env, jobject owner, jstring format, jboolean hevc, jstring bindAddress) {
    auto p = std::make_unique<Peer>();
    try {
        Utf bind(env, bindAddress), profile(env, format);
        if (!usableBindAddress(bind.bytes)) throw std::runtime_error("no-lan-route");
        p->address = bind.bytes;
        check(env->GetJavaVM(&p->vm));
        p->owner = env->NewGlobalRef(owner);
        if (!p->owner) throw std::runtime_error("JNI reference allocation failed");
        auto cls = env->GetObjectClass(owner);
        p->event = env->GetMethodID(cls, "onNativeEvent",
                                   "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (!p->event) throw std::runtime_error("JNI callback unavailable");
        static std::once_flag logging;
        std::call_once(logging, [] {
            // SDP/candidates are never logged.
            rtcInitLogger(RTC_LOG_NONE, nullptr);
        });
        rtcConfiguration config{};
        config.bindAddress = p->address.c_str();
        config.disableAutoNegotiation = true;
        config.forceMediaTransport = true;
        config.enableIceTcp = false;
        config.mtu = 1200;
        p->pc = rtcCreatePeerConnection(&config); check(p->pc);
        rtcSetUserPointer(p->pc, p.get());
        { std::lock_guard<std::mutex> guard(callbacksMutex); callbackPeers[p->pc] = p.get(); }
        check(rtcSetLocalDescriptionCallback(p->pc,
            [](int id, const char* sdp, const char* type, void*) {
                callback(id, [&](Peer* p) {
                if (!sdp || std::char_traits<char>::length(sdp) > MAX_SDP ||
                    !type || std::string(type) != "offer") { p->emit("failed"); return; }
                p->emit("offer", sdp);
                });
            }));
        check(rtcSetLocalCandidateCallback(p->pc,
            [](int id, const char* candidate, const char* mid, void*) {
                callback(id, [&](Peer* p) {
                if (!candidate || !mid || std::char_traits<char>::length(candidate) > 1024 ||
                    std::char_traits<char>::length(mid) > 32 || p->candidates.fetch_add(1) >= 16) {
                    p->emit("failed"); return;
                }
                p->emit("candidate", candidate, mid);
                });
            }));
        check(rtcSetStateChangeCallback(p->pc, [](int id, rtcState state, void*) {
            callback(id, [&](Peer* p) {
            switch (state) {
                case RTC_CONNECTED: p->emit("connected"); break;
                case RTC_DISCONNECTED: p->emit("disconnected"); break;
                case RTC_FAILED: p->emit("failed"); break;
                case RTC_CLOSED: p->emit("closed"); break;
                default: break;
            }
            });
        }));
        std::random_device random;
        const uint32_t ssrc = random();
        p->timestampOrigin = random();
        rtcTrackInit track{};
        track.direction = RTC_DIRECTION_SENDONLY;
        track.codec = hevc ? RTC_CODEC_H265 : RTC_CODEC_H264;
        track.payloadType = 96; track.ssrc = ssrc;
        track.mid = "video"; track.name = "diplay"; track.msid = "diplay";
        track.trackId = "screen"; track.profile = profile.bytes;
        p->track = rtcAddTrackEx(p->pc, &track); check(p->track);
        rtcSetUserPointer(p->track, p.get());
        { std::lock_guard<std::mutex> guard(callbacksMutex); callbackPeers[p->track] = p.get(); }
        rtcPacketizerInit packetizer{};
        packetizer.ssrc = ssrc; packetizer.cname = "diplay"; packetizer.payloadType = 96;
        packetizer.clockRate = 90000; packetizer.sequenceNumber = static_cast<uint16_t>(random());
        packetizer.nalSeparator = RTC_NAL_SEPARATOR_START_SEQUENCE;
        packetizer.maxFragmentSize = 1100;
        check(hevc ? rtcSetH265Packetizer(p->track, &packetizer) :
                     rtcSetH264Packetizer(p->track, &packetizer));
        check(rtcChainRtcpSrReporter(p->track));
        check(rtcChainRtcpNackResponder(p->track, 512));
        check(rtcChainPliHandler(p->track, [](int id, void*) {
            callback(id, [](Peer* p) { p->emit("keyframe"); });
        }));
        return reinterpret_cast<jlong>(p.release());
    } catch (const std::exception& e) { p->dispose(env); fail(env, e); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_browser_NativeBrowserRtcPeer_nativeStart(JNIEnv* env, jobject, jlong h) {
    try { check(rtcSetLocalDescription(peer(h)->pc, "offer")); }
    catch (const std::exception& e) { fail(env, e); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_browser_NativeBrowserRtcPeer_nativeAnswer(JNIEnv* env, jobject, jlong h, jstring sdp) {
    try {
        Utf text(env, sdp);
        if (std::char_traits<char>::length(text.bytes) > MAX_SDP) throw std::runtime_error("Oversized SDP");
        check(rtcSetRemoteDescription(peer(h)->pc, text.bytes, "answer"));
    } catch (const std::exception& e) { fail(env, e); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_browser_NativeBrowserRtcPeer_nativeCandidate(
    JNIEnv* env, jobject, jlong h, jstring candidate, jstring mid) {
    try {
        Utf c(env, candidate), m(env, mid);
        if (std::char_traits<char>::length(c.bytes) > 1024 ||
            std::char_traits<char>::length(m.bytes) > 32) throw std::runtime_error("Oversized ICE candidate");
        check(rtcAddRemoteCandidate(peer(h)->pc, c.bytes, m.bytes));
    } catch (const std::exception& e) { fail(env, e); }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_browser_NativeBrowserRtcPeer_nativeSend(
    JNIEnv* env, jobject, jlong h, jbyteArray frame, jlong timestampUs) {
    try {
        auto p = peer(h);
        if (!frame || !rtcIsOpen(p->track)) return false;
        const int size = env->GetArrayLength(frame);
        if (size <= 0 || size > MAX_FRAME || timestampUs < 0) return false;
        std::vector<char> bytes(size);
        env->GetByteArrayRegion(frame, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return false;
        // Avoid overflowing the microseconds-to-90kHz conversion.
        const uint64_t us = static_cast<uint64_t>(timestampUs);
        const uint32_t timestamp = p->timestampOrigin + static_cast<uint32_t>((us / 1000) * 90 + (us % 1000) * 90 / 1000);
        if (rtcSetTrackRtpTimestamp(p->track, timestamp) < 0) return false;
        return diplaySendMedia(p->track, bytes.data(), size) >= 0;
    } catch (const std::exception& e) { fail(env, e); return false; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_browser_NativeBrowserRtcPeer_nativeClose(JNIEnv* env, jobject, jlong h) {
    std::unique_ptr<Peer> p(reinterpret_cast<Peer*>(h));
    if (p) p->dispose(env);
}

