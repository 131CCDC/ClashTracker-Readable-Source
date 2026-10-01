// Pre-application timing + field-discovery trace.
//
// Round 2 of the ghost-drop investigation.  Round 1 proved the structural
// window (T_entity - T_semantic = 338-788 ms, exec_tick = server_tick + 20,
// 16/16 exact) but failed to read the payload: the CardSelection pointer was
// fetched as 32 bits and cmd+0x14/+0x18 did not hold the target point.
//
// So this build stops assuming offsets.  For every command it records the raw
// object image before and after the decoder runs and lets the host diff it:
//
//   0xd2bc0c  LogicCommand::read(cmd, reader)   base reader  -> T_decode
//   0xd3128c  PlayCard::read(cmd, reader)       card reader  -> T_semantic
//   0xd2eef0  CommandManager::enqueue(cm, &cmd) -> T_apply (+ owner, both seats)
//   0x1064a08 GameStateManager::applyServerState
//   0xd2f37c  CommandManager pump              -> queue length when non-empty
//   0xf25b8c  object add (existing hook)       -> T_entity
//
// Every pointer is handled as uintptr_t/uint64_t.  Nothing writes game state.
#include <sys/syscall.h>

namespace cr_semantic {

constexpr int kDumpQwords = 11;   // 0x00..0x57: the whole play-card object
constexpr int kLineBytes = 1600;

constexpr const char *kTracePaths[] = {
    "/data/data/nullsroyale.rel.free/files/semantic-trace.jsonl",
    "/sdcard/Android/data/nullsroyale.rel.free/files/semantic-trace.jsonl",
};

FILE *g_trace_file = nullptr;
pthread_mutex_t g_trace_mutex = PTHREAD_MUTEX_INITIALIZER;
uint64_t g_trace_lines = 0, g_trace_events = 0;
int g_trace_errors = 0;
bool g_trace_ready = false;
bool g_identity_logged = false;

int64_t monotonic_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

long current_tid() { return static_cast<long>(syscall(__NR_gettid)); }

void trace_raw(const char *tag, const char *path, const char *fields, int32_t battle_tick);

void trace_open() {
    for (const char *path : kTracePaths) {
        FILE *handle = fopen(path, "a");
        if (!handle) continue;
        g_trace_file = handle;
        g_trace_ready = true;
        trace_raw("trace_open", path, nullptr, 0);
        LOGI("semantic trace writing to %s", path);
        return;
    }
    LOGI("semantic trace could not open a log file; tracing disabled");
}

void trace_raw(const char *tag, const char *path, const char *fields, int32_t battle_tick) {
    if (!g_trace_ready || !g_trace_file) return;
    char line[kLineBytes];
    int offset = snprintf(line, sizeof(line),
        "{\"tag\":\"%s\",\"ns\":%lld,\"pid\":%d,\"tid\":%ld,\"bs\":%d",
        tag, static_cast<long long>(monotonic_ns()), static_cast<int>(getpid()),
        current_tid(), battle_tick);
    if (path) offset += snprintf(line + offset, sizeof(line) - offset, ",\"path\":\"%s\"", path);
    if (fields && *fields) offset += snprintf(line + offset, sizeof(line) - offset, ",%s", fields);
    if (offset > 0 && offset < int(sizeof(line)) - 2) {
        line[offset++] = '}';
        line[offset++] = '\n';
        line[offset] = 0;
        pthread_mutex_lock(&g_trace_mutex);
        if (fwrite(line, 1, size_t(offset), g_trace_file) == size_t(offset)) {
            ++g_trace_lines;
            ++g_trace_events;
        } else {
            ++g_trace_errors;
        }
        fflush(g_trace_file);
        pthread_mutex_unlock(&g_trace_mutex);
    }
}

int32_t trace_battle_tick() {
    void *manager = g_live_manager.load();
    if (!manager || !g_game_state_tick) return -1;
    int32_t tick = g_game_state_tick(manager);
    return tick >= 0 ? tick : -1;
}

// Raw object image, one hex qword per 8 bytes.  Pointer-sized on purpose.
void dump_region(const void *object, uint64_t (&out)[kDumpQwords]) {
    for (int i = 0; i < kDumpQwords; ++i) {
        out[i] = 0;
        safe_field(object, size_t(i) * 8, out[i]);
    }
}

void format_region(const uint64_t (&in)[kDumpQwords], char *out, size_t size) {
    int used = 0;
    for (int i = 0; i < kDumpQwords && used < int(size) - 20; ++i)
        used += snprintf(out + used, size - used, "%s%llx", i ? "," : "",
                         static_cast<unsigned long long>(in[i]));
}

uint64_t account_id_of(void *world, int owner, bool &ok) {
    void *identity = nullptr;
    uint32_t high = 0, low = 0;
    ok = world && safe_field(world, 0x30 + 8 * size_t(owner), identity) &&
        is_valid_pointer(identity) && safe_field(identity, 0, high) && safe_field(identity, 4, low) &&
        (high || low);
    return ok ? ((static_cast<uint64_t>(high) << 32) | low) : 0;
}

void format_identity(char *out, size_t size, void *world) {
    bool ok0 = false, ok1 = false;
    uint64_t a0 = account_id_of(world, 0, ok0), a1 = account_id_of(world, 1, ok1);
    snprintf(out, size, "\"acct0\":%llu,\"acct1\":%llu,\"acct_known\":%s",
             static_cast<unsigned long long>(a0), static_cast<unsigned long long>(a1),
             (ok0 && ok1) ? "true" : "false");
}

// ===========================================================================
// Ghost Drop event source
// ===========================================================================
//
// The reverse-engineered offsets live only here.  A single, stable, tiny event
// is handed to the HUD:
//
//   { issuer_account_id, card_id, target x/y, server_tick, exec_tick, ... }
//
// Ownership is deliberately NOT decided here: the probe publishes the issuer
// account id and both seat account ids, and the HUD compares them against its
// own configured self account.  Seats swap between matches, so a "seat 0 is the
// enemy" rule would be wrong half the time.
struct GhostRecord {
    uint64_t sequence = 0;
    int issuer = 0;
    int card_id = 0;
    int x = 0, y = 0;
    int server_tick = -1, exec_tick = -1, semantic_tick = -1;
    int64_t semantic_ms = 0;
    uint64_t command_seq = 0;
    bool valid = false;
};

constexpr int kGhostRing = 32;
constexpr int64_t kGhostMaxAgeMs = 2500;
constexpr int kGhostTickSlack = 2;
GhostRecord g_ghosts[kGhostRing];
pthread_mutex_t g_ghost_mutex = PTHREAD_MUTEX_INITIALIZER;
uint64_t g_ghost_sequence = 0;
uint64_t g_ghost_published = 0, g_ghost_rejected = 0;
// The local client's own PlayCard command does not carry a CardSelection, so an
// enqueue with a play-card vtable but no resolvable selection names the seat
// that submitted locally.  Published as a hint only; the HUD still prefers its
// configured account id.
int g_self_account_hint = 0;

void ghost_publish(int issuer, int card_id, int x, int y, int server_tick, int exec_tick,
                   int semantic_tick, uint64_t command_seq) {
    if (issuer == 0 || card_id == 0) return;
    pthread_mutex_lock(&g_ghost_mutex);
    GhostRecord *slot = &g_ghosts[g_ghost_sequence % kGhostRing];
    *slot = GhostRecord{};
    slot->sequence = ++g_ghost_sequence;
    slot->issuer = issuer;
    slot->card_id = card_id;
    slot->x = x;
    slot->y = y;
    slot->server_tick = server_tick;
    slot->exec_tick = exec_tick;
    slot->semantic_tick = semantic_tick;
    slot->semantic_ms = monotonic_ns() / 1000000;
    slot->command_seq = command_seq;
    slot->valid = true;
    ++g_ghost_published;
    pthread_mutex_unlock(&g_ghost_mutex);
}

void ghost_note_self_hint(int issuer) {
    if (issuer == 0) return;
    pthread_mutex_lock(&g_ghost_mutex);
    if (g_self_account_hint == 0) g_self_account_hint = issuer;
    pthread_mutex_unlock(&g_ghost_mutex);
}

// Resolve one already-decoded PlayCard command image and feed the ring.
//
// This must run AFTER the decoder has populated the object.  Measured on a live
// friendly match (2026-09-20, 142 play-card commands): at CommandManager::enqueue
// time the object has the right vtable and server tick but `sel == 0`,
// `card_id == 0`, `owner == -1`, `exec_tick == -1` and no identity row was ever
// produced, i.e. the world lookup behind the enqueue hook's first argument does
// not resolve either.  Publishing from there yields zero events.  The decoder
// hooks are the call sites where the round-2/3 before/after diff proved the
// payload exists, and where 4-15 ticks (200-750 ms) of lead remain.
void ghost_publish_from_region(const uint64_t *region, void *command) {
    if (region == nullptr || command == nullptr) return;
    // Same world path ghost_json() uses; the hook arguments are not the manager.
    void *manager = g_live_manager.load();
    void *world = nullptr;
    if (manager) safe_field(manager, 0xa8, world);

    // Locate the CardSelection without assuming an offset: the first qword that
    // is a valid pointer to one of the two decks' card data is, by construction,
    // the selection's data pointer.
    int owner = -1, deck_slot = -1, selection_qword = -1;
    uint64_t selection = 0;
    uint32_t packed = 0;
    for (int qword = 0; qword < kDumpQwords && owner < 0; ++qword) {
        void *candidate = reinterpret_cast<void *>(static_cast<uintptr_t>(region[qword]));
        if (!is_valid_pointer(candidate)) continue;
        for (int slot_index = 0; slot_index < 8; ++slot_index) {
            int resolved = cr_command_queue::command_owner(world, slot_index, candidate);
            if (resolved < 0) continue;
            owner = resolved;
            deck_slot = slot_index;
            selection = region[qword];
            selection_qword = qword;
            break;
        }
    }
    // Static layout: selection data, secondary, then the packed word, so the
    // packed value is two qwords past the data pointer.  Read it only once the
    // pointer itself has been proved by deck identity.
    if (selection_qword >= 0 && selection_qword + 2 < kDumpQwords) {
        packed = uint32_t(region[selection_qword + 2] & 0xffffffffu);
        int packed_slot = int((packed >> 22) & 0x3f) - 1;
        if (deck_slot < 0) deck_slot = packed_slot;
        else if (packed_slot != deck_slot) deck_slot = -2;  // contradiction: report, do not pick
    }
    int server_tick = -1;
    safe_field(command, 0x10, server_tick);
    // Semantic deployment anchor, confirmed by the round-2/3 before-after diff.
    int target_x = 0, target_y = 0;
    safe_field(command, 0x28, target_x);
    safe_field(command, 0x2c, target_y);
    int card_id = 0;
    if (selection) {
        void *data = reinterpret_cast<void *>(static_cast<uintptr_t>(selection));
        if (is_valid_pointer(data)) safe_field(data, 0x40, card_id);
    }
    int issuer = int(uint32_t(region[3] & 0xffffffffu));
    uint64_t command_seq = region[10] & 0xffffffffu;

    if (issuer != 0 && card_id > 0 && server_tick > 0) {
        // `owner` is not required: the world pointer behind the deck-identity
        // scan is intermittently unresolvable even mid-battle (GET reports
        // players=[] in the same instant), and the HUD decides ownership from
        // the issuer account anyway.  Never let a flaky world drop an event.
        ghost_publish(issuer, card_id, target_x, target_y, server_tick, server_tick + 20,
                      trace_battle_tick(), command_seq);
    } else if (issuer != 0 && card_id == 0) {
        // A locally submitted command has no semantic selection to read.
        ghost_note_self_hint(issuer);
        pthread_mutex_lock(&g_ghost_mutex);
        ++g_ghost_rejected;
        pthread_mutex_unlock(&g_ghost_mutex);
    } else {
        pthread_mutex_lock(&g_ghost_mutex);
        ++g_ghost_rejected;
        pthread_mutex_unlock(&g_ghost_mutex);
    }
}

void ghost_publish_from_command(void *command) {
    if (!is_valid_pointer(command)) return;
    uint64_t region[kDumpQwords];
    dump_region(command, region);
    ghost_publish_from_region(region, command);
}

// Serialise the live ring for the GHOST command.  `tick` drops entries the
// engine has already executed; the age bound covers disconnect/reconnect.
void ghost_json(char *json, size_t size, int tick) {
    int64_t now_ms = monotonic_ns() / 1000000;
    int acct0 = 0, acct1 = 0;
    void *manager = g_live_manager.load();
    if (manager) {
        void *world = nullptr;
        if (safe_field(manager, 0xa8, world)) {
            bool ok = false;
            acct0 = int(account_id_of(world, 0, ok));
            acct1 = int(account_id_of(world, 1, ok));
        }
    }
    int offset = snprintf(json, size,
        "{\"schema\":\"nulls-ghost.v1\",\"in_battle\":%s,\"tick\":%d,\"monotonic_ms\":%lld,"
        "\"acct0\":%d,\"acct1\":%d,\"self_hint\":%d,\"events\":[",
        g_in_battle.load() ? "true" : "false", tick, static_cast<long long>(now_ms),
        acct0, acct1, g_self_account_hint);
    pthread_mutex_lock(&g_ghost_mutex);
    bool first = true;
    for (int i = 0; i < kGhostRing; ++i) {
        const GhostRecord &record = g_ghosts[i];
        if (!record.valid) continue;
        if (tick >= 0 && record.exec_tick >= 0 && tick >= record.exec_tick + kGhostTickSlack) continue;
        if (record.semantic_ms && now_ms - record.semantic_ms > kGhostMaxAgeMs) continue;
        if (offset < 0 || size_t(offset) + 300 >= size) break;
        offset += snprintf(json + offset, size - offset,
            "%s{\"sequence\":%llu,\"issuer\":%d,\"card_id\":%d,\"x\":%d,\"y\":%d,"
            "\"server_tick\":%d,\"exec_tick\":%d,\"semantic_tick\":%d,\"semantic_ms\":%lld,"
            "\"command_sequence\":%llu}",
            first ? "" : ",", static_cast<unsigned long long>(record.sequence), record.issuer,
            record.card_id, record.x, record.y, record.server_tick, record.exec_tick,
            record.semantic_tick, static_cast<long long>(record.semantic_ms),
            static_cast<unsigned long long>(record.command_seq));
        first = false;
    }
    pthread_mutex_unlock(&g_ghost_mutex);
    snprintf(json + offset, size - offset, "],\"published\":%llu,\"rejected\":%llu}\n",
             static_cast<unsigned long long>(g_ghost_published),
             static_cast<unsigned long long>(g_ghost_rejected));
}

// --- LogicCommand::read(cmd, reader) : base decoder ------------------------
using CommandReadFn = void (*)(void *, void *);
CommandReadFn g_command_read_original = nullptr;

void command_read_hook(void *command, void *reader) {
    uint64_t before[kDumpQwords], after[kDumpQwords];
    dump_region(command, before);
    g_command_read_original(command, reader);
    dump_region(command, after);
    uintptr_t vtable = 0;
    safe_field(command, 0, vtable);
    char region_before[kDumpQwords * 20], region_after[kDumpQwords * 20], fields[1024];
    format_region(before, region_before, sizeof(region_before));
    format_region(after, region_after, sizeof(region_after));
    snprintf(fields, sizeof(fields),
        "\"cmd\":%llu,\"vt\":%llu,\"reader\":%llu,\"before\":\"%s\",\"after\":\"%s\"",
        static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(command)),
        static_cast<unsigned long long>(vtable ? vtable - g_libg_base : 0),
        static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(reader)),
        region_before, region_after);
    trace_raw("cmd_read_diff", nullptr, fields, trace_battle_tick());
}

