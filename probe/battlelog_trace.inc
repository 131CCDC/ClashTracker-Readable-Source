// Battle Log trace: what does the client receive and parse when the player
// opens the battle log?
//
// Development instrument, not part of the shipping feature set. Four rules hold
// everywhere in this file:
//
//   1. OFF BY DEFAULT.  Every hook begins with one relaxed atomic load and a
//      branch; with tracing stopped the added cost is a predictable not-taken
//      branch on a call the game was making anyway.
//   2. NO I/O AND NO ALLOCATION ON A HOT PATH.  The readable-range helper used
//      to reopen /proc/self/maps on every call, and the JSON parser hook runs
//      about 400 times a second, so the trace was costing the game real
//      milliseconds per frame.  Mappings are now snapshotted into a
//      double-buffered table and looked up by binary search; nothing in a hook
//      opens a file, allocates, takes a long lock or serialises anything.
//   3. VERSION GUARDED.  Each hook carries the attested 16-byte prologue of the
//      function it patches, and `install_inline_hook` refuses the patch when the
//      bytes differ, so a different libg build installs nothing.
//   4. FAIL OPEN.  A hook that cannot be installed is logged and skipped.
//
// Work is split by frequency:
//
//   every call   -> one atomic load, a small table scan, one atomic increment
//   first sight  -> snapshot the object and chase its immediate children once
//   on demand    -> TRACE_DUMP / TRACE_MSGDUMP serialise and send
//
// The sites were located by static analysis of the attested 15.535.13 libg.so
// (VMA == file offset, single PT_LOAD); see research/battlelog/FINDINGS.md.

#pragma once

namespace cr_battlelog {

// ------------------------------------------------------------------ config --

constexpr uintptr_t kMsgDispatchOffset = 0x00ad3da8;
constexpr uint8_t kMsgDispatchPrologue[16] = {
    0xfd, 0x7b, 0xba, 0xa9, 0xfc, 0x6f, 0x01, 0xa9, 0xfa, 0x67, 0x02, 0xa9, 0xf8, 0x5f, 0x03, 0xa9,
};
constexpr uintptr_t kReplayDataSetOffset = 0x00c8de8c;
constexpr uint8_t kReplayDataSetPrologue[16] = {
    0xfd, 0x7b, 0xbe, 0xa9, 0xf4, 0x4f, 0x01, 0xa9, 0xfd, 0x03, 0x00, 0x91, 0xf4, 0x03, 0x01, 0xaa,
};
constexpr uintptr_t kJsonParseOffset = 0x012197e8;
constexpr uint8_t kJsonParsePrologue[16] = {
    0xff, 0x83, 0x01, 0xd1, 0xfd, 0x7b, 0x02, 0xa9, 0xf7, 0x1b, 0x00, 0xf9, 0xf6, 0x57, 0x04, 0xa9,
};

constexpr uint32_t kKindMessage = 1;
constexpr uint32_t kKindReplayData = 2;
constexpr uint32_t kKindJsonParse = 3;

constexpr int kRingCapacity = 4096;
constexpr int kPayloadSlots = 8;
constexpr int kPayloadCapacity = 128 * 1024;
constexpr int kHistogramSlots = 256;
constexpr int kKeyBytes = 48;
constexpr int kMessageRingCapacity = 256;

constexpr int kMessageDumpSlots = 32;
constexpr int kMessageDumpBytes = 1024;
constexpr int kChaseChildren = 8;
constexpr int kChaseChildBytes = 512;

constexpr int kSeenPointerSlots = 64;

/** Upper bound on ring entries written from a hot shared routine per session. */
constexpr uint32_t kMaxWatchedEvents = 2048;

/** A missed lookup may refresh the map cache at most this often. */
constexpr int64_t kMapRefreshMinIntervalMs = 3000;

/** Code regions worth a ring entry; everything else only feeds the histogram. */
inline bool trace_watch_caller(uint32_t caller) {
    return (caller >= 0x00ad3000 && caller < 0x00ad8000) ||   // message handlers
           (caller >= 0x00c8c000 && caller < 0x00c90000) ||   // battle controller / replay
           (caller >= 0x01060000 && caller < 0x01080000) ||   // home data decoders
           (caller >= 0x01155000 && caller < 0x01157000);     // replay consumer
}

/**
 * Where the trace marker lives.
 *
 * The probe runs inside the game, so the game's own external files directory is
 * writable without any permission, and the marker can also be inspected over
 * ADB. Arming is NOT the default workflow: the login burst has already been
 * characterised, and a cold start is minutes long.
 */
constexpr const char *kArmMarkerPath =
    "/sdcard/Android/data/nullsroyale.rel.free/files/battlelog_trace.arm";

// ------------------------------------------------------- readable map cache --

/**
 * Sorted snapshot of the process's readable mappings.
 *
 * `is_readable_range` opens and scans /proc/self/maps per call, which is far
 * too expensive to sit under a hook that runs hundreds of times a second. The
 * snapshot is published into one of two buffers and swapped, so a reader always
 * sees a complete table without taking a lock.
 */
constexpr int kMapCacheCapacity = 4096;

struct MapRange {
    uintptr_t begin;
    uintptr_t end;
};

MapRange g_map_cache[2][kMapCacheCapacity];
std::atomic<int> g_map_count[2] = {{0}, {0}};
std::atomic<int> g_map_active{0};
std::atomic<int64_t> g_map_stamp{0};
std::atomic<bool> g_map_refreshing{false};

/** Reads /proc/self/maps once and publishes it. Called off any hot path. */
inline void trace_refresh_maps() {
    const int next = 1 - g_map_active.load(std::memory_order_relaxed);
    int count = 0;
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps != nullptr) {
        char line[512];
        while (count < kMapCacheCapacity && fgets(line, sizeof(line), maps)) {
            unsigned long long begin = 0;
            unsigned long long end = 0;
            char permissions[5] = {};
            if (sscanf(line, "%llx-%llx %4s", &begin, &end, permissions) != 3) continue;
            if (permissions[0] != 'r') continue;
            g_map_cache[next][count].begin = static_cast<uintptr_t>(begin);
            g_map_cache[next][count].end = static_cast<uintptr_t>(end);
            count++;
        }
        fclose(maps);
    }
    g_map_count[next].store(count, std::memory_order_release);
    g_map_active.store(next, std::memory_order_release);
    g_map_stamp.store(current_time_ms(), std::memory_order_release);
}

