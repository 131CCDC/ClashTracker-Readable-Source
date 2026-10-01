// Read-only view of the battle LogicCommand queue.
//
// Attested for the pinned Null's Royale 15.535.13 libg build
// (SHA-256 110aa2b5cac391c498645e072b0d88729428c2c2845e7e2737ca8ee979059783).
// Every offset below was read out of that binary, not guessed:
//
//   addr                        what
//   GameStateManager+0x38       CommandManager*          (loaded at 0x1062374)
//   CommandManager+0x08/+0x10/+0x14
//                               packed vector {data, capacity, count}
//                               (appended by 0xd2eef0, pumped by 0xd2f37c)
//   CommandManager+0x20         owning GameStateManager (identity check)
//   LogicCommand+0x00           vtable
//   LogicCommand+0x0c           execute tick    (getter 0xd2bbb4, setter 0xd2bbac)
//   LogicCommand+0x10           issuing server tick (getter 0xd2bc8c)
//   LogicCommand+0x14/+0x18     target IntPoint  (read by 0x1161890, x() 0x121aa88)
//   LogicCommand+0x38/+0x40/+0x48
//                               CardSelection {data, secondary, packed}
//                               (reader 0xe2cf20, setter 0xd311f0)
//
// GameStateManager::step calls the pump 0xd2f37c at [manager+0x38] once per
// tick.  The pump executes exactly the commands whose +0x0c equals the current
// manager tick, keeps later ones queued, and drops earlier ones with
// "Execute command failed! Command should have been executed already."  A
// command therefore observed here with exec_tick > tick has not created any
// object yet.  Nothing in this file writes game state or installs a hook: it
// only enumerates the queue from the snapshot the probe already builds.
namespace cr_command_queue {

// Type 0x56 is the attested "play a card at a position" command class: its
// vtable is written by the constructor at 0xd309a8, its execute (0xd30a00)
// funnels into the consume-card path 0xf38a68, and its reader (0xd3128c) pulls
// target position + card selection off the wire.  Anything else stays visible
// but unlabelled, so an unknown class can never be mistaken for a placement.
constexpr uintptr_t kPlayCardVtableOffset = 0x18776a0;
constexpr unsigned kPlayCardType = 0x56;
constexpr int kMaximumCommands = 2048;
constexpr int kMaximumReported = 24;
constexpr int kMaximumLeadTicks = 600; // 30 s at 50 ms/tick would be absurd

struct QueuedCommand {
    uintptr_t vtable_offset = 0;
    int exec_tick = -1;
    int server_tick = -1;
    int x = 0, y = 0;
    int owner = -1, deck_slot = -1, card_id = 0, selected_cost = -1;
    bool play_card = false;
};

int command_deck_slot(uint32_t packed) { return int((packed >> 22) & 0x3f) - 1; }
int command_selected_cost(uint32_t packed) { return int(packed >> 28); }

// Resolve the queue entry's CardSelection against both seats' decks.  The
// selection stores the deck slot's LogicCardData pointer, so the owner is
// proved by pointer identity instead of being assumed from field ranges.
int command_owner(void *world, int slot, void *card_data) {
    if (!world || slot < 0 || slot >= 8 || !card_data) return -1;
    for (int owner = 0; owner < 2; ++owner) {
        void *deck = nullptr, *slots = nullptr, *entry = nullptr, *data = nullptr;
        int capacity = 0, count = 0;
        if (!safe_field(world, 0x88 + 8 * owner, deck) ||
            !runtime_vector(deck, 0x20, 8, slots, count) || count != 8 ||
            !safe_field(slots, 8 * slot, entry) || !safe_field(entry, 0x10, data)) continue;
        if (data == card_data) return owner;
    }
    return -1;
}

bool read_queued_command(void *world, void *command, QueuedCommand &out) {
    uintptr_t vtable = 0;
    uint32_t selection_data = 0, packed = 0;
    if (!safe_field(command, 0, vtable) || !vtable) return false;
    out.vtable_offset = vtable - g_libg_base;
    if (!safe_field(command, 0x0c, out.exec_tick) || !safe_field(command, 0x10, out.server_tick) ||
        !safe_field(command, 0x14, out.x) || !safe_field(command, 0x18, out.y)) return false;
    out.play_card = out.vtable_offset == kPlayCardVtableOffset;
    if (!out.play_card) return true;
    if (!safe_field(command, 0x48, packed) || !safe_field(command, 0x38, selection_data)) return false;
    out.deck_slot = command_deck_slot(packed);
    out.selected_cost = command_selected_cost(packed);
    void *card_data = reinterpret_cast<void *>(static_cast<uintptr_t>(selection_data));
    if (!is_valid_pointer(card_data) || !safe_field(card_data, 0x40, out.card_id) || out.card_id <= 0) {
        out.card_id = 0;
        return true;
    }
    out.owner = command_owner(world, out.deck_slot, card_data);
    return true;
}

} // namespace cr_command_queue

