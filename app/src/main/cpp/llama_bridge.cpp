// JNI bridge between app.offlineresearch.engine.LlamaBridge and llama.cpp.
//
// Each loaded model is a Session, identified on the Kotlin side by a handle.
// Several sessions can be loaded at once (the planner and the answerer). All
// text crosses the boundary as UTF-8 byte arrays, because JNI strings use
// "modified UTF-8" and corrupt emoji and other 4-byte characters.

#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

#define TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Return codes shared with LlamaBridge.kt. nativeLoad returns a handle or one of these.
// A handle is a heap pointer; it is never -1 or -2, but with pointer tagging it can be negative.
constexpr jlong LOAD_ERR_MODEL = -1;
constexpr jlong LOAD_ERR_CONTEXT = -2;

constexpr jint STOP_EOS = 0;
constexpr jint STOP_MAX_TOKENS = 1;
constexpr jint STOP_CANCELLED = 2;
constexpr jint STOP_CONTEXT_FULL = 3;
constexpr jint ERR_BUSY = -3;
constexpr jint GEN_ERR_TEMPLATE = -4;
constexpr jint GEN_ERR_TOKENIZE = -5;
constexpr jint GEN_ERR_PROMPT_TOO_LONG = -6;
constexpr jint GEN_ERR_DECODE = -7;

constexpr int PENALTY_LAST_N = 64;

// Values of the affinity argument of nativeLoad; mirrored in LlamaBridge.kt.
constexpr jint AFFINITY_NONE = 0;
constexpr jint AFFINITY_FASTEST = 1;

// Layout of the double[] returned by nativeMetrics(); mirrored in LlamaBridge.kt.
enum MetricIndex {
    M_LOAD_MS = 0,
    M_PROMPT_TOKENS,
    M_PROMPT_MS,
    M_TTFT_MS,
    M_GEN_TOKENS,
    M_GEN_MS,
    M_THREADS,
    M_PROMPT_REUSED,
    M_THREADS_BATCH,
    M_COUNT
};

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string chat_template;
    int n_ctx = 0;
    int n_batch = 0;
    int n_threads = 0;

    // Set when the threads are pinned; otherwise llama.cpp makes its own pools.
    // One pool generates tokens, the other processes prompts; they may be the same.
    ggml_threadpool *threadpool = nullptr;
    ggml_threadpool *threadpool_batch = nullptr;
    void (*threadpool_free)(ggml_threadpool *) = nullptr;

    // The prompt tokens the context currently holds, so a new prompt that
    // starts the same way (the fixed system prompt) is not processed again.
    std::vector<llama_token> cached;

    // The file the context was last saved to or restored from. Some models'
    // memory cannot be cut back to an earlier point; for those the saved start
    // of the prompt is restored from here before each prompt.
    std::string state_path;

    std::mutex run_mutex;  // held for the whole of a generate() call
    std::atomic<bool> cancel{false};

    double metrics[M_COUNT] = {0};
    std::mutex metrics_mutex;

    ~Session() {
        if (ctx) llama_free(ctx);
        if (threadpool_batch && threadpool_batch != threadpool) threadpool_free(threadpool_batch);
        if (threadpool) threadpool_free(threadpool);
        if (model) llama_model_free(model);
    }
};

// A reranking model: it reads a question and a passage together and gives one
// number for how well the passage answers the question.
struct Ranker {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    std::mutex run_mutex;