// --- PlayCard::read(cmd, reader) : card payload decoder --------------------
using PlayCardReadFn = void (*)(void *, void *);
PlayCardReadFn g_playcard_read_original = nullptr;

void playcard_read_hook(void *command, void *reader) {
    uint64_t before[kDumpQwords], after[kDumpQwords];
    dump_region(command, before);
    g_playcard_read_original(command, reader);
    dump_region(command, after);
    char region_before[kDumpQwords * 20], region_after[kDumpQwords * 20], fields[1024];
    format_region(before, region_before, sizeof(region_before));
    format_region(after, region_after, sizeof(region_after));
    snprintf(fields, sizeof(fields),
        "\"cmd\":%llu,\"before\":\"%s\",\"after\":\"%s\"", 
        static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(command)),
        region_before, region_after);
    trace_raw("playcard_read_diff", nullptr, fields, trace_battle_tick());
    // Ghost Drop source: only here is the object decoded, so only here does the
    // CardSelection, the deck identity and the execution tick actually exist.
    ghost_publish_from_region(after, command);
}

// --- CommandManager::enqueue(cm, &cmd) : apply + full payload --------------
using EnqueueFn = void (*)(void *, void *);
EnqueueFn g_enqueue_original = nullptr;

void enqueue_hook(void *manager, void *slot) {
    void *command = nullptr;
    if (!safe_field(slot, 0, command) || !is_valid_pointer(command)) {
        g_enqueue_original(manager, slot);
        return;
    }
    uintptr_t vtable = 0;
    uint64_t region[kDumpQwords];
    dump_region(command, region);
    safe_field(command, 0, vtable);
    void *world = nullptr;
    if (is_valid_pointer(manager)) safe_field(manager, 0xa8, world);
    bool known0 = false, known1 = false;
    uint64_t seat0 = account_id_of(world, 0, known0), seat1 = account_id_of(world, 1, known1);

    // Locate the CardSelection without assuming an offset: the first qword in
    // the live image that is a valid pointer to one of the two decks' card data
    // is, by construction, the selection's data pointer.
    int owner = -1, deck_slot = -1, selection_qword = -1;
    uint64_t selection = 0;
    uint32_t packed = 0;
    for (int qword = 0; qword < kDumpQwords && owner < 0; ++qword) {
        void *candidate = reinterpret_cast<void *>(static_cast<uintptr_t>(region[qword]));
        if (!is_valid_pointer(candidate)) continue;
        for (int slot_index = 0; slot_index < 8; ++slot_index) {
            int resolved = cr_command_queue::command_owner(world, slot_index, candidate);
            if (resolved < 0) continue;
            owner = resolved;
            deck_slot = slot_index;
            selection = region[qword];
            selection_qword = qword;
            break;
        }
    }
    // Static layout: selection data, secondary, then the packed word, so the
    // packed value is two qwords past the data pointer.  Read it only once the
    // pointer itself has been proved by deck identity.
    if (selection_qword >= 0 && selection_qword + 2 < kDumpQwords) {
        packed = uint32_t(region[selection_qword + 2] & 0xffffffffu);
        int packed_slot = int((packed >> 22) & 0x3f) - 1;
        if (deck_slot < 0) deck_slot = packed_slot;
        else if (packed_slot != deck_slot) deck_slot = -2;  // contradiction: report, do not pick
    }
    int server_tick = -1, exec_tick = -1;
    safe_field(command, 0x10, server_tick);
    safe_field(command, 0x0c, exec_tick);
    int card_id = 0;
    if (selection) {
        void *data = reinterpret_cast<void *>(static_cast<uintptr_t>(selection));
        if (is_valid_pointer(data)) safe_field(data, 0x40, card_id);
    }
    int32_t tick = trace_battle_tick();
    char identity[192], region_text[kDumpQwords * 20], fields[1200];
    format_identity(identity, sizeof(identity), world);
    format_region(region, region_text, sizeof(region_text));
    snprintf(fields, sizeof(fields),
        "\"cmd\":%llu,\"vt\":%llu,\"owner\":%d,\"deck_slot\":%d,\"card_id\":%d,\"sel\":%llu,"
        "\"packed\":%u,\"server_tick\":%d,\"exec_tick\":%d,\"tick_delta\":%d,\"seat0\":%llu,\"seat1\":%llu,"
        "\"image\":\"%s\"",
        static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(command)),
        static_cast<unsigned long long>(vtable ? vtable - g_libg_base : 0),
        owner, deck_slot, card_id,
        static_cast<unsigned long long>(selection), packed, server_tick, exec_tick,
        exec_tick >= 0 ? exec_tick - tick : -1,
        static_cast<unsigned long long>(seat0), static_cast<unsigned long long>(seat1),
        region_text);
    trace_raw("enqueue", nullptr, fields, tick);
    if (!g_identity_logged && world) {
        g_identity_logged = true;
        trace_raw("identity", nullptr, identity, tick);
    }
    // No ghost publish here on purpose: at enqueue time the object still has
    // sel == 0 / card_id == 0 / exec_tick == -1 and this hook's first argument
    // does not resolve to the CommandManager.  The publisher runs from
    // playcard_read_hook, after the decoder has filled the payload.
    g_enqueue_original(manager, slot);
}

