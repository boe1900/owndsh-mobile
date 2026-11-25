/**
 * [INPUT]: 依赖 NodeBridge 启动线程的 JNI 环境
 * [OUTPUT]: 在 Node 启动前初始化图片编解码 JNI 方法
 * [POS]: Node 与 Android 图片编解码适配之间的初始化边界
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
#pragma once
#include <jni.h>

bool initializeImageCodec(JNIEnv* env);