inline size_t trace_readable_from_cache(uintptr_t begin, size_t max) {
    const int active = g_map_active.load(std::memory_order_acquire);
    const int count = g_map_count[active].load(std::memory_order_acquire);
    if (count <= 0) return 0;
    const MapRange *ranges = g_map_cache[active];
    int low = 0;
    int high = count;
    while (low < high) {
        const int mid = (low + high) / 2;
        if (ranges[mid].begin <= begin) {
            low = mid + 1;
        } else {
            high = mid;
        }
    }
    if (low == 0) return 0;
    const MapRange &range = ranges[low - 1];
    if (begin < range.begin || begin >= range.end) return 0;
    const size_t span = static_cast<size_t>(range.end - begin);
    return span < max ? span : max;
}

/**
 * How many bytes are readable starting at `address`, capped at `max`.
 *
 * A miss may simply mean the heap grew since the snapshot, so a miss triggers
 * at most one refresh per [kMapRefreshMinIntervalMs] and only from one thread.
 */
inline size_t trace_readable_bytes(const void *address, size_t max) {
    const uintptr_t begin = reinterpret_cast<uintptr_t>(address) & 0x00ffffffffffffffULL;
    if (begin == 0 || max == 0) return 0;
    const size_t cached = trace_readable_from_cache(begin, max);
    if (cached > 0) return cached;
    const int64_t now = current_time_ms();
    if (now - g_map_stamp.load(std::memory_order_relaxed) <= kMapRefreshMinIntervalMs) return 0;
    bool expected = false;
    if (!g_map_refreshing.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) return 0;
    trace_refresh_maps();
    g_map_refreshing.store(false, std::memory_order_release);
    return trace_readable_from_cache(begin, max);
}

// ------------------------------------------------------------------- state --

struct TraceEntry {
    uint64_t sequence;
    uint64_t at_ms;
    uint32_t kind;
    uint32_t value;
    uint64_t extra;
    char     text[kKeyBytes];
};

struct TraceMessage {
    uint64_t sequence;
    uint64_t at_ms;
    uint32_t type;
    uint32_t caller;
};

/**
 * One snapshot per incoming message type, plus the first level of the object
 * graph behind it.
 *
 * Message objects hold pointers, not inline data: a deck array is a separate
 * allocation. Chasing one level at capture time is what makes the snapshot
 * useful, and it happens once per type instead of once per message.
 */
struct MessageDump {
    std::atomic<uint32_t> type{0};
    std::atomic<uint32_t> length{0};
    std::atomic<uint32_t> hits{0};
    std::atomic<uint32_t> child_count{0};
    std::atomic<uint64_t> child_addresses[kChaseChildren] = {};
    std::atomic<uint32_t> child_lengths[kChaseChildren] = {};
    char data[kMessageDumpBytes];
    char children[kChaseChildren][kChaseChildBytes];
};