// --- CommandManager pump: the authoritative queue length -------------------
using PumpFn = void (*)(void *);
PumpFn g_pump_original = nullptr;

void pump_hook(void *manager) {
    int capacity = -1, count = -1;
    void *data = nullptr;
    safe_field(manager, 0x10, capacity);
    safe_field(manager, 0x14, count);
    safe_field(manager, 0x08, data);
    if (count > 0) {
        int first_exec = -1, first_server = -1;
        void *first = nullptr;
        if (safe_field(data, 0, first) && is_valid_pointer(first)) {
            safe_field(first, 0x0c, first_exec);
            safe_field(first, 0x10, first_server);
        }
        int32_t tick = trace_battle_tick();
        char fields[256];
        snprintf(fields, sizeof(fields),
            "\"count\":%d,\"capacity\":%d,\"tick\":%d,\"first_exec\":%d,\"first_server\":%d,"
            "\"first_delta\":%d",
            count, capacity, tick, first_exec, first_server,
            first_exec >= 0 ? first_exec - tick : -1);
        trace_raw("queue_nonempty", nullptr, fields, tick);
    }
    g_pump_original(manager);
}

// --- ApplyServerState ------------------------------------------------------
using ApplyStateFn = void (*)(void *, int, int, void *);
ApplyStateFn g_apply_state_original = nullptr;

