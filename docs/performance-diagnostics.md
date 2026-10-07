# Performance diagnostics

Performance diagnostics is a separate, default-off switch in the original Settings screen and the in-CarPlay settings overlay. It does not enable the on-screen debug overlay or change decoding, frame queues, touch delivery, or codec presentation timestamps.

## Capture a comparison

1. Enable **Performance diagnostics** (性能诊断). In the in-CarPlay settings overlay, choose **Save** to keep the setting (the switch applies immediately; Cancel restores the saved setting).
2. Connect and reproduce a repeatable action, such as scrolling the same screen. Avoid changing resolution, frame rate, phone, or receiver between runs.
3. Use the existing **Save diagnostic report** or **Share diagnostic report** action. Nothing is uploaded automatically.
4. Turn capture off after exporting. Off retains the last in-memory capture for export; enabling it again starts a fresh capture. App process exit loses the capture.
5. Repeat over the other connection, e.g. Android hotspot versus the same router. Label the exported reports yourself with the connection used; diagnostics do not collect SSIDs, IPs, touch coordinates, or frame contents.

Capture remains enabled if you navigate between settings and CarPlay, and the preference survives app restart. For a clean comparison, switch off and back on before each run. Export before restarting the app.

## Reading the report

Every stage reports milliseconds, total sample count `n`, recent sample count `window`, recent p50/p95/p99, lifetime maximum, and counts at least 250 ms / 2000 ms. Percentiles use the latest 512 samples, while maximum and threshold counts span the entire enabled capture. They are not whole-capture percentiles. Samples are collected only when the switch is enabled; missing stages show `n=0`.

- `touch_queue_wait`: enqueue to the touch executor starting the task. Includes waiting behind other executor commands.
- `touch_worker`: time inside the touch task, including report construction and send.
- `touch_send`: synchronous HID send duration; completion does not mean the phone received or processed it.
- `touch_enqueue_to_complete`: enqueue through task completion.
- `touch_sent_to_next_frame_proxy`: first completed touch send to the next main-screen decoder submission. This is **uncorrelated**, not measured touch response latency. Extra touches before that frame increment a sampling counter; actual touches are never coalesced or dropped by diagnostics.
- `video_queue_wait`: decoder submission to worker processing.
- `video_input_acquire`: obtaining an input buffer, including output draining and input waits.
- `video_worker_to_feed`: worker processing through successful input submission.
- `video_feed_to_release` / `video_receive_to_release`: input submission or decoder receipt through output buffer release to the Surface.
- `video_release_to_render` / `video_receive_to_render`: codec-reported rendering, when the callback is supported and an unambiguous timestamp match is available.
- `video_*_gap`: intervals between receipt, output release, or render events. Long static-screen gaps are included rather than hidden. Threshold counts are **stall candidates**, not evidence of packet loss.

Video stages aggregate all active decoder streams. Queue depth reports the latest sampled decoder queue, and peak is the maximum single queue, not total depth across streams. Touch depth covers touch tasks only. Counters distinguish stale/overflow/reference-chain drops, input stalls, recovery, missing correlation, ambiguous timestamps, and bounded tracking evictions.

The new diagnostics do not alter the pre-existing `video stats` fields: its historical `shown` counter counts output release, and its `maxGap` excludes long gaps. Use the new `video_*` metrics for these distinctions.

## Limits and overhead

- Receipt is measured at decoder submission, **after transport, decryption, and any earlier dispatch**. Network one-way latency, iPhone work, and display scanout cannot be separated here.
- Codec release is not presentation. A frame-rendered callback is not a camera measurement of glass-to-glass latency, and callback delivery can be delayed or missing on vendor codecs.
- Input PTS values remain unchanged. Duplicate PTS or config outputs are excluded from correlated measurements; bounded maps and session/codec generations prevent stale matches.
- State is bounded: 512 numeric samples per metric plus bounded in-flight frame tracking. Summary logging occurs at most once per ten seconds when video workers run; report export always includes a fresh snapshot. There are no new per-frame logs or payload dumps.
- Capture adds timestamp reads and short synchronization sections. Compare repeated runs; do not treat one run or a percentile with very few samples as proof of a bottleneck.

Validation on a real receiver is still required to establish actual latency or the cause of a hotspot/router difference. This feature measures; it does not claim a performance improvement.
