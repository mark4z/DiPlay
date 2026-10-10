# Based on WheelPlay's GPL-3.0 media-send feedback fix, intentionally excluding
# its custom packetizer and crypto patches. Upstream files remain MPL-2.0.
diplay_replace("${libdatachannel_SOURCE_DIR}/src/impl/track.cpp" [=[
		bool ret = false;
		for (auto &m : messages)
			ret = transportSend(std::move(m));

		return ret;]=] [=[
		bool ret = !messages.empty();
		for (auto &m : messages) {
			const bool sent = transportSend(std::move(m));
			ret = sent && ret;
		}
		return ret;]=])
# Do not change data-channel buffered-send semantics.
diplay_replace("${libdatachannel_SOURCE_DIR}/src/capi.cpp"
    "int rtcSendMessage(int id, const char *data, int size) {" [=[
extern "C" RTC_C_EXPORT int diplaySendMedia(int id, const char *data, int size) {
    return wrap([&] {
        if (!data || size <= 0) throw std::invalid_argument("Invalid media frame");
        auto bytes = reinterpret_cast<const byte *>(data);
        return getTrack(id)->send(binary(bytes, bytes + size)) ? RTC_ERR_SUCCESS : RTC_ERR_FAILURE;
    });
}

int rtcSendMessage(int id, const char *data, int size) {]=])

# Compressed passthrough has no encoder-rate control; do not advertise REMB support.
# The upstream packetizer's NACK/PLI feedback remains enabled.
diplay_replace("${libdatachannel_SOURCE_DIR}/src/description.cpp"
    "\tmap.addFeedback(\"goog-remb\");"
    "\t// DiPlay passthrough: no goog-remb encoder feedback is advertised.")
