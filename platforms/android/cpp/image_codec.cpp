/**
 * [INPUT]: 依赖 Node-API 异步工作与 Android ImageCodec 的 JNI 方法
 * [OUTPUT]: 交付 inspect 和 encode 的 Promise，响应用图片事实或编码字节
 * [POS]: Node 与 Android 图片编解码之间的内存边界，后台线程不访问 JS
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
#include "image_codec.h"
#include <node_api.h>
#include <cmath>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

namespace {
constexpr size_t MAX_SOURCE_BYTES = 20 * 1024 * 1024;
constexpr int MAX_SIDE = 16'384;
constexpr int MAX_PIXELS = 24'000'000;

struct JavaCodec {
    JavaVM* vm = nullptr;
    jclass type = nullptr;
    jmethodID inspect = nullptr;
    jmethodID encode = nullptr;
    jmethodID exceptionMessage = nullptr;
    jfieldID mediaType = nullptr;
    jfieldID width = nullptr;
    jfieldID height = nullptr;
    jfieldID alpha = nullptr;
};

JavaCodec java;

enum class Operation { Inspect, Encode };

struct ImageTask {
    Operation operation;
    napi_async_work work = nullptr;
    napi_deferred deferred = nullptr;
    std::vector<uint8_t> bytes;
    std::string mediaType;
    int width = 0;
    int height = 0;
    int quality = 0;
    int maxPixels = 0;
    int maxSide = 0;
    bool alpha = false;
    const char* error = nullptr;
};

const char* codecError(JNIEnv* env) {
    jthrowable failure = env->ExceptionOccurred();
    if (!failure) return "INVALID_IMAGE";
    env->ExceptionClear();
    auto message = static_cast<jstring>(env->CallObjectMethod(failure, java.exceptionMessage));
    const char* code = "INVALID_IMAGE";
    if (message) {
        const char* value = env->GetStringUTFChars(message, nullptr);
        if (value) {
            for (const char* candidate : {"IMAGE_TOO_MANY_PIXELS", "IMAGE_DIMENSION_TOO_LARGE", "UNSUPPORTED_IMAGE_TYPE"}) {
                if (std::strcmp(value, candidate) == 0) {
                    code = candidate;
                    break;
                }
            }
            env->ReleaseStringUTFChars(message, value);
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    return code;
}

void runCodec(JNIEnv* env, ImageTask& task) {
    jobject input = env->NewDirectByteBuffer(task.bytes.data(), static_cast<jlong>(task.bytes.size()));
    if (!input) {
        task.error = codecError(env);
        return;
    }
    if (task.operation == Operation::Inspect) {
        jobject result = env->CallStaticObjectMethod(java.type, java.inspect, input, task.maxPixels, task.maxSide);
        if (!result || env->ExceptionCheck()) {
            task.error = codecError(env);
            return;
        }
        auto mediaType = static_cast<jstring>(env->GetObjectField(result, java.mediaType));
        const char* value = env->GetStringUTFChars(mediaType, nullptr);
        if (!value) {
            task.error = codecError(env);
            return;
        }
        task.mediaType = value;
        env->ReleaseStringUTFChars(mediaType, value);
        task.width = env->GetIntField(result, java.width);
        task.height = env->GetIntField(result, java.height);
        task.alpha = env->GetBooleanField(result, java.alpha);
        return;
    }
    jstring mediaType = env->NewStringUTF(task.mediaType.c_str());
    if (!mediaType) {
        task.error = codecError(env);
        return;
    }
    auto result = static_cast<jbyteArray>(env->CallStaticObjectMethod(
        java.type, java.encode, input, task.width, task.height, mediaType, task.quality));
    if (!result || env->ExceptionCheck()) {
        task.error = codecError(env);
        return;
    }
    jsize length = env->GetArrayLength(result);
    task.bytes.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(result, 0, length, reinterpret_cast<jbyte*>(task.bytes.data()));
    if (env->ExceptionCheck()) task.error = codecError(env);
}

void execute(napi_env, void* data) {
    auto& task = *static_cast<ImageTask*>(data);
    JNIEnv* env = nullptr;
    if (java.vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        task.error = "IMAGE_CODEC_UNAVAILABLE";
        return;
    }
    try {
        runCodec(env, task);
    } catch (const std::bad_alloc&) {
        task.error = "IMAGE_CODEC_UNAVAILABLE";
    }
    java.vm->DetachCurrentThread();
}

napi_status facts(napi_env env, const ImageTask& task, napi_value* result) {
    napi_value mediaType, width, height, alpha;
    if (napi_create_object(env, result) != napi_ok ||
        napi_create_string_utf8(env, task.mediaType.c_str(), NAPI_AUTO_LENGTH, &mediaType) != napi_ok ||
        napi_create_int32(env, task.width, &width) != napi_ok ||
        napi_create_int32(env, task.height, &height) != napi_ok ||
        napi_get_boolean(env, task.alpha, &alpha) != napi_ok ||
        napi_set_named_property(env, *result, "mediaType", mediaType) != napi_ok ||
        napi_set_named_property(env, *result, "width", width) != napi_ok ||
        napi_set_named_property(env, *result, "height", height) != napi_ok) return napi_generic_failure;
    return napi_set_named_property(env, *result, "hasAlpha", alpha);
}

void complete(napi_env env, napi_status status, void* data) {
    std::unique_ptr<ImageTask> task(static_cast<ImageTask*>(data));
    napi_delete_async_work(env, task->work);
    napi_value result;
    if (status == napi_ok && !task->error) {
        status = task->operation == Operation::Inspect
            ? facts(env, *task, &result)
            : napi_create_buffer_copy(env, task->bytes.size(), task->bytes.data(), nullptr, &result);
        if (status == napi_ok) {
            napi_resolve_deferred(env, task->deferred, result);
            return;
        }
    }
    napi_value code, message;
    if (napi_create_string_utf8(env, task->error ? task->error : "IMAGE_CODEC_UNAVAILABLE", NAPI_AUTO_LENGTH, &code) == napi_ok &&
        napi_create_string_utf8(env, "Android image processing failed", NAPI_AUTO_LENGTH, &message) == napi_ok &&
        napi_create_error(env, code, message, &result) == napi_ok) {
        napi_reject_deferred(env, task->deferred, result);
    }
}

bool integer(napi_env env, napi_value argument, int min, int max, int& result) {
    double number;
    if (napi_get_value_double(env, argument, &number) != napi_ok ||
        !(number >= min && number <= max) || std::trunc(number) != number) return false;
    result = static_cast<int>(number);
    return true;
}

bool parseArguments(napi_env env, napi_callback_info info, ImageTask& task, void** input, size_t* length) {
    napi_value args[5], buffer;
    size_t count = 5, offset;
    napi_typedarray_type type;
    if (napi_get_cb_info(env, info, &count, args, nullptr, nullptr) != napi_ok ||
        count != (task.operation == Operation::Inspect ? 3 : 5) ||
        napi_get_typedarray_info(env, args[0], &type, length, input, &buffer, &offset) != napi_ok ||
        type != napi_uint8_array || *length == 0 || *length > MAX_SOURCE_BYTES) return false;

    if (task.operation == Operation::Inspect) {
        return integer(env, args[1], 1, MAX_PIXELS, task.maxPixels) &&
               integer(env, args[2], 1, MAX_SIDE, task.maxSide);
    }
    char mediaType[16];
    size_t size;
    if (napi_get_value_string_utf8(env, args[3], nullptr, 0, &size) != napi_ok || size >= sizeof(mediaType) ||
        napi_get_value_string_utf8(env, args[3], mediaType, sizeof(mediaType), &size) != napi_ok) return false;
    task.mediaType.assign(mediaType, size);
    if (task.mediaType != "image/jpeg" && task.mediaType != "image/png" && task.mediaType != "image/webp") return false;
    return integer(env, args[1], 1, MAX_SIDE, task.width) &&
           integer(env, args[2], 1, MAX_SIDE, task.height) &&
           integer(env, args[4], 0, 100, task.quality);
}

napi_value queue(napi_env env, napi_callback_info info, Operation operation) {
    std::unique_ptr<ImageTask> task;
    try {
        task = std::make_unique<ImageTask>();
        task->operation = operation;
        void* input;
        size_t length;
        if (!parseArguments(env, info, *task, &input, &length)) {
            napi_throw_type_error(env, "INVALID_IMAGE", "Invalid Android image codec arguments");
            return nullptr;
        }
        const auto* first = static_cast<const uint8_t*>(input);
        task->bytes.assign(first, first + length);
    } catch (const std::bad_alloc&) {
        napi_throw_error(env, "IMAGE_CODEC_UNAVAILABLE", "Android image input allocation failed");
        return nullptr;
    }
    napi_value name, promise;
    if (!java.vm || napi_create_string_utf8(env, "OwnDsh image codec", NAPI_AUTO_LENGTH, &name) != napi_ok ||
        napi_create_promise(env, &task->deferred, &promise) != napi_ok ||
        napi_create_async_work(env, nullptr, name, execute, complete, task.get(), &task->work) != napi_ok ||
        napi_queue_async_work(env, task->work) != napi_ok) {
        if (task->work) napi_delete_async_work(env, task->work);
        napi_throw_error(env, "IMAGE_CODEC_UNAVAILABLE", "Cannot start Android image processing");
        return nullptr;
    }
    task.release();
    return promise;
}
}

bool initializeImageCodec(JNIEnv* env) {
    jclass codec = env->FindClass("com/owndsh/mobile/ImageCodec");
    if (!codec) return false;
    jclass result = env->FindClass("com/owndsh/mobile/ImageCodec$Facts");
    if (!result) return false;
    jclass throwable = env->FindClass("java/lang/Throwable");
    if (!throwable) return false;
    java.type = static_cast<jclass>(env->NewGlobalRef(codec));
    if (!java.type) return false;
    java.inspect = env->GetStaticMethodID(codec, "inspect", "(Ljava/nio/ByteBuffer;II)Lcom/owndsh/mobile/ImageCodec$Facts;");
    if (!java.inspect) return false;
    java.encode = env->GetStaticMethodID(codec, "encode", "(Ljava/nio/ByteBuffer;IILjava/lang/String;I)[B");
    if (!java.encode) return false;
    java.exceptionMessage = env->GetMethodID(throwable, "getMessage", "()Ljava/lang/String;");
    if (!java.exceptionMessage) return false;
    java.mediaType = env->GetFieldID(result, "mediaType", "Ljava/lang/String;");
    if (!java.mediaType) return false;
    java.width = env->GetFieldID(result, "width", "I");
    if (!java.width) return false;
    java.height = env->GetFieldID(result, "height", "I");
    if (!java.height) return false;
    java.alpha = env->GetFieldID(result, "hasAlpha", "Z");
    return java.alpha && env->GetJavaVM(&java.vm) == JNI_OK;
}

NAPI_MODULE_INIT() {
    napi_property_descriptor methods[] = {
        {.utf8name = "inspect", .method = [](napi_env env, napi_callback_info info) {
            return queue(env, info, Operation::Inspect);
        }},
        {.utf8name = "encode", .method = [](napi_env env, napi_callback_info info) {
            return queue(env, info, Operation::Encode);
        }},
    };
    return napi_define_properties(env, exports, sizeof(methods) / sizeof(methods[0]), methods) == napi_ok ? exports : nullptr;
}