struct PayloadSlot {
    std::atomic<uint32_t> length{0};
    std::atomic<uint64_t> sequence{0};
    std::atomic<bool> truncated{false};
    std::atomic<uint32_t> origin{0};
    char data[kPayloadCapacity];
};

TraceEntry g_trace_ring[kRingCapacity];
TraceMessage g_trace_messages[kMessageRingCapacity];
MessageDump g_trace_message_dumps[kMessageDumpSlots];
PayloadSlot g_trace_payloads[kPayloadSlots];

std::atomic<uint32_t> g_trace_message_count{0};
std::atomic<uint64_t> g_trace_sequence{0};
std::atomic<bool> g_trace_active{false};
std::atomic<bool> g_trace_armed{false};
std::atomic<uint32_t> g_trace_message_dump_count{0};
std::atomic<uint32_t> g_trace_watched_events{0};
std::atomic<uint32_t> g_trace_payload_next{0};
std::atomic<uint32_t> g_trace_seen_count{0};
uint64_t g_trace_seen_pointers[kSeenPointerSlots] = {};

struct HistogramSlot {
    std::atomic<uint32_t> caller{0};
    std::atomic<uint32_t> count{0};
};
HistogramSlot g_trace_histogram[kHistogramSlots];

void *g_trace_msg_dispatch_trampoline = nullptr;
void *g_trace_replay_set_trampoline = nullptr;
void *g_trace_json_parse_trampoline = nullptr;
bool g_trace_msg_dispatch_ready = false;
bool g_trace_replay_set_ready = false;
bool g_trace_json_parse_ready = false;

// ------------------------------------------------------------------ helpers --