    ~Ranker() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

using Clock = std::chrono::steady_clock;

double ms_since(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

void log_to_android(enum ggml_log_level level, const char *text, void *) {
    static int last_priority = ANDROID_LOG_INFO;
    int priority;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  priority = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  priority = ANDROID_LOG_INFO;  break;
        case GGML_LOG_LEVEL_CONT:  priority = last_priority;     break;
        default: return;  // drop DEBUG
    }
    last_priority = priority;
    __android_log_write(priority, "llama.cpp", text);
}

bool should_abort(void *data) {
    return static_cast<Session *>(data)->cancel.load(std::memory_order_relaxed);
}

// Same heuristic as llama.cpp's Android example: leave two cores for the UI and
// the system, and never use more than four. Profiles can override it.
int default_thread_count() {
    const int online = (int) sysconf(_SC_NPROCESSORS_ONLN);
    return std::max(2, std::min(4, online - 2));
}

// The n CPUs with the highest capacity, fastest first. Android reports a
// relative capacity per core; maximum frequency is the fallback.
std::vector<int> fastest_cpus(int n) {
    const int count = (int) sysconf(_SC_NPROCESSORS_CONF);
    std::vector<std::pair<long, int>> ranked;
    for (int cpu = 0; cpu < count; cpu++) {
        long value = 0;
        for (const char *leaf : {"cpu_capacity", "cpufreq/cpuinfo_max_freq"}) {
            std::ifstream in("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/" + leaf);
            if (in >> value && value > 0) break;
            value = 0;
        }
        ranked.emplace_back(value, cpu);
    }
    std::sort(ranked.begin(), ranked.end(), [](const auto &a, const auto &b) {
        return a.first != b.first ? a.first > b.first : a.second > b.second;
    });
    std::vector<int> cpus;
    for (int i = 0; i < n && i < (int) ranked.size(); i++) cpus.push_back(ranked[(size_t) i].second);
    return cpus;
}

// Gives the context thread pools restricted to the fastest cores: `threads`
// cores for generating tokens and `threads_batch` cores for processing prompts.
// Without this the scheduler spreads the threads over fast and slow cores and
// moves them around, and every operation waits for the thread on the slowest one.
void pin_to_fastest_cpus(Session &session, int threads, int threads_batch) {
    // The CPU backend is a separately loaded library, so its functions are looked up by name.
    ggml_backend_dev_t cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    ggml_backend_reg_t reg = cpu ? ggml_backend_dev_backend_reg(cpu) : nullptr;
    auto *pool_new = reg ? (decltype(ggml_threadpool_new) *)
            ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new") : nullptr;
    auto *pool_free = reg ? (decltype(ggml_threadpool_free) *)
            ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free") : nullptr;
    if (pool_new == nullptr || pool_free == nullptr) {
        LOGW("CPU backend has no thread pool functions; threads are not pinned");
        return;
    }

    auto make_pool = [&](int n, const char *purpose) -> ggml_threadpool * {
        ggml_threadpool_params params = ggml_threadpool_params_default(n);
        std::string list;
        for (int cpu_id : fastest_cpus(n)) {
            if (cpu_id >= GGML_MAX_N_THREADS) continue;
            params.cpumask[cpu_id] = true;
            list += (list.empty() ? "" : ",") + std::to_string(cpu_id);
        }
        ggml_threadpool *pool = pool_new(&params);
        if (pool == nullptr) LOGW("could not create the %s thread pool", purpose);
        else LOGI("%s: %d threads pinned to cpus %s", purpose, n, list.c_str());
        return pool;
    };

    session.threadpool = make_pool(threads, "generation");
    if (session.threadpool == nullptr) return;
    session.threadpool_free = pool_free;
    session.threadpool_batch = threads_batch == threads
            ? session.threadpool : make_pool(threads_batch, "prompt processing");
    if (session.threadpool_batch == nullptr) session.threadpool_batch = session.threadpool;
    llama_attach_threadpool(session.ctx, session.threadpool, session.threadpool_batch);
}

std::string bytes_to_string(JNIEnv *env, jbyteArray array) {
    if (array == nullptr) return {};
    const jsize len = env->GetArrayLength(array);
    std::string out((size_t) len, '\0');
    if (len > 0) env->GetByteArrayRegion(array, 0, len, (jbyte *) out.data());
    return out;
}

// Length of the longest prefix of `s` that ends on a UTF-8 character boundary.
// A token can end in the middle of a multi-byte character; the tail is held
// back until the next token completes it.
size_t complete_utf8_prefix(const std::string &s) {
    const size_t n = s.size();
    size_t i = n;
    // Walk back over at most 3 continuation bytes to the last lead byte.
    while (i > 0 && n - i < 4) {
        const unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) == 0x80) { i--; continue; }
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return (n - (i - 1) >= need) ? n : i - 1;
    }
    return n;
}

