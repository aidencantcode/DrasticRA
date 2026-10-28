#include <jni.h>
#include <string>
#include <vector>
#include <deque>
#include <map>
#include <mutex>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <sys/socket.h>
#include <arpa/inet.h>

#include "rc_client.h"
#include "rc_consoles.h"
#include "rc_hash.h"

#define SEP "\037"

static const uint32_t RAM_SIZE = 0x400000;
static const uint32_t PAGE = 4096;
static const uint32_t NPAGES = RAM_SIZE / PAGE;

static rc_client_t* g_client = nullptr;
static int g_fd = -1;
static bool g_lost_sent = false;
static uint8_t g_ram[RAM_SIZE];
static bool g_have[NPAGES];
static bool g_used[NPAGES];
static std::string g_path;

struct Req { int id; std::string url, post, ctype; bool has_post; };
struct Pending { rc_client_server_callback_t cb; void* data; };
static std::mutex g_mu;
static std::deque<Req> g_reqs;
static std::map<int, Pending> g_pending;
static std::deque<std::string> g_events;
static int g_next_id = 1;

static const char* S(const char* s) { return s ? s : ""; }

static void push_event(const std::string& s) {
    std::lock_guard<std::mutex> l(g_mu);
    g_events.push_back(s);
}

/* ---------- socket to the root RAM server ---------- */
static bool write_all(const void* p, size_t n) {
    const char* c = (const char*)p;
    while (n) {
        ssize_t k = send(g_fd, c, n, MSG_NOSIGNAL);
        if (k <= 0) return false;
        c += k; n -= (size_t)k;
    }
    return true;
}
static bool read_all(void* p, size_t n) {
    char* c = (char*)p;
    while (n) {
        ssize_t k = recv(g_fd, c, n, 0);
        if (k <= 0) return false;
        c += k; n -= (size_t)k;
    }
    return true;
}
static void drop_fd() {
    if (g_fd >= 0) { close(g_fd); g_fd = -1; }
}

/* idx must be sorted ascending */
static bool fetch_pages(const std::vector<uint32_t>& idx) {
    if (g_fd < 0) return false;
    if (idx.empty()) return true;
    std::vector<uint8_t> msg(5 + 4 * idx.size());
    msg[0] = 'P';
    uint32_t n = htonl((uint32_t)idx.size());
    memcpy(&msg[1], &n, 4);
    for (size_t i = 0; i < idx.size(); i++) {
        uint32_t v = htonl(idx[i]);
        memcpy(&msg[5 + 4 * i], &v, 4);
    }
    if (!write_all(msg.data(), msg.size())) { drop_fd(); return false; }
    size_t i = 0;
    while (i < idx.size()) {
        size_t j = i;
        while (j + 1 < idx.size() && idx[j + 1] == idx[j] + 1) j++;
        size_t len = (j - i + 1) * PAGE;
        if (!read_all(g_ram + (size_t)idx[i] * PAGE, len)) { drop_fd(); return false; }
        for (size_t k = i; k <= j; k++) g_have[idx[k]] = true;
        i = j + 1;
    }
    return true;
}

/* ---------- rcheevos callbacks ---------- */
static uint32_t read_mem(uint32_t addr, uint8_t* buf, uint32_t n, rc_client_t*) {
    if (n == 0 || addr >= RAM_SIZE || n > RAM_SIZE - addr) return 0;
    uint32_t first = addr / PAGE, last = (addr + n - 1) / PAGE;
    for (uint32_t p = first; p <= last; p++) {
        if (!g_have[p]) {
            std::vector<uint32_t> one(1, p);
            if (!fetch_pages(one)) return 0;
        }
        g_used[p] = true;
    }
    memcpy(buf, g_ram + addr, n);
    return n;
}

static void server_call(const rc_api_request_t* request, rc_client_server_callback_t cb, void* data, rc_client_t*) {
    std::lock_guard<std::mutex> l(g_mu);
    Req r;
    r.id = g_next_id++;
    r.url = S(request->url);
    r.has_post = request->post_data != nullptr;
    r.post = S(request->post_data);
    r.ctype = S(request->content_type);
    g_pending[r.id] = Pending{cb, data};
    g_reqs.push_back(r);
}

