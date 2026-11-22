/*
 * [INPUT]: 依赖 Android Linux renameat2(RENAME_NOREPLACE) 与稳定 Node-API
 * [OUTPUT]: 提供不覆盖已存在目标的原子文件发布原语，返回真实 errno
 * [POS]: mobile 对 Android 禁止硬链接的最小系统调用适配；DSH 保留锁、fsync、清理与错误分类
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
#include <node_api.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <unistd.h>

static napi_value publish(napi_env env, napi_callback_info info) {
    size_t count = 2;
    napi_value args[2], result;
    if (napi_get_cb_info(env, info, &count, args, NULL, NULL) != napi_ok || count != 2) {
        napi_throw_type_error(env, NULL, "publishExclusive requires source and target paths");
        return NULL;
    }
    char* paths[2] = {NULL, NULL};
    for (size_t i = 0; i < 2; i++) {
        size_t length;
        if (napi_get_value_string_utf8(env, args[i], NULL, 0, &length) != napi_ok || length == 0) {
            free(paths[0]); napi_throw_type_error(env, NULL, "Invalid publication path"); return NULL;
        }
        paths[i] = malloc(length + 1);
        if (paths[i] == NULL) { free(paths[0]); napi_throw_error(env, NULL, "Publication allocation failed"); return NULL; }
        napi_get_value_string_utf8(env, args[i], paths[i], length + 1, &length);
    }
    int code = syscall(SYS_renameat2, AT_FDCWD, paths[0], AT_FDCWD, paths[1], RENAME_NOREPLACE) == 0 ? 0 : errno;
    free(paths[0]); free(paths[1]);
    napi_create_int32(env, code, &result);
    return result;
}
NAPI_MODULE_INIT() {
    napi_value function;
    if (napi_create_function(env, "publishExclusive", NAPI_AUTO_LENGTH, publish, NULL, &function) != napi_ok ||
        napi_set_named_property(env, exports, "publishExclusive", function) != napi_ok) return NULL;
    return exports;
}