std::string apply_chat_template(const Session &session, const std::string &system,
                                const std::string &user) {
    std::vector<llama_chat_message> messages;
    if (!system.empty()) messages.push_back({"system", system.c_str()});
    messages.push_back({"user", user.c_str()});

    std::vector<char> buf(2 * (system.size() + user.size()) + 512);
    int32_t n = llama_chat_apply_template(session.chat_template.c_str(), messages.data(),
                                          messages.size(), true, buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize((size_t) n);
        n = llama_chat_apply_template(session.chat_template.c_str(), messages.data(),
                                      messages.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) return {};
    return std::string(buf.data(), (size_t) n);
}

// Tokenises `text`; returns false on failure.
bool tokenize(const Session &session, const std::string &text, bool special,
              std::vector<llama_token> &tokens) {
    const int n = -llama_tokenize(session.vocab, text.c_str(), (int32_t) text.size(), nullptr, 0,
                                  special, special);
    if (n < 0) return false;
    tokens.resize((size_t) n);
    return n == 0 || llama_tokenize(session.vocab, text.c_str(), (int32_t) text.size(),
                                    tokens.data(), n, special, special) >= 0;
}

// Marks where the question goes when only the fixed start of a prompt is wanted.
constexpr const char *QUESTION_MARK = "@@QUESTION@@";

// Fills the context from a file written by llama_state_save_file and records
// the tokens it holds. On failure the context is left empty.
bool restore_state(Session &session, const std::string &path) {
    std::vector<llama_token> tokens((size_t) session.n_ctx);
    size_t n_tokens = 0;
    if (!llama_state_load_file(session.ctx, path.c_str(), tokens.data(), tokens.size(), &n_tokens)
        || n_tokens == 0) {
        // A failed load can leave the context half filled.
        llama_memory_clear(llama_get_memory(session.ctx), true);
        session.cached.clear();
        return false;
    }
    tokens.resize(n_tokens);
    session.cached = tokens;
    return true;
}

// How many leading tokens of `tokens` the context already holds, after cutting
// the context back to exactly those; 0 when it could not be cut back.
int keep_common_prefix(Session &session, const std::vector<llama_token> &tokens) {
    int n = 0;
    const int limit = std::min((int) session.cached.size(), (int) tokens.size() - 1);
    while (n < limit && session.cached[(size_t) n] == tokens[(size_t) n]) n++;
    if (n > 0 && llama_memory_seq_rm(llama_get_memory(session.ctx), 0, n, -1)) return n;
    return 0;
}

llama_sampler *make_sampler(const Session &session, float temperature, int top_k, float top_p,
                            float presence_penalty, uint32_t seed) {
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (presence_penalty != 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(
                llama_vocab_n_tokens(session.vocab), PENALTY_LAST_N, 1.0f, 0.0f,
                presence_penalty));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        return chain;
    }
    if (top_k > 0) llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    if (top_p < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    return chain;
}

Session *from_handle(jlong handle) { return reinterpret_cast<Session *>(handle); }

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeInit(JNIEnv *env, jobject, jstring native_lib_dir) {
    llama_log_set(log_to_android, nullptr);

    // The CPU backends are separate .so files (one per ARM feature level);
    // ggml picks the best one this CPU supports.
    const char *dir = env->GetStringUTFChars(native_lib_dir, nullptr);
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(native_lib_dir, dir);

    llama_backend_init();
    LOGI("backend initialised: %s", llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeLoad(
        JNIEnv *env, jobject, jstring model_path, jint n_ctx, jint n_batch, jint n_threads,
        jint n_threads_batch, jboolean use_mmap, jboolean use_mlock, jboolean repack, jint affinity,
        jstring chat_template) {
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    if (use_mmap) {
        model_params.load_mode = use_mlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP;
    } else {
        model_params.load_mode = use_mlock ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE;
    }
    // Repacking copies the weights into a CPU-friendly layout held in ordinary
    // RAM, on top of the mapped file. Off keeps the weights file-backed only.
    model_params.use_extra_bufts = repack;

    auto *session = new Session();

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("loading %s (load_mode=%s, repack=%d)", path,
         llama_load_mode_name(model_params.load_mode), (int) repack);
    const auto t_start = Clock::now();
    session->model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);
    if (session->model == nullptr) {
        LOGE("llama_model_load_from_file failed");
        delete session;
        return LOAD_ERR_MODEL;
    }

    const int threads = n_threads > 0 ? n_threads : default_thread_count();
    const int threads_batch = n_threads_batch > 0 ? n_threads_batch : threads;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = (uint32_t) n_ctx;
    ctx_params.n_batch = (uint32_t) n_batch;
    ctx_params.n_ubatch = (uint32_t) n_batch;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads_batch;
    ctx_params.abort_callback = should_abort;
    ctx_params.abort_callback_data = session;

    session->ctx = llama_init_from_model(session->model, ctx_params);
    if (session->ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        delete session;
        return LOAD_ERR_CONTEXT;
    }
    if (affinity == AFFINITY_FASTEST) pin_to_fastest_cpus(*session, threads, threads_batch);
    const double load_ms = ms_since(t_start);

    const char *tmpl = env->GetStringUTFChars(chat_template, nullptr);
    std::string chat_tmpl(tmpl);
    env->ReleaseStringUTFChars(chat_template, tmpl);
    if (chat_tmpl.empty() || chat_tmpl == "auto") {
        const char *embedded = llama_model_chat_template(session->model, nullptr);
        if (embedded == nullptr) LOGW("model has no chat template; falling back to chatml");
        chat_tmpl = embedded ? embedded : "chatml";
    }

    session->vocab = llama_model_get_vocab(session->model);
    session->chat_template = chat_tmpl;
    session->n_ctx = (int) llama_n_ctx(session->ctx);
    session->n_batch = n_batch;
    session->n_threads = threads;
    session->metrics[M_LOAD_MS] = load_ms;
    session->metrics[M_THREADS] = threads;
    session->metrics[M_THREADS_BATCH] = threads_batch;

    char desc[128] = {0};
    llama_model_desc(session->model, desc, sizeof(desc));
    LOGI("loaded '%s' in %.0f ms: n_ctx=%d n_batch=%d threads=%d/%d size=%.2f GiB", desc, load_ms,
         session->n_ctx, n_batch, threads, threads_batch,
         (double) llama_model_size(session->model) / (1024.0 * 1024.0 * 1024.0));
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jint JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject, jlong handle, jbyteArray system_utf8, jbyteArray user_utf8,
        jbyteArray assistant_prefix_utf8, jint max_tokens, jfloat temperature, jint top_k, jfloat top_p, jfloat presence_penalty,
        jint seed, jobject callback) {
    Session &session = *from_handle(handle);
    std::unique_lock<std::mutex> lock(session.run_mutex, std::try_to_lock);
    if (!lock.owns_lock()) return ERR_BUSY;

    session.cancel = false;
    const auto t_start = Clock::now();

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(callback_class, "onToken", "([B)V");
    if (on_token == nullptr) return GEN_ERR_DECODE;  // NoSuchMethodError is pending

    // No reply asked for: only the fixed start of the prompt is read, up to
    // where a question would begin, so that any question continues from it.
    const bool start_only = max_tokens <= 0;
    std::string prompt = apply_chat_template(session, bytes_to_string(env, system_utf8),
                                             start_only ? QUESTION_MARK : bytes_to_string(env, user_utf8));
    if (prompt.empty()) {
        LOGE("chat template could not be applied");
        return GEN_ERR_TEMPLATE;
    }
    if (start_only) {
        const size_t mark = prompt.find(QUESTION_MARK);
        if (mark == std::string::npos || mark == 0) return GEN_ERR_TEMPLATE;
        prompt.resize(mark);
    } else {
        // Some models need the start of their reply written for them, such as an
        // empty thinking block that turns their thinking off.
        prompt += bytes_to_string(env, assistant_prefix_utf8);
    }

    std::vector<llama_token> tokens;
    if (!tokenize(session, prompt, true, tokens) || tokens.empty()) return GEN_ERR_TOKENIZE;
    const int n_prompt = (int) tokens.size();
    if (n_prompt >= session.n_ctx) {
        LOGE("prompt is %d tokens but the context holds %d", n_prompt, session.n_ctx);
        return GEN_ERR_PROMPT_TOO_LONG;
    }

    // Prefix caching: keep the part of the context this prompt shares with the
    // previous one (at least the fixed system prompt) and process only the rest.
    // The last token is always processed, because sampling needs its output.
    int n_reused = keep_common_prefix(session, tokens);
    if (n_reused == 0 && !session.state_path.empty() && restore_state(session, session.state_path)) {
        // This model's memory holds only its latest point and cannot go back to
        // the end of the rules. The saved file is that point: start from it again.
        n_reused = keep_common_prefix(session, tokens);
    }
    if (n_reused == 0) llama_memory_clear(llama_get_memory(session.ctx), true);
    // If this call stops early, only the reused prefix is known to be intact.
    session.cached.assign(tokens.begin(), tokens.begin() + n_reused);

    for (int i = n_reused; i < n_prompt; i += session.n_batch) {
        const int n = std::min(session.n_batch, n_prompt - i);
        const int rc = llama_decode(session.ctx, llama_batch_get_one(tokens.data() + i, n));
        if (rc == 2 || session.cancel) return STOP_CANCELLED;
        if (rc != 0) {
            LOGE("llama_decode failed on the prompt: %d", rc);
            return GEN_ERR_DECODE;
        }
    }
    session.cached = tokens;
    const double prompt_ms = ms_since(t_start);
    const auto t_gen_start = Clock::now();

    llama_sampler *sampler = make_sampler(session, temperature, top_k, top_p, presence_penalty,
                                          (uint32_t) seed);

    jint stop = STOP_MAX_TOKENS;
    int n_generated = 0;
    double ttft_ms = 0.0;
    std::string pending;  // bytes not yet delivered (incomplete UTF-8 tail)
    const int budget = std::min((int) max_tokens, session.n_ctx - n_prompt);
    if (budget < (int) max_tokens) stop = STOP_CONTEXT_FULL;

    auto emit = [&](size_t n_bytes) -> bool {
        if (n_bytes == 0) return true;
        jbyteArray array = env->NewByteArray((jsize) n_bytes);
        if (array == nullptr) return false;
        env->SetByteArrayRegion(array, 0, (jsize) n_bytes, (const jbyte *) pending.data());
        env->CallVoidMethod(callback, on_token, array);
        env->DeleteLocalRef(array);
        pending.erase(0, n_bytes);
        return !env->ExceptionCheck();
    };

    for (int i = 0; i < budget; i++) {
        if (session.cancel) { stop = STOP_CANCELLED; break; }

        llama_token token = llama_sampler_sample(sampler, session.ctx, -1);
        if (n_generated == 0) ttft_ms = ms_since(t_start);
        if (llama_vocab_is_eog(session.vocab, token)) { stop = STOP_EOS; break; }
        n_generated++;

        char piece[256];
        const int n_piece = llama_token_to_piece(session.vocab, token, piece, sizeof(piece), 0,
                                                 false);
        if (n_piece > 0) pending.append(piece, (size_t) n_piece);
        if (!emit(complete_utf8_prefix(pending))) { stop = STOP_CANCELLED; break; }

        const int rc = llama_decode(session.ctx, llama_batch_get_one(&token, 1));
        if (rc == 2) { stop = STOP_CANCELLED; break; }
        if (rc != 0) {
            LOGE("llama_decode failed during generation: %d", rc);
            stop = GEN_ERR_DECODE;
            break;
        }
    }
    if (!env->ExceptionCheck()) emit(pending.size());
    const double gen_ms = ms_since(t_gen_start);
    llama_sampler_free(sampler);

    {
        std::lock_guard<std::mutex> metrics_lock(session.metrics_mutex);
        session.metrics[M_PROMPT_TOKENS] = n_prompt;
        session.metrics[M_PROMPT_MS] = prompt_ms;
        session.metrics[M_TTFT_MS] = ttft_ms;
        session.metrics[M_GEN_TOKENS] = n_generated;
        session.metrics[M_GEN_MS] = gen_ms;
        session.metrics[M_PROMPT_REUSED] = n_reused;
    }
    LOGI("generate: prompt %d tok (%d reused) in %.0f ms, ttft %.0f ms, %d tok in %.0f ms (%.2f tok/s), stop=%d",
         n_prompt, n_reused, prompt_ms, ttft_ms, n_generated, gen_ms,
         gen_ms > 0 ? n_generated * 1000.0 / gen_ms : 0.0, stop);
    return stop;
}

// Restores a context saved by nativeSaveState: what the model had read, so it
// need not read it again. Returns the number of tokens restored, or -1 when the
// file is missing, damaged or from another model; the context is then empty.
JNIEXPORT jint JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeLoadState(JNIEnv *env, jobject, jlong handle,
                                                            jstring path) {
    Session &session = *from_handle(handle);
    std::unique_lock<std::mutex> lock(session.run_mutex, std::try_to_lock);
    if (!lock.owns_lock()) return ERR_BUSY;

    const auto t_start = Clock::now();
    const char *file = env->GetStringUTFChars(path, nullptr);
    const std::string state_path(file);
    env->ReleaseStringUTFChars(path, file);
    if (!restore_state(session, state_path)) {
        LOGW("saved state could not be restored");
        return -1;
    }
    session.state_path = state_path;
    LOGI("state restored: %d tok in %.0f ms", (int) session.cached.size(), ms_since(t_start));
    return (jint) session.cached.size();
}

// Writes what the context holds now, with its tokens, to a file.
JNIEXPORT jboolean JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeSaveState(JNIEnv *env, jobject, jlong handle,
                                                            jstring path) {
    Session &session = *from_handle(handle);
    std::unique_lock<std::mutex> lock(session.run_mutex, std::try_to_lock);
    if (!lock.owns_lock() || session.cached.empty()) return JNI_FALSE;

    const auto t_start = Clock::now();
    const char *file = env->GetStringUTFChars(path, nullptr);
    const bool ok = llama_state_save_file(session.ctx, file, session.cached.data(), session.cached.size());
    env->ReleaseStringUTFChars(path, file);
    if (ok) LOGI("state saved: %d tok in %.0f ms", (int) session.cached.size(), ms_since(t_start));
    else LOGW("state could not be saved");
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Loads a reranking model. Returns a handle, or LOAD_ERR_MODEL / LOAD_ERR_CONTEXT.
JNIEXPORT jlong JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeRankerLoad(JNIEnv *env, jobject, jstring model_path,
                                                             jint n_ctx, jint n_threads) {
    auto *ranker = new Ranker();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    const auto t_start = Clock::now();
    ranker->model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);
    if (ranker->model == nullptr) {
        delete ranker;
        return LOAD_ERR_MODEL;
    }

    const int threads = n_threads > 0 ? n_threads : default_thread_count();
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = (uint32_t) n_ctx;
    // An encoder reads a whole text in one piece, so a batch must hold all of it.
    ctx_params.n_batch = (uint32_t) n_ctx;
    ctx_params.n_ubatch = (uint32_t) n_ctx;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    ctx_params.embeddings = true;
    ctx_params.pooling_type = LLAMA_POOLING_TYPE_RANK;
    ranker->ctx = llama_init_from_model(ranker->model, ctx_params);
    if (ranker->ctx == nullptr) {
        delete ranker;
        return LOAD_ERR_CONTEXT;
    }
    ranker->vocab = llama_model_get_vocab(ranker->model);
    ranker->n_ctx = (int) llama_n_ctx(ranker->ctx);
    LOGI("ranker loaded in %.0f ms: n_ctx=%d threads=%d", ms_since(t_start), ranker->n_ctx, threads);
    return reinterpret_cast<jlong>(ranker);
}

// One score per passage, higher for a passage that answers the question
// better. Null when the model could not be run.
JNIEXPORT jfloatArray JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeRank(JNIEnv *env, jobject, jlong handle,
                                                       jbyteArray question_utf8, jobjectArray passages_utf8) {
    Ranker &ranker = *reinterpret_cast<Ranker *>(handle);
    std::lock_guard<std::mutex> lock(ranker.run_mutex);
    const auto t_start = Clock::now();

    // The layout llama.cpp's own server uses when the model file names no other:
    // [BOS] question [EOS] [SEP] passage [EOS], each mark only if the vocabulary wants it.
    const llama_vocab *vocab = ranker.vocab;
    llama_token eos = llama_vocab_eos(vocab);
    if (eos == LLAMA_TOKEN_NULL) eos = llama_vocab_sep(vocab);
    auto plain_tokens = [&](const std::string &text, std::vector<llama_token> &out) {
        const int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, false, false);
        out.resize((size_t) std::max(n, 0));
        return n <= 0 || llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), n, false, false) >= 0;
    };
    std::vector<llama_token> question;
    if (!plain_tokens(bytes_to_string(env, question_utf8), question)) return nullptr;
    // A long question must leave the passage most of the room.
    if ((int) question.size() > ranker.n_ctx / 4) question.resize((size_t) ranker.n_ctx / 4);

    const jsize count = env->GetArrayLength(passages_utf8);
    std::vector<float> scores((size_t) count, 0.0f);
    llama_batch batch = llama_batch_init(ranker.n_ctx, 0, 1);
    int total_tokens = 0;
    bool ok = true;
    for (jsize i = 0; i < count && ok; i++) {
        auto passage_bytes = (jbyteArray) env->GetObjectArrayElement(passages_utf8, i);
        std::vector<llama_token> passage;
        ok = plain_tokens(bytes_to_string(env, passage_bytes), passage);
        env->DeleteLocalRef(passage_bytes);
        if (!ok) break;

        std::vector<llama_token> tokens;
        if (llama_vocab_get_add_bos(vocab)) tokens.push_back(llama_vocab_bos(vocab));
        tokens.insert(tokens.end(), question.begin(), question.end());
        if (llama_vocab_get_add_eos(vocab)) tokens.push_back(eos);
        if (llama_vocab_get_add_sep(vocab)) tokens.push_back(llama_vocab_sep(vocab));
        // What does not fit is cut from the end of the passage.
        const int room = ranker.n_ctx - (int) tokens.size() - 1;
        if ((int) passage.size() > room) passage.resize((size_t) std::max(room, 0));
        tokens.insert(tokens.end(), passage.begin(), passage.end());
        if (llama_vocab_get_add_eos(vocab)) tokens.push_back(eos);

        batch.n_tokens = (int32_t) tokens.size();
        for (int t = 0; t < batch.n_tokens; t++) {
            batch.token[t] = tokens[(size_t) t];
            batch.pos[t] = t;
            batch.n_seq_id[t] = 1;
            batch.seq_id[t][0] = 0;
            batch.logits[t] = 1;
        }
        llama_memory_clear(llama_get_memory(ranker.ctx), true);
        if (llama_decode(ranker.ctx, batch) != 0) { ok = false; break; }
        const float *out = llama_get_embeddings_seq(ranker.ctx, 0);
        if (out == nullptr) { ok = false; break; }
        scores[(size_t) i] = out[0];
        total_tokens += batch.n_tokens;
    }
    llama_batch_free(batch);
    if (!ok) {
        LOGW("ranking failed");
        return nullptr;
    }
    LOGI("ranked %d passages (%d tok) in %.0f ms", (int) count, total_tokens, ms_since(t_start));
    jfloatArray result = env->NewFloatArray(count);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, count, scores.data());
    return result;
}