static void on_event(const rc_client_event_t* e, rc_client_t*) {
    char buf[1536];
    switch (e->type) {
        case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED: {
            const rc_client_achievement_t* a = e->achievement;
            /* the id (last field) lets the app hold back a suspicious burst of unlocks */
            snprintf(buf, sizeof buf, "UNLOCK" SEP "%s" SEP "%s" SEP "%u" SEP "%s" SEP "%u",
                     S(a->title), S(a->description), (unsigned)a->points, S(a->badge_name), (unsigned)a->id);
            push_event(buf);
            break;
        }
#ifdef RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_SHOW
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_SHOW:
#ifdef RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_UPDATE
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_UPDATE:
#endif
        {
            const rc_client_achievement_t* a = e->achievement;
            snprintf(buf, sizeof buf, "PROGRESS" SEP "%s" SEP "%s" SEP "%s",
                     S(a->title), a->measured_progress, S(a->badge_name));
            push_event(buf);
            break;
        }
#endif
#ifdef RC_CLIENT_EVENT_GAME_COMPLETED
        case RC_CLIENT_EVENT_GAME_COMPLETED:
            push_event("MASTERED");
            break;
#endif
#ifdef RC_CLIENT_EVENT_SERVER_ERROR
        case RC_CLIENT_EVENT_SERVER_ERROR:
            push_event("SERVER_ERROR");
            break;
#endif
        default:
            break;
    }
}

static void login_cb(int result, const char* err, rc_client_t* c, void*) {
    if (result == RC_OK) {
        const rc_client_user_t* u = rc_client_get_user_info(c);
        std::string s = "LOGIN_OK" SEP;
        s += S(u->display_name); s += SEP; s += S(u->username); s += SEP; s += S(u->token);
        push_event(s);
    } else {
        push_event(std::string("LOGIN_ERR" SEP) + S(err));
    }
}

static void game_cb(int result, const char* err, rc_client_t* c, void*) {
    if (result == RC_OK) {
        const rc_client_game_t* g = rc_client_get_game_info(c);
        rc_client_user_game_summary_t sum;
        memset(&sum, 0, sizeof sum);
        rc_client_get_user_game_summary(c, &sum);
        char buf[1024];
        snprintf(buf, sizeof buf, "GAME_OK" SEP "%s" SEP "%u" SEP "%u" SEP "%u" SEP "%u" SEP "%s" SEP "%u",
                 S(g->title), (unsigned)sum.num_core_achievements, (unsigned)sum.num_unlocked_achievements,
                 (unsigned)sum.points_core, (unsigned)sum.points_unlocked, S(g->badge_name), (unsigned)g->id);
        push_event(buf);
    } else {
        push_event(std::string("GAME_ERR" SEP) + S(err));
    }
}

/* ---------- JNI ---------- */
static std::string str(JNIEnv* env, jstring js) {
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s = c ? c : "";
    if (c) env->ReleaseStringUTFChars(js, c);
    return s;
}

