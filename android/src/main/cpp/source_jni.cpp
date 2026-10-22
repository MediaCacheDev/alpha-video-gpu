// Readers over a Java AlphaVideoGpuDataSource (AlphaVideoGpuReader and its Upstream), and the
// check of the range-source add-on's table (RangeSourceLink)

#include "source_jni.h"

#include <android/log.h>
#include <pthread.h>

#include <algorithm>
#include <cstddef>
#include <string>
#include <vector>

#define LOG_TAG "AlphaVideoGpu"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

pthread_key_t detachKey;
pthread_once_t detachKeyOnce = PTHREAD_ONCE_INIT;

void detachThread(void* vm) {
    static_cast<JavaVM*>(vm)->DetachCurrentThread();
}

// This thread's env. A native thread is attached until it exits, as a reader's fetch thread calls
// into Java for as long as it runs.
JNIEnv* threadEnv(JavaVM* vm) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    pthread_once(&detachKeyOnce, [] { pthread_key_create(&detachKey, detachThread); });
    pthread_setspecific(detachKey, vm);
    return env;
}

// Clears a pending Java exception; true if there was one.
bool clearException(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

std::string stringFromJava(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

class JavaUpstream : public avg_rs::Upstream {
public:
    static std::unique_ptr<JavaUpstream> create(JNIEnv* env, jobject upstream) {
        auto result = std::unique_ptr<JavaUpstream>(new JavaUpstream());
        env->GetJavaVM(&result->vm_);
        jclass cls = env->GetObjectClass(upstream);
        result->open_ = env->GetMethodID(cls, "open", "(J)J");
        result->read_ = env->GetMethodID(cls, "read", "([BI)I");
        result->close_ = env->GetMethodID(cls, "close", "()V");
        result->cancel_ = env->GetMethodID(cls, "cancel", "()V");
        result->etag_ = env->GetFieldID(cls, "etag", "Ljava/lang/String;");
        result->lastModified_ = env->GetFieldID(cls, "lastModified", "Ljava/lang/String;");
        result->error_ = env->GetFieldID(cls, "error", "Ljava/lang/String;");
        env->DeleteLocalRef(cls);
        jbyteArray buffer = env->NewByteArray(kBufferBytes);
        if (clearException(env) || !buffer || !result->open_ || !result->read_ || !result->close_ ||
            !result->cancel_ || !result->etag_ || !result->lastModified_ || !result->error_) {
            return nullptr;
        }
        result->object_ = env->NewGlobalRef(upstream);
        result->buffer_ = static_cast<jbyteArray>(env->NewGlobalRef(buffer));
        env->DeleteLocalRef(buffer);
        return result;
    }

    ~JavaUpstream() override {
        JNIEnv* env = threadEnv(vm_);
        if (!env) return;
        if (object_) env->DeleteGlobalRef(object_);
        if (buffer_) env->DeleteGlobalRef(buffer_);
    }

    bool open(int64_t position, avg_rs::SourceInfo& info, std::string& error) override {
        JNIEnv* env = threadEnv(vm_);
        if (!env) {
            error = "Cannot attach the reader's thread to Java";
            return false;
        }
        jlong size = env->CallLongMethod(object_, open_, static_cast<jlong>(position));
        if (clearException(env)) {
            error = "The data source threw";
            return false;
        }
        if (size == -2) {
            error = stringField(env, error_);
            return false;
        }
        info.size = size >= 0 ? size : -1;
        info.etag = stringField(env, etag_);
        info.lastModified = stringField(env, lastModified_);
        return true;
    }

    int64_t read(uint8_t* out, int64_t size, std::string& error) override {
        JNIEnv* env = threadEnv(vm_);
        if (!env) {
            error = "Cannot attach the reader's thread to Java";
            return -1;
        }
        jint n = env->CallIntMethod(object_, read_, buffer_, static_cast<jint>(std::min<int64_t>(size, kBufferBytes)));
        if (clearException(env)) {
            error = "The data source threw";
            return -1;
        }
        if (n < 0) {
            error = stringField(env, error_);
            return -1;
        }
        if (n > 0) env->GetByteArrayRegion(buffer_, 0, n, reinterpret_cast<jbyte*>(out));
        return n;
    }

    void close() override { call(close_); }
    void cancel() override { call(cancel_); }

private:
    static constexpr jint kBufferBytes = 64 * 1024;

    JavaUpstream() = default;

    void call(jmethodID method) {
        JNIEnv* env = threadEnv(vm_);
        if (!env) return;
        env->CallVoidMethod(object_, method);
        clearException(env);
    }

    std::string stringField(JNIEnv* env, jfieldID field) {
        auto value = static_cast<jstring>(env->GetObjectField(object_, field));
        std::string result = stringFromJava(env, value);
        if (value) env->DeleteLocalRef(value);
        return result;
    }

    JavaVM* vm_ = nullptr;
    jobject object_ = nullptr;
    jbyteArray buffer_ = nullptr;
    jmethodID open_ = nullptr, read_ = nullptr, close_ = nullptr, cancel_ = nullptr;
    jfieldID etag_ = nullptr, lastModified_ = nullptr, error_ = nullptr;
};

// An AlphaVideoGpuReader reference: a shared_ptr of its own, so each holder frees only its own.
using ReaderRef = std::shared_ptr<avg_rs::Reader>;

const ReaderRef& readerOf(jlong reference) {
    return *reinterpret_cast<ReaderRef*>(reference);
}

} // namespace

std::shared_ptr<avg_rs::Reader> readerFromReference(jlong reference) {
    return reference != 0 ? readerOf(reference) : nullptr;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_mediacache_alphavideo_gpu_RangeSourceLink_nativeUsablePlugin(JNIEnv*, jclass, jlong address) {
    const auto* plugin = reinterpret_cast<const RangeSourcePlugin*>(address);
    if (!plugin) return 0;
    if (plugin->abi != RANGE_SOURCE_ABI) {
        LOGE("Not using range-source: its ABI is %d, and this player needs %d", plugin->abi, RANGE_SOURCE_ABI);
        return 0;
    }
    if (plugin->structSize < offsetof(RangeSourcePlugin, releaseReader) + sizeof(plugin->releaseReader)) {
        LOGE("Not using range-source: it is older than this player");
        return 0;
    }
    return reinterpret_cast<jlong>(plugin);
}

JNIEXPORT jlong JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuReader_nativeOpen(
    JNIEnv* env, jclass, jlong plugin, jobject upstream, jint storage, jstring storeDir, jstring key,
    jlong maxStorageBytes, jstring downloadDir, jobjectArray errorOut) {
    // AlphaVideoGpuReader.Storage ordinals
    enum { kDisk = 0, kMemory = 1 };
    std::string error;
    std::shared_ptr<avg_rs::Reader> reader;
    std::unique_ptr<JavaUpstream> javaUpstream = JavaUpstream::create(env, upstream);
    const auto* table = reinterpret_cast<const RangeSourcePlugin*>(plugin);
    if (!javaUpstream) {
        error = "Cannot call the data source";
    } else if (table) {
        std::shared_ptr<avg_rs::Store> store;
        if (storage == kDisk) {
            store = avg_rs::Store::disk(table, stringFromJava(env, storeDir), maxStorageBytes);
        } else if (storage == kMemory) {
            store = avg_rs::Store::memory(table, stringFromJava(env, storeDir), maxStorageBytes);
        }
        reader = avg_rs::RangeReader::open(table, std::move(javaUpstream), store, stringFromJava(env, key),
                                           avg_rs::ReaderOptions(), error);
    } else {
        reader = avg_rs::DownloadReader::open(std::move(javaUpstream), stringFromJava(env, downloadDir), error);
    }
    if (!reader) {
        jstring message = env->NewStringUTF(error.c_str());
        env->SetObjectArrayElement(errorOut, 0, message);
        env->DeleteLocalRef(message);
        return 0;
    }
    return reinterpret_cast<jlong>(new ReaderRef(std::move(reader)));
}

JNIEXPORT void JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuReader_nativeRelease(JNIEnv*, jclass, jlong reference) {
    delete reinterpret_cast<ReaderRef*>(reference);
}

JNIEXPORT jint JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuReader_nativeTryRead(
    JNIEnv* env, jclass, jlong reference, jlong position, jbyteArray buffer, jint offset, jint length) {
    if (length <= 0) return 0;
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    const int64_t n = readerOf(reference)->tryRead(position, bytes.data(), length);
    if (n > 0) env->SetByteArrayRegion(buffer, offset, static_cast<jsize>(n), reinterpret_cast<const jbyte*>(bytes.data()));
    return static_cast<jint>(n);
}

JNIEXPORT jlong JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuReader_nativeSize(JNIEnv*, jclass, jlong reference) {
    return readerOf(reference)->size();
}

JNIEXPORT jstring JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuReader_nativeLastError(JNIEnv* env, jclass, jlong reference) {
    return env->NewStringUTF(readerOf(reference)->lastError().c_str());
}

} // extern "C"