/** One caller -> count table, so the diff can name callers it never guessed. */
inline void trace_note_caller(uint32_t caller) {
    const uint32_t start = (caller * 2654435761u) % kHistogramSlots;
    for (uint32_t probe = 0; probe < 8; ++probe) {
        HistogramSlot &slot = g_trace_histogram[(start + probe) % kHistogramSlots];
        uint32_t current = slot.caller.load(std::memory_order_relaxed);
        if (current == caller) {
            slot.count.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        if (current == 0) {
            uint32_t expected = 0;
            if (slot.caller.compare_exchange_strong(expected, caller, std::memory_order_relaxed)) {
                slot.count.fetch_add(1, std::memory_order_relaxed);
                return;
            }
            if (slot.caller.load(std::memory_order_relaxed) == caller) {
                slot.count.fetch_add(1, std::memory_order_relaxed);
                return;
            }
        }
    }
}

/** True when this address was already handled; records it otherwise. */
inline bool trace_seen_pointer(const void *pointer) {
    if (pointer == nullptr) return false;
    const uint64_t value = reinterpret_cast<uint64_t>(pointer);
    const uint32_t used = g_trace_seen_count.load(std::memory_order_acquire);
    for (uint32_t index = 0; index < used && index < kSeenPointerSlots; ++index) {
        if (g_trace_seen_pointers[index] == value) return true;
    }
    uint32_t expected = used;
    if (g_trace_seen_count.compare_exchange_strong(expected, used + 1, std::memory_order_acq_rel)) {
        if (used < kSeenPointerSlots) g_trace_seen_pointers[used] = value;
    }
    return false;
}

inline void trace_push(uint32_t kind, uint32_t value, uint64_t extra, const char *text) {
    const uint64_t sequence = g_trace_sequence.fetch_add(1, std::memory_order_relaxed);
    const uint64_t now = static_cast<uint64_t>(current_time_ms());
    if (kind == kKindMessage) {
        const uint32_t index =
            g_trace_message_count.fetch_add(1, std::memory_order_relaxed) % kMessageRingCapacity;
        TraceMessage &message = g_trace_messages[index];
        message.sequence = sequence;
        message.at_ms = now;
        message.type = value;
        message.caller = static_cast<uint32_t>(extra);
    }
    TraceEntry &entry = g_trace_ring[sequence % kRingCapacity];
    entry.sequence = sequence;
    entry.at_ms = now;
    entry.kind = kind;
    entry.value = value;
    entry.extra = extra;
    entry.text[0] = 0;
    if (text != nullptr) {
        int index = 0;
        for (; index < kKeyBytes - 1 && text[index] != 0; ++index) entry.text[index] = text[index];
        entry.text[index] = 0;
    }
}

/** Copies a buffer into the next payload slot. Called only on first sight. */
inline void trace_store_payload(const void *data, uint32_t length, uint32_t origin) {
    if (data == nullptr || length == 0) return;
    const uint32_t capacity = static_cast<uint32_t>(kPayloadCapacity);
    const uint32_t copied = length < capacity ? length : capacity;
    const size_t available = trace_readable_bytes(data, copied);
    if (available < 16) return;
    const uint32_t stored = static_cast<uint32_t>(available);
    const uint32_t slot_index = g_trace_payload_next.fetch_add(1, std::memory_order_relaxed) % kPayloadSlots;
    PayloadSlot &slot = g_trace_payloads[slot_index];
    slot.length.store(0, std::memory_order_relaxed);
    std::memcpy(slot.data, data, stored);
    slot.origin.store(origin, std::memory_order_relaxed);
    slot.truncated.store(length > capacity, std::memory_order_relaxed);
    slot.sequence.store(g_trace_sequence.load(std::memory_order_relaxed), std::memory_order_relaxed);
    slot.length.store(stored, std::memory_order_release);
}

/**
 * Locates (data, length) inside a byte-buffer object.
 *
 * The layout was inferred from the constructor at libg 0x138a800
 * (`{ int32 sentinel = -1; int32 size = 0; char* data = nullptr; }`), but an
 * inference is not a measurement, so several plausible layouts are tried and
 * each candidate is validated against the cached map. Only ever called for a
 * buffer pointer that has not been seen before.
 */
inline bool trace_read_buffer(const void *object, const char **data, uint32_t *length) {
    *data = nullptr;
    *length = 0;
    if (!is_valid_pointer(object)) return false;

    struct Candidate {
        int32_t size;
        void *pointer;
    };
    Candidate probe[4] = {};
    safe_field(object, 4, probe[0].size);       // A: { cap; size; data }
    safe_field(object, 8, probe[0].pointer);
    safe_field(object, 0, probe[1].size);       // B: { size; cap; data }
    safe_field(object, 8, probe[1].pointer);
    safe_field(object, 0, probe[2].pointer);    // C: { data; size; cap }
    safe_field(object, 8, probe[2].size);
    safe_field(object, 0, probe[3].size);       // D: { size; ...; data @0x10 }
    safe_field(object, 0x10, probe[3].pointer);

    for (const Candidate &candidate : probe) {
        if (candidate.size <= 0 || candidate.size > 8 * 1024 * 1024) continue;
        if (!is_valid_pointer(candidate.pointer)) continue;
        const size_t window = static_cast<size_t>(candidate.size) < 4096
            ? static_cast<size_t>(candidate.size)
            : 4096;
        if (trace_readable_bytes(candidate.pointer, window) < window) continue;
        *data = static_cast<const char *>(candidate.pointer);
        *length = static_cast<uint32_t>(candidate.size);
        return true;
    }
    return false;
}

inline uint32_t trace_libg_offset(const void *address) {
    const uintptr_t value = reinterpret_cast<uintptr_t>(address);
    if (g_libg_base == 0 || value < g_libg_base) return 0;
    const uintptr_t offset = value - g_libg_base;
    return offset < 0x10000000u ? static_cast<uint32_t>(offset) : 0;
}

/**
 * Snapshots the objects the message points at, one level deep.
 *
 * Runs once per message type. Children are deduplicated by address and the walk
 * is bounded by both the child count and the scan window, so a self-referential
 * or hostile object graph cannot turn this into an unbounded traversal.
 */
inline void trace_chase_children(MessageDump &slot, const void *object, size_t available) {
    const size_t limit = available < 512 ? available : 512;
    int children = 0;
    for (size_t offset = 0; offset + 8 <= limit && children < kChaseChildren; offset += 8) {
        uint64_t value = 0;
        std::memcpy(&value, static_cast<const uint8_t *>(object) + offset, 8);
        if (value < 0x1000000000ULL || value > 0x0000ffffffffffffULL) continue;
        bool duplicate = false;
        for (int index = 0; index < children; ++index) {
            if (slot.child_addresses[index].load(std::memory_order_relaxed) == value) {
                duplicate = true;
                break;
            }
        }
        if (duplicate) continue;
        const void *child = reinterpret_cast<const void *>(static_cast<uintptr_t>(value));
        const size_t childAvailable = trace_readable_bytes(child, kChaseChildBytes);
        if (childAvailable < 16) continue;
        slot.child_addresses[children].store(value, std::memory_order_relaxed);
        std::memcpy(slot.children[children], child, childAvailable);
        slot.child_lengths[children].store(
            static_cast<uint32_t>(childAvailable), std::memory_order_release);
        children++;
    }
    slot.child_count.store(static_cast<uint32_t>(children), std::memory_order_release);
}

/**
 * Snapshots one message type.
 *
 * The fast path is a scan of a table that holds at most 32 entries: no file
 * I/O, no allocation, no memcpy, no lock. The expensive snapshot happens only
 * the first time a type is seen, so the login burst costs nine snapshots rather
 * than one per message.
 */
inline void trace_store_message_dump(const void *message, uint32_t type) {
    if (type == 0) return;
    const uint32_t used = g_trace_message_dump_count.load(std::memory_order_acquire);
    for (uint32_t index = 0; index < used && index < kMessageDumpSlots; ++index) {
        MessageDump &existing = g_trace_message_dumps[index];
        if (existing.type.load(std::memory_order_relaxed) == type) {
            existing.hits.fetch_add(1, std::memory_order_relaxed);
            return;
        }
    }
    uint32_t expected = used;
    if (!g_trace_message_dump_count.compare_exchange_strong(expected, used + 1, std::memory_order_acq_rel)) {
        return;
    }
    if (used >= kMessageDumpSlots) return;
    MessageDump &slot = g_trace_message_dumps[used];
    slot.type.store(type, std::memory_order_relaxed);
    const size_t available = trace_readable_bytes(message, kMessageDumpBytes);
    if (available < 64) {
        slot.length.store(0, std::memory_order_release);
        return;
    }
    std::memcpy(slot.data, message, available);
    slot.length.store(static_cast<uint32_t>(available), std::memory_order_release);
    slot.hits.store(1, std::memory_order_relaxed);
    trace_chase_children(slot, message, available);
}

// -------------------------------------------------------------------- hooks --

using MsgDispatchFn = void (*)(void *, void *);
using ReplaySetFn = void (*)(void *, void *);
using JsonParseFn = void *(*)(void *, void *, void *, int32_t);

/**
 * Incoming-message dispatcher. Records the message type the client is about to
 * handle, which is the cheapest possible answer to "did opening the battle log
 * make the client receive something new?".
 */
void trace_msg_dispatch(void *client, void *message) {
    if (g_trace_active.load(std::memory_order_relaxed)) {
        uint32_t type = 0;
        void *vtable = nullptr;
        if (is_valid_pointer(message) && safe_field(message, 0, vtable) && is_valid_pointer(vtable)) {
            void *getter = nullptr;
            if (safe_field(vtable, 0x28, getter) && is_valid_pointer(getter) &&
                trace_libg_offset(getter) != 0) {
                using GetType = int32_t (*)(void *);
                type = static_cast<uint32_t>(reinterpret_cast<GetType>(getter)(message));
            }
        }
        const uint32_t caller = trace_libg_offset(__builtin_return_address(0));
        trace_note_caller(caller);
        trace_push(kKindMessage, type, caller, nullptr);
        trace_store_message_dump(message, type);
    }
    if (g_trace_msg_dispatch_trampoline != nullptr) {
        reinterpret_cast<MsgDispatchFn>(g_trace_msg_dispatch_trampoline)(client, message);
    }
}

/**
 * `setReplayData(this, buffer)` — the single place the client hands a replay
 * payload to the battle/game-state manager. One caller, so the buffer here is
 * the raw payload and nothing else.
 */
void trace_replay_set(void *self, void *buffer) {
    if (g_trace_active.load(std::memory_order_relaxed)) {
        const uint32_t caller = trace_libg_offset(__builtin_return_address(0));
        if (trace_seen_pointer(buffer)) {
            trace_push(kKindReplayData, 0, caller, "repeat");
        } else {
            const char *data = nullptr;
            uint32_t length = 0;
            const bool captured = trace_read_buffer(buffer, &data, &length);
            trace_push(kKindReplayData, captured ? length : 0, caller,
                       captured ? nullptr : "unreadable-buffer");
            // Always keep a raw window of the buffer object itself: if every
            // layout guess is wrong, the bytes that identify the layout are on
            // record instead of the capture being a silent miss.
            if (is_valid_pointer(buffer)) {
                const size_t window = trace_readable_bytes(buffer, 256);
                if (window >= 16) trace_store_payload(buffer, static_cast<uint32_t>(window), 0xD1A6u);
            }
            if (captured && !trace_seen_pointer(data)) {
                trace_store_payload(data, length, caller);
            }
        }
    }
    if (g_trace_replay_set_trampoline != nullptr) {
        reinterpret_cast<ReplaySetFn>(g_trace_replay_set_trampoline)(self, buffer);
    }
}

/**
 * JSON parser entry.
 *
 * This entry is shared with hot text code (about 400 calls a second while the
 * client draws), so it does no probing at all: the caller histogram always
 * sees the call, and a ring entry is written only for a watched caller, under a
 * hard cap for the whole session.
 */
void *trace_json_parse(void *input, void *flag, void *a3, int32_t w3) {
    if (g_trace_active.load(std::memory_order_relaxed)) {
        const uint32_t caller = trace_libg_offset(__builtin_return_address(0));
        trace_note_caller(caller);
        if (trace_watch_caller(caller) &&
            g_trace_watched_events.fetch_add(1, std::memory_order_relaxed) < kMaxWatchedEvents) {
            trace_push(kKindJsonParse, 0, caller, nullptr);
        }
    }
    if (g_trace_json_parse_trampoline != nullptr) {
        return reinterpret_cast<JsonParseFn>(g_trace_json_parse_trampoline)(input, flag, a3, w3);
    }
    return nullptr;
}

// ------------------------------------------------------------------- control --

inline void trace_apply_arm_marker();

inline void trace_install() {
    if (g_libg_base == 0) {
        LOGI("BattleLog trace: no libg base, not installing");
        return;
    }
    g_trace_msg_dispatch_ready = install_inline_hook(
        g_libg_base + kMsgDispatchOffset, kMsgDispatchPrologue, 16,
        reinterpret_cast<const void *>(&trace_msg_dispatch),
        reinterpret_cast<void **>(&g_trace_msg_dispatch_trampoline), "BattleLog::msgDispatch");
    g_trace_replay_set_ready = install_inline_hook(
        g_libg_base + kReplayDataSetOffset, kReplayDataSetPrologue, 16,
        reinterpret_cast<const void *>(&trace_replay_set),
        reinterpret_cast<void **>(&g_trace_replay_set_trampoline), "BattleLog::replayDataSet");
    g_trace_json_parse_ready = install_inline_hook(
        g_libg_base + kJsonParseOffset, kJsonParsePrologue, 16,
        reinterpret_cast<const void *>(&trace_json_parse),
        reinterpret_cast<void **>(&g_trace_json_parse_trampoline), "BattleLog::jsonParse");
    LOGI("BattleLog trace hooks: msg=%d replay=%d parse=%d",
         g_trace_msg_dispatch_ready ? 1 : 0,
         g_trace_replay_set_ready ? 1 : 0,
         g_trace_json_parse_ready ? 1 : 0);
    trace_refresh_maps();
    trace_apply_arm_marker();
}

inline void trace_reset() {
    g_trace_sequence.store(0, std::memory_order_relaxed);
    g_trace_message_count.store(0, std::memory_order_relaxed);
    g_trace_watched_events.store(0, std::memory_order_relaxed);
    g_trace_payload_next.store(0, std::memory_order_relaxed);
    g_trace_seen_count.store(0, std::memory_order_relaxed);
    for (int i = 0; i < kSeenPointerSlots; ++i) g_trace_seen_pointers[i] = 0;
    for (int i = 0; i < kHistogramSlots; ++i) {
        g_trace_histogram[i].caller.store(0, std::memory_order_relaxed);
        g_trace_histogram[i].count.store(0, std::memory_order_relaxed);
    }
    for (int i = 0; i < kPayloadSlots; ++i) {
        g_trace_payloads[i].length.store(0, std::memory_order_relaxed);
        g_trace_payloads[i].sequence.store(0, std::memory_order_relaxed);
        g_trace_payloads[i].truncated.store(false, std::memory_order_relaxed);
        g_trace_payloads[i].origin.store(0, std::memory_order_relaxed);
    }
    for (int i = 0; i < kMessageDumpSlots; ++i) {
        g_trace_message_dumps[i].type.store(0, std::memory_order_relaxed);
        g_trace_message_dumps[i].length.store(0, std::memory_order_relaxed);
        g_trace_message_dumps[i].hits.store(0, std::memory_order_relaxed);
        g_trace_message_dumps[i].child_count.store(0, std::memory_order_relaxed);
    }
    g_trace_message_dump_count.store(0, std::memory_order_relaxed);
}

/** Persistent arming, kept only for completeness; not the default workflow. */
inline bool trace_arm_marker_present() {
    return access(kArmMarkerPath, F_OK) == 0;
}

inline bool trace_set_arm_marker(bool armed) {
    if (armed) {
        FILE *marker = fopen(kArmMarkerPath, "w");
        if (marker == nullptr) return false;
        fputs("armed\n", marker);
        fclose(marker);
        return true;
    }
    return remove(kArmMarkerPath) == 0 || !trace_arm_marker_present();
}

inline void trace_apply_arm_marker() {
    if (!trace_arm_marker_present()) return;
    g_trace_active.store(true, std::memory_order_release);
    g_trace_armed.store(true, std::memory_order_release);
    LOGI("BattleLog trace armed at load; tracing is ON for this process");
}

// --------------------------------------------------------------- serializing --

inline int trace_json_escape(char *out, int offset, int size, const char *text) {
    for (int i = 0; text[i] != 0 && offset + 8 < size; ++i) {
        const unsigned char c = static_cast<unsigned char>(text[i]);
        if (c == '"' || c == '\\') {
            out[offset++] = '\\';
            out[offset++] = static_cast<char>(c);
        } else if (c >= 0x20 && c < 0x7f) {
            out[offset++] = static_cast<char>(c);
        } else {
            offset += snprintf(out + offset, size - offset, "\\u%04x", c);
        }
    }
    return offset;
}

inline int trace_base64_into(char *out, int offset, int size,
                             const unsigned char *bytes, uint32_t length) {
    static const char kAlphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    for (uint32_t i = 0; i < length && offset + 8 < size; i += 3) {
        const uint32_t remaining = length - i;
        const uint32_t chunk = (static_cast<uint32_t>(bytes[i]) << 16) |
            (remaining > 1 ? static_cast<uint32_t>(bytes[i + 1]) << 8 : 0) |
            (remaining > 2 ? static_cast<uint32_t>(bytes[i + 2]) : 0);
        out[offset++] = kAlphabet[(chunk >> 18) & 0x3f];
        out[offset++] = kAlphabet[(chunk >> 12) & 0x3f];
        out[offset++] = remaining > 1 ? kAlphabet[(chunk >> 6) & 0x3f] : '=';
        out[offset++] = remaining > 2 ? kAlphabet[chunk & 0x3f] : '=';
    }
    return offset;
}

/** Ring + message ring + histogram + payload metadata; fits one response. */
inline int trace_dump(char *json, int size, uint32_t max_entries) {
    const uint64_t total = g_trace_sequence.load(std::memory_order_relaxed);
    const uint32_t messages = g_trace_message_count.load(std::memory_order_relaxed);
    int offset = snprintf(json, size,
        "{\"schema\":\"nulls-battlelog-trace.v1\",\"active\":%s,\"armed\":%s,"
        "\"hooks\":{\"msg\":%s,\"replay\":%s,\"parse\":%s},\"events\":%llu,\"messages\":%u,"
        "\"payload_slots\":%u,\"maps\":%d,\"messages_ring\":[",
        g_trace_active.load(std::memory_order_relaxed) ? "true" : "false",
        g_trace_armed.load(std::memory_order_relaxed) ? "true" : "false",
        g_trace_msg_dispatch_ready ? "true" : "false",
        g_trace_replay_set_ready ? "true" : "false",
        g_trace_json_parse_ready ? "true" : "false",
        static_cast<unsigned long long>(total), messages,
        g_trace_payload_next.load(std::memory_order_relaxed),
        g_map_count[g_map_active.load(std::memory_order_relaxed)].load(std::memory_order_relaxed));

    const uint32_t first_message = messages > kMessageRingCapacity ? messages - kMessageRingCapacity : 0;
    bool first = true;
    for (uint32_t index = first_message; index < messages && offset < size - 256; ++index) {
        const TraceMessage &message = g_trace_messages[index % kMessageRingCapacity];
        offset += snprintf(json + offset, size - offset,
            "%s{\"seq\":%llu,\"ms\":%llu,\"type\":%u,\"caller\":%u}",
            first ? "" : ",",
            static_cast<unsigned long long>(message.sequence),
            static_cast<unsigned long long>(message.at_ms),
            message.type, message.caller);
        first = false;
    }

    offset += snprintf(json + offset, size - offset, "],\"ring\":[");
    const uint64_t start = total > max_entries ? total - max_entries : 0;
    first = true;
    for (uint64_t sequence = start; sequence < total && offset < size - 256; ++sequence) {
        const TraceEntry &entry = g_trace_ring[sequence % kRingCapacity];
        if (entry.sequence != sequence) continue;
        offset += snprintf(json + offset, size - offset,
            "%s{\"seq\":%llu,\"ms\":%llu,\"kind\":%u,\"value\":%u,\"extra\":%llu",
            first ? "" : ",",
            static_cast<unsigned long long>(entry.sequence),
            static_cast<unsigned long long>(entry.at_ms),
            entry.kind, entry.value,
            static_cast<unsigned long long>(entry.extra));
        if (entry.text[0] != 0) {
            offset += snprintf(json + offset, size - offset, ",\"text\":\"");
            offset = trace_json_escape(json, offset, size, entry.text);
            offset += snprintf(json + offset, size - offset, "\"");
        }
        offset += snprintf(json + offset, size - offset, "}");
        first = false;
    }

    offset += snprintf(json + offset, size - offset, "],\"callers\":[");
    first = true;
    for (int i = 0; i < kHistogramSlots && offset < size - 128; ++i) {
        const uint32_t caller = g_trace_histogram[i].caller.load(std::memory_order_relaxed);
        const uint32_t count = g_trace_histogram[i].count.load(std::memory_order_relaxed);
        if (caller == 0 || count == 0) continue;
        offset += snprintf(json + offset, size - offset,
            "%s{\"caller\":%u,\"count\":%u}", first ? "" : ",", caller, count);
        first = false;
    }

    offset += snprintf(json + offset, size - offset, "],\"payloads\":[");
    first = true;
    for (int i = 0; i < kPayloadSlots && offset < size - 128; ++i) {
        const uint32_t length = g_trace_payloads[i].length.load(std::memory_order_relaxed);
        if (length == 0) continue;
        offset += snprintf(json + offset, size - offset,
            "%s{\"slot\":%d,\"length\":%u,\"origin\":%u,\"truncated\":%s,\"seq\":%llu}",
            first ? "" : ",", i, length,
            g_trace_payloads[i].origin.load(std::memory_order_relaxed),
            g_trace_payloads[i].truncated.load(std::memory_order_relaxed) ? "true" : "false",
            static_cast<unsigned long long>(g_trace_payloads[i].sequence.load(std::memory_order_relaxed)));
        first = false;
    }

    offset += snprintf(json + offset, size - offset, "],\"message_dumps\":[");
    first = true;
    const uint32_t used = g_trace_message_dump_count.load(std::memory_order_relaxed);
    for (uint32_t i = 0; i < used && i < kMessageDumpSlots && offset < size - 128; ++i) {
        const MessageDump &slot = g_trace_message_dumps[i];
        offset += snprintf(json + offset, size - offset,
            "%s{\"type\":%u,\"length\":%u,\"hits\":%u,\"children\":%u}",
            first ? "" : ",", slot.type.load(std::memory_order_relaxed),
            slot.length.load(std::memory_order_relaxed),
            slot.hits.load(std::memory_order_relaxed),
            slot.child_count.load(std::memory_order_relaxed));
        first = false;
    }
    offset += snprintf(json + offset, size - offset, "]}\n");
    return offset;
}

/** One payload slot, base64 encoded, so binary bytes survive the text protocol. */
inline int trace_payload_base64(char *json, int size, int slot_index) {
    if (slot_index < 0 || slot_index >= kPayloadSlots) {
        return snprintf(json, size, "{\"ok\":false,\"error\":\"slot\"}\n");
    }
    PayloadSlot &slot = g_trace_payloads[slot_index];
    const uint32_t length = slot.length.load(std::memory_order_acquire);
    int offset = snprintf(json, size,
        "{\"schema\":\"nulls-battlelog-payload.v1\",\"slot\":%d,\"length\":%u,\"truncated\":%s,\"data\":\"",
        slot_index, length,
        slot.truncated.load(std::memory_order_relaxed) ? "true" : "false");
    offset = trace_base64_into(json, offset, size,
        reinterpret_cast<const unsigned char *>(slot.data), length);
    offset += snprintf(json + offset, size - offset, "\"}\n");
    return offset;
}

/** The object snapshot and its one-level children for one message type. */
inline int trace_message_dump_base64(char *json, int size, uint32_t type) {
    const uint32_t used = g_trace_message_dump_count.load(std::memory_order_acquire);
    for (uint32_t index = 0; index < used && index < kMessageDumpSlots; ++index) {
        MessageDump &slot = g_trace_message_dumps[index];
        if (slot.type.load(std::memory_order_relaxed) != type) continue;
        const uint32_t length = slot.length.load(std::memory_order_acquire);
        int offset = snprintf(json, size,
            "{\"schema\":\"nulls-battlelog-msgdump.v2\",\"type\":%u,\"length\":%u,\"hits\":%u,\"data\":\"",
            type, length, slot.hits.load(std::memory_order_relaxed));
        offset = trace_base64_into(json, offset, size,
            reinterpret_cast<const unsigned char *>(slot.data), length);
        offset += snprintf(json + offset, size - offset, "\",\"children\":[");
        const uint32_t children = slot.child_count.load(std::memory_order_acquire);
        for (uint32_t child = 0; child < children && child < kChaseChildren; ++child) {
            const uint32_t childLength = slot.child_lengths[child].load(std::memory_order_acquire);
            offset += snprintf(json + offset, size - offset,
                "%s{\"address\":%llu,\"length\":%u,\"data\":\"",
                child == 0 ? "" : ",",
                static_cast<unsigned long long>(slot.child_addresses[child].load(std::memory_order_relaxed)),
                childLength);
            offset = trace_base64_into(json, offset, size,
                reinterpret_cast<const unsigned char *>(slot.children[child]), childLength);
            offset += snprintf(json + offset, size - offset, "\"}");
        }
        offset += snprintf(json + offset, size - offset, "]}\n");
        return offset;
    }
    return snprintf(json, size, "{\"ok\":false,\"error\":\"type-not-captured\"}\n");
}

} // namespace cr_battlelog