void apply_state_hook(void *manager, int server_tick, int flag, void *batch) {
    int32_t tick = trace_battle_tick();
    char fields[256];
    snprintf(fields, sizeof(fields),
        "\"server_tick\":%d,\"arg2\":%d,\"arg3\":%llu",
        server_tick, flag, static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(batch)));
    trace_raw("apply_state", nullptr, fields, tick);
    g_apply_state_original(manager, server_tick, flag, batch);
}

// --- entity add (called from the deployment spawn hook) --------------------
void trace_entity(void *object, uint32_t id, int owner, int commit) {
    int card_id = 0, x = 0, y = 0;
    safe_field(object, 0xac, card_id);
    safe_field(object, 0x7c, x);
    safe_field(object, 0x80, y);
    char fields[288];
    snprintf(fields, sizeof(fields),
             "\"obj\":%llu,\"entity\":%u,\"owner\":%d,\"commit\":%d,\"card_id\":%d,\"x\":%d,\"y\":%d",
             static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(object)), id, owner, commit,
             card_id, x, y);
    trace_raw(commit ? "entity_live" : "entity_queue", nullptr, fields, trace_battle_tick());
}

bool install_semantic_trace() {
    trace_open();
    const uint8_t read_prologue[16] = {0xfd, 0x7b, 0xbe, 0xa9, 0xf4, 0x4f, 0x01, 0xa9,
                                       0xfd, 0x03, 0x00, 0x91, 0x28, 0x00, 0x40, 0xf9};
    const uint8_t playcard_prologue[16] = {0xff, 0x03, 0x01, 0xd1, 0xfd, 0x7b, 0x02, 0xa9,
                                           0xf4, 0x4f, 0x03, 0xa9, 0xfd, 0x83, 0x00, 0x91};
    const uint8_t enqueue_prologue[16] = {0x28, 0x00, 0x40, 0xf9, 0xe8, 0x05, 0x00, 0xb4,
                                          0xfd, 0x7b, 0xbe, 0xa9, 0xf4, 0x4f, 0x01, 0xa9};
    const uint8_t apply_prologue[16] = {0x08, 0xbc, 0x41, 0xb9, 0x1f, 0x01, 0x01, 0x6b,
                                        0x4c, 0x2c, 0x00, 0x54, 0xff, 0x03, 0x02, 0xd1};
    const uint8_t pump_prologue[16] = {0xff, 0xc3, 0x03, 0xd1, 0xfd, 0x7b, 0x09, 0xa9,
                                       0xfc, 0x6f, 0x0a, 0xa9, 0xfa, 0x67, 0x0b, 0xa9};
    return install_inline_hook(g_libg_base + 0xd2bc0c, read_prologue, 16,
               reinterpret_cast<void *>(command_read_hook),
               reinterpret_cast<void **>(&g_command_read_original), "semantic command read") &&
        install_inline_hook(g_libg_base + 0xd3128c, playcard_prologue, 16,
               reinterpret_cast<void *>(playcard_read_hook),
               reinterpret_cast<void **>(&g_playcard_read_original), "semantic playcard read") &&
        install_inline_hook(g_libg_base + 0xd2eef0, enqueue_prologue, 16,
               reinterpret_cast<void *>(enqueue_hook),
               reinterpret_cast<void **>(&g_enqueue_original), "semantic enqueue") &&
        install_inline_hook(g_libg_base + 0x1064a08, apply_prologue, 16,
               reinterpret_cast<void *>(apply_state_hook),
               reinterpret_cast<void **>(&g_apply_state_original), "semantic apply state") &&
        install_inline_hook(g_libg_base + 0xd2f37c, pump_prologue, 16,
               reinterpret_cast<void *>(pump_hook),
               reinterpret_cast<void **>(&g_pump_original), "semantic queue pump");
}

void append_semantic_diagnostics(char *json, int &offset, size_t size) {
    offset += snprintf(json + offset, size - offset,
        ",\"semantic_trace\":{\"ready\":%s,\"lines\":%llu,\"errors\":%d}",
        g_trace_ready ? "true" : "false",
        static_cast<unsigned long long>(g_trace_lines), g_trace_errors);
}

} // namespace cr_semantic
