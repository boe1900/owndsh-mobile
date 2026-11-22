/**
 * [INPUT]: 依赖 Node Mobile 公共 node::Start、JNI 与 Android log
 * [OUTPUT]: 在 Service 线程中启动一次 Node，将原生 stderr 送到 logcat
 * [POS]: mobile native 的薄桥；JS 引擎逻辑与 Android 生命周期留在各自 owner
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
#include <jni.h>
#include <node.h>
#include <android/log.h>
#include <unistd.h>
#include <atomic>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

static std::string utf8(JNIEnv* env, jstring value) {
    const char* text = env->GetStringUTFChars(value, nullptr);
    std::string result(text);
    env->ReleaseStringUTFChars(value, text);
    return result;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_owndsh_mobile_NodeBridge_start(JNIEnv* env, jclass, jstring script, jstring config, jstring home) {
    static std::atomic<bool> started{false};
    if (started.exchange(true)) return 70;
    const std::string root = utf8(env, home);
    setenv("HOME", root.c_str(), 1);
    setenv("TMPDIR", (root + "/tmp").c_str(), 1);
    int logs[2];
    if (pipe(logs) == 0) {
        dup2(logs[1], STDOUT_FILENO);
        dup2(logs[1], STDERR_FILENO);
        close(logs[1]);
        std::thread([fd = logs[0]] {
            char buffer[2048];
            ssize_t count;
            while ((count = read(fd, buffer, sizeof(buffer) - 1)) > 0) {
                buffer[count] = '\0';
                __android_log_write(ANDROID_LOG_INFO, "OwnDshNode", buffer);
            }
            close(fd);
        }).detach();
    }
    std::vector<std::string> args = {"node", "--expose-internals", "--experimental-vm-modules", utf8(env, script), utf8(env, config)};
    size_t total = 0;
    for (const auto& arg : args) total += arg.size() + 1;
    std::vector<char> storage(total);
    std::vector<char*> argv;
    size_t offset = 0;
    for (const auto& arg : args) {
        argv.push_back(storage.data() + offset);
        std::copy(arg.begin(), arg.end(), storage.begin() + offset);
        offset += arg.size() + 1;
    }
    argv.push_back(nullptr);
    return node::Start(static_cast<int>(args.size()), argv.data());
}
