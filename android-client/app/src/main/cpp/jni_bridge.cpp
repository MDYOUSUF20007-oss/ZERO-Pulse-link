#include <jni.h>
#include <memory>
#include "PulseLinkEngine.h"

static std::unique_ptr<pulselink::PulseLinkEngine> gEngine;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_zero_pulselink_engine_NativeAudioEngine_initEngine(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint sampleRate,
    jint channels,
    jint profileCode) {
    if (!gEngine) {
        gEngine = std::make_unique<pulselink::PulseLinkEngine>();
    }
    auto profile = static_cast<pulselink::BufferProfile>(profileCode);
    return gEngine->init(static_cast<uint32_t>(sampleRate), channels, profile) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_zero_pulselink_engine_NativeAudioEngine_destroyEngine(
    JNIEnv* /*env*/,
    jobject /*thiz*/) {
    if (gEngine) {
        gEngine->destroy();
        gEngine.reset();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_zero_pulselink_engine_NativeAudioEngine_startStream(
    JNIEnv* /*env*/,
    jobject /*thiz*/) {
    if (gEngine) {
        return gEngine->start() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_zero_pulselink_engine_NativeAudioEngine_stopStream(
    JNIEnv* /*env*/,
    jobject /*thiz*/) {
    if (gEngine) {
        gEngine->stop();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_zero_pulselink_engine_NativeAudioEngine_pushAudioPacket(
    JNIEnv* env,
    jobject /*thiz*/,
    jbyteArray packetArray,
    jint length) {
    if (!gEngine || packetArray == nullptr || length < 16) {
        return JNI_FALSE;
    }

    jbyte* bytes = env->GetByteArrayElements(packetArray, nullptr);
    if (!bytes) {
        return JNI_FALSE;
    }

    bool success = gEngine->pushAudioPacket(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(packetArray, bytes, JNI_ABORT);

    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_zero_pulselink_engine_NativeAudioEngine_setVolume(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jfloat volume) {
    if (gEngine) {
        gEngine->setVolume(volume);
    }
}

JNIEXPORT void JNICALL
Java_com_zero_pulselink_engine_NativeAudioEngine_setStereoRouting(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint routingCode) {
    if (gEngine) {
        gEngine->setStereoRouting(static_cast<pulselink::StereoRouting>(routingCode));
    }
}

JNIEXPORT void JNICALL
Java_com_zero_pulselink_engine_NativeAudioEngine_setBufferProfile(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint profileCode) {
    if (gEngine) {
        gEngine->setBufferProfile(static_cast<pulselink::BufferProfile>(profileCode));
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_zero_pulselink_engine_NativeAudioEngine_getTelemetry(
    JNIEnv* env,
    jobject /*thiz*/) {
    jfloatArray result = env->NewFloatArray(7);
    if (!result) return nullptr;

    float telemetry[7] = {0.0f};
    if (gEngine) {
        gEngine->getTelemetry(telemetry);
    }

    env->SetFloatArrayRegion(result, 0, 7, telemetry);
    return result;
}

} // extern "C"