extern "C" {

JNIEXPORT jint JNICALL Java_com_drasticra_Native_create(JNIEnv*, jobject) {
    if (g_client) return 0;
    g_client = rc_client_create(read_mem, server_call);
    rc_client_set_event_handler(g_client, on_event);
    rc_client_set_hardcore_enabled(g_client, 0);
    return 0;
}

JNIEXPORT jstring JNICALL Java_com_drasticra_Native_userAgentClause(JNIEnv* env, jobject) {
    char b[128] = {0};
    if (g_client) rc_client_get_user_agent_clause(g_client, b, sizeof b);
    return env->NewStringUTF(b);
}

JNIEXPORT void JNICALL Java_com_drasticra_Native_login(JNIEnv* env, jobject, jstring u, jstring p) {
    std::string us = str(env, u), ps = str(env, p);
    rc_client_begin_login_with_password(g_client, us.c_str(), ps.c_str(), login_cb, nullptr);
}

JNIEXPORT void JNICALL Java_com_drasticra_Native_loginToken(JNIEnv* env, jobject, jstring u, jstring t) {
    std::string us = str(env, u), ts = str(env, t);
    rc_client_begin_login_with_token(g_client, us.c_str(), ts.c_str(), login_cb, nullptr);
}

JNIEXPORT void JNICALL Java_com_drasticra_Native_attach(JNIEnv*, jobject, jint fd) {
    g_fd = fd;
    g_lost_sent = false;
    memset(g_have, 0, sizeof g_have);
    memset(g_used, 0, sizeof g_used);
}

JNIEXPORT void JNICALL Java_com_drasticra_Native_loadGame(JNIEnv* env, jobject, jstring path) {
    g_path = str(env, path);
    rc_client_begin_identify_and_load_game(g_client, RC_CONSOLE_NINTENDO_DS, g_path.c_str(), nullptr, 0, game_cb, nullptr);
}

JNIEXPORT void JNICALL Java_com_drasticra_Native_doFrame(JNIEnv*, jobject) {
    if (!g_client) return;
    if (g_fd < 0) {
        if (!g_lost_sent) { g_lost_sent = true; push_event("LOST"); }
        return;
    }
    memset(g_have, 0, sizeof g_have);
    std::vector<uint32_t> idx;
    for (uint32_t p = 0; p < NPAGES; p++) if (g_used[p]) idx.push_back(p);
    fetch_pages(idx);
    rc_client_do_frame(g_client);
}

JNIEXPORT void JNICALL Java_com_drasticra_Native_idle(JNIEnv*, jobject) {
    if (g_client) rc_client_idle(g_client);
}

JNIEXPORT jobjectArray JNICALL Java_com_drasticra_Native_nextRequest(JNIEnv* env, jobject) {
    Req r;
    {
        std::lock_guard<std::mutex> l(g_mu);
        if (g_reqs.empty()) return nullptr;
        r = g_reqs.front();
        g_reqs.pop_front();
    }
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray a = env->NewObjectArray(5, sc, nullptr);
    env->SetObjectArrayElement(a, 0, env->NewStringUTF(std::to_string(r.id).c_str()));
    env->SetObjectArrayElement(a, 1, env->NewStringUTF(r.url.c_str()));
    env->SetObjectArrayElement(a, 2, env->NewStringUTF(r.post.c_str()));
    env->SetObjectArrayElement(a, 3, env->NewStringUTF(r.ctype.c_str()));
    env->SetObjectArrayElement(a, 4, env->NewStringUTF(r.has_post ? "1" : "0"));
    return a;
}

JNIEXPORT void JNICALL Java_com_drasticra_Native_respond(JNIEnv* env, jobject, jint id, jint status, jbyteArray body) {
    Pending p;
    {
        std::lock_guard<std::mutex> l(g_mu);
        auto it = g_pending.find(id);
        if (it == g_pending.end()) return;
        p = it->second;
        g_pending.erase(it);
    }
    jsize len = env->GetArrayLength(body);
    std::vector<char> data((size_t)len + 1, 0);
    if (len > 0) env->GetByteArrayRegion(body, 0, len, (jbyte*)data.data());
    rc_api_server_response_t resp;
    memset(&resp, 0, sizeof resp);
    resp.body = data.data();
    resp.body_length = (size_t)len;
    resp.http_status_code = status;
#ifdef RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR
    if (status <= 0) resp.http_status_code = RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR;
#endif
    p.cb(&resp, p.data);
}

JNIEXPORT jstring JNICALL Java_com_drasticra_Native_nextEvent(JNIEnv* env, jobject) {
    std::string s;
    {
        std::lock_guard<std::mutex> l(g_mu);
        if (g_events.empty()) return nullptr;
        s = g_events.front();
        g_events.pop_front();
    }
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT void JNICALL Java_com_drasticra_Native_shutdown(JNIEnv*, jobject) {
    if (g_client) {
        rc_client_unload_game(g_client);
        rc_client_destroy(g_client);
        g_client = nullptr;
    }
    drop_fd();
}

JNIEXPORT jint JNICALL Java_com_drasticra_Native_readHeader(JNIEnv* env, jobject, jbyteArray out) {
    if (g_fd < 0) return -1;
    char cmd = 'H';
    if (!write_all(&cmd, 1)) { drop_fd(); return -1; }
    uint8_t buf[1 + 0x200];
    if (!read_all(buf, sizeof buf)) { drop_fd(); return -1; }
    env->SetByteArrayRegion(out, 0, 0x200, (const jbyte*)(buf + 1));
    return buf[0] == 1 ? 1 : 0;
}

/*
 * Every core achievement of the loaded game with its live state, for the in-game overlay.
 * Records are separated by \036, fields by \037:
 * id, title, description, badge, points, unlocked(0/1), measured_progress, measured_percent, unlock_time, bucket
 */
JNIEXPORT jbyteArray JNICALL Java_com_drasticra_Native_achievementList(JNIEnv* env, jobject) {
    std::string out;
    if (g_client) {
        rc_client_achievement_list_t* list = rc_client_create_achievement_list(
            g_client, RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
        if (list) {
            char num[64];
            for (uint32_t b = 0; b < list->num_buckets; b++) {
                const rc_client_achievement_bucket_t* bucket = &list->buckets[b];
                for (uint32_t i = 0; i < bucket->num_achievements; i++) {
                    const rc_client_achievement_t* a = bucket->achievements[i];
                    if (!a) continue;
                    if (!out.empty()) out += "\036";
                    snprintf(num, sizeof num, "%u", (unsigned)a->id);
                    out += num; out += SEP;
                    out += S(a->title); out += SEP;
                    out += S(a->description); out += SEP;
                    out += a->badge_name; out += SEP;
                    snprintf(num, sizeof num, "%u", (unsigned)a->points);
                    out += num; out += SEP;
                    out += (a->unlocked ? "1" : "0"); out += SEP;
                    out += a->measured_progress; out += SEP;
                    snprintf(num, sizeof num, "%.2f", (double)a->measured_percent);
                    out += num; out += SEP;
                    snprintf(num, sizeof num, "%lld", (long long)a->unlock_time);
                    out += num; out += SEP;
                    snprintf(num, sizeof num, "%u", (unsigned)bucket->bucket_type);
                    out += num;
                }
            }
            rc_client_destroy_achievement_list(list);
        }
    }
    /* raw UTF-8 bytes: titles may hold characters NewStringUTF's modified UTF-8 cannot take */
    jbyteArray arr = env->NewByteArray((jsize)out.size());
    if (arr && !out.empty()) env->SetByteArrayRegion(arr, 0, (jsize)out.size(), (const jbyte*)out.data());
    return arr;
}

/* RetroAchievements hash of a ROM file, computed exactly like rc_client does when it identifies a game.
 * Independent of the client, so the Offline Play cacher can call it from its own threads. */
JNIEXPORT jstring JNICALL Java_com_drasticra_Native_hashFile(JNIEnv* env, jobject, jstring path) {
    std::string p = str(env, path);
    char hash[33];
    memset(hash, 0, sizeof hash);
    static std::mutex hash_mu; /* the hash library sets up its file reader lazily: keep it one at a time */
    int ok;
    {
        std::lock_guard<std::mutex> l(hash_mu);
        ok = rc_hash_generate_from_file(hash, RC_CONSOLE_NINTENDO_DS, p.c_str());
    }
    return env->NewStringUTF(ok ? hash : "");
}

JNIEXPORT void JNICALL Java_com_drasticra_Native_unload(JNIEnv*, jobject) {
    if (g_client) rc_client_unload_game(g_client);
    memset(g_used, 0, sizeof g_used);
    memset(g_have, 0, sizeof g_have);
}

} // extern "C"