// Emit every command still waiting for its execute tick.  `tick` is the current
// manager tick, so `lead_ticks = exec_tick - tick` is the amount of game time
// left before the command is allowed to create anything.
void append_command_queue(char *json, int &offset, size_t size, void *manager, void *world, int tick) {
    using namespace cr_command_queue;
    void *queue = nullptr, *owner = nullptr, *data = nullptr;
    int capacity = 0, count = 0;
    bool available = manager && safe_field(manager, 0x38, queue) && is_valid_pointer(queue) &&
        safe_field(queue, 0x20, owner) && owner == manager &&
        safe_field(queue, 0x08, data) && safe_field(queue, 0x10, capacity) &&
        safe_field(queue, 0x14, count) && count >= 0 && capacity >= count &&
        capacity <= kMaximumCommands && (!count || is_valid_pointer(data));
    if (!available) {
        offset += snprintf(json + offset, size - offset,
            ",\"command_queue_runtime\":{\"validated\":false,\"reason\":\"queue_unavailable\"}");
        return;
    }
    int pending = 0, reported = 0, minimum_lead = -1, play_card_pending = 0;
    offset += snprintf(json + offset, size - offset,
        ",\"command_queue_runtime\":{\"validated\":true,\"schema\":\"nulls-command-queue.v1\","
        "\"tick\":%d,\"count\":%d,\"capacity\":%d,\"commands\":[", tick, count, capacity);
    for (int index = 0; index < count && index < kMaximumCommands; ++index) {
        void *command = nullptr;
        if (!safe_field(data, index * sizeof(void *), command) || !is_valid_pointer(command)) continue;
        QueuedCommand entry;
        if (!read_queued_command(world, command, entry)) continue;
        if (entry.exec_tick < tick || entry.exec_tick - tick > kMaximumLeadTicks) continue;
        ++pending;
        if (entry.play_card) ++play_card_pending;
        if (minimum_lead < 0 || entry.exec_tick - tick < minimum_lead) minimum_lead = entry.exec_tick - tick;
        if (reported >= kMaximumReported || offset + 1024 >= int(size)) continue;
        ++reported;
        offset += snprintf(json + offset, size - offset,
            "%s{\"class\":\"%s\",\"type\":%u,\"vtable\":%llu,\"exec_tick\":%d,\"server_tick\":%d,"
            "\"lead_ticks\":%d,\"lead_ms\":%d,\"target_x\":%d,\"target_y\":%d,"
            "\"owner\":%d,\"deck_slot\":%d,\"card_id\":%d,\"selected_cost\":%d}",
            reported > 1 ? "," : "", entry.play_card ? "play_card" : "other",
            entry.play_card ? kPlayCardType : 0u,
            static_cast<unsigned long long>(entry.vtable_offset),
            entry.exec_tick, entry.server_tick, entry.exec_tick - tick, (entry.exec_tick - tick) * 50,
            entry.x, entry.y, entry.owner, entry.deck_slot, entry.card_id, entry.selected_cost);
    }
    offset += snprintf(json + offset, size - offset,
        "],\"pending\":%d,\"reported\":%d,\"play_card_pending\":%d,"
        "\"min_lead_ticks\":%d,\"min_lead_ms\":%d}",
        pending, reported, play_card_pending, minimum_lead,
        minimum_lead < 0 ? -1 : minimum_lead * 50);
}