// The handle must not be used again after this call.
JNIEXPORT void JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeRankerUnload(JNIEnv *, jobject, jlong handle) {
    auto *ranker = reinterpret_cast<Ranker *>(handle);
    { std::lock_guard<std::mutex> lock(ranker->run_mutex); }
    delete ranker;
}

// Number of tokens in `text` for this model, or -1. Uses only the vocabulary,
// so it is safe to call while a generate() is running.
JNIEXPORT jint JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeTokenCount(JNIEnv *env, jobject, jlong handle,
                                                             jbyteArray text_utf8) {
    std::vector<llama_token> tokens;
    if (!tokenize(*from_handle(handle), bytes_to_string(env, text_utf8), false, tokens)) return -1;
    return (jint) tokens.size();
}

JNIEXPORT void JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeCancel(JNIEnv *, jobject, jlong handle) {
    from_handle(handle)->cancel = true;
}

JNIEXPORT jdoubleArray JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeMetrics(JNIEnv *env, jobject, jlong handle) {
    Session &session = *from_handle(handle);
    jdoubleArray out = env->NewDoubleArray(M_COUNT);
    if (out == nullptr) return nullptr;
    std::lock_guard<std::mutex> metrics_lock(session.metrics_mutex);
    env->SetDoubleArrayRegion(out, 0, M_COUNT, session.metrics);
    return out;
}

JNIEXPORT jstring JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// The handle must not be used again after this call.
JNIEXPORT void JNICALL
Java_app_offlineresearch_engine_LlamaBridge_nativeUnload(JNIEnv *, jobject, jlong handle) {
    Session *session = from_handle(handle);
    session->cancel = true;  // make a running generate() return so the lock frees up
    { std::lock_guard<std::mutex> lock(session->run_mutex); }
    delete session;
}

}  // extern "C"
