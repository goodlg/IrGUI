#define LOG_TAG "IrisGui"
#define LOG_NDEBUG 0

#include <jni.h>
#include <string>
#include <android/log.h>
#include <dlfcn.h>
#include <assert.h>

#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#ifndef TRUE
#define TRUE 1
#endif
#ifndef FALSE
#define FALSE 0
#endif

#define IRIS_LIB_PATH "/system/lib/libraw_interface.so"

#define UNUSED(x) (void)(x)

static void *s_handle = NULL;

typedef int (*CAC_FUNC)();
typedef int (*CAC_FUNC1)(int);
typedef int (*CAC_FUNC2)(int, int *);
typedef int (*CAC_FUNC3)(int, int);
typedef void (*PFN_FRAMEPOST_CB)(void *pFrameHeapBase, int frame_len);
typedef int (*CAC_FUNC4)(PFN_FRAMEPOST_CB);

void dataCallback(void *pFrameHeapBase, int frame_len);

/* API */
CAC_FUNC Open = NULL;
CAC_FUNC Close = NULL;
CAC_FUNC StartStream = NULL;
CAC_FUNC StopStream = NULL;
CAC_FUNC2 ReadRegister = NULL;
CAC_FUNC3 WriteRegister = NULL;
CAC_FUNC3 SetLed = NULL;
CAC_FUNC4 RegisterFrameCallback = NULL;

void dataCallback(void *pFrameHeapBase, int frame_len) {
    UNUSED(pFrameHeapBase);
    LOGI("[IR_JNI]: dataCallback, frame_len=%d", frame_len);
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_ftd_gyn_IrisguiActicity_ApiInit(
        JNIEnv *env,
        jobject /* this */) {
    char *error;
    LOGI("[IR_JNI]: ApiInit");

    s_handle = dlopen(IRIS_LIB_PATH, RTLD_NOW);
    if (NULL == s_handle) {
        LOGE("[IR_JNI]: Failed to get IRIS handle in %s()! (Reason=%s)\n", __FUNCTION__, dlerror());
        return FALSE;
    }

    //clear prev error
    dlerror();

    *(void **) (&Open) = dlsym(s_handle, "RawCam_Open");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_Open handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return FALSE;
    }

    *(void **) (&Close) = dlsym(s_handle, "RawCam_Close");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_Close handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return FALSE;
    }

    *(void **) (&StartStream) = dlsym(s_handle, "RawCam_StartStream");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_StartStream handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return FALSE;
    }

    *(void **) (&StopStream) = dlsym(s_handle, "RawCam_StopStream");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_StopStream handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return FALSE;
    }

    *(void **) (&ReadRegister) = dlsym(s_handle, "RawCam_ReadRegister");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_ReadRegister handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return FALSE;
    }

    *(void **) (&WriteRegister) = dlsym(s_handle, "RawCam_WriteRegister");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_WriteRegister handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return FALSE;
    }

    *(void **) (&SetLed) = dlsym(s_handle, "RawCam_SetLed");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_SetLed handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return FALSE;
    }

    *(void **) (&RegisterFrameCallback) = dlsym(s_handle, "RawCam_RegisterFrameCallback");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_RegisterFrameCallback handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return FALSE;
    }

    LOGI("[IR_JNI]: INIT done");
    return TRUE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_ftd_gyn_IrisguiActicity_ApiDeinit(
        JNIEnv *env,
        jobject /* this */) {
    if (s_handle != NULL) {
        dlclose(s_handle);
        LOGI("[IR_JNI]: ApiDeinit done");
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_ftd_gyn_IrisguiActicity_RawCamOpen(
        JNIEnv *env,
        jobject obj,
        jint id) {
    LOGI("[IR_JNI]: RawCamOpen (id %d)", id);
    return Open();
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_ftd_gyn_IrisguiActicity_RawCamClose(
        JNIEnv *env,
        jobject /* this */) {
    LOGI("[IR_JNI]: RawCamClose");
    return Close();
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_ftd_gyn_IrisguiActicity_RawCamStartStream(
        JNIEnv *env,
        jobject /* this */) {
    LOGI("[IR_JNI]: RawCamStartStream");
    StartStream();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_ftd_gyn_IrisguiActicity_RawCamStopStream(
        JNIEnv *env,
        jobject /* this */) {
    LOGI("[IR_JNI]: RawCamStopStream");
    StopStream();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_ftd_gyn_IrisguiActicity_RawCamReadRegister(
        JNIEnv *env,
        jobject obj,/* this */
        jint addr) {
    int value = 0;
    LOGI("[IR_JNI]: ReadRegister, addr=%d", addr);
    ReadRegister(addr, &value);
    return value;
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_ftd_gyn_IrisguiActicity_RawCamWriteRegister(
        JNIEnv *env,
        jobject obj,/* this */
        jint addr,
        jint value) {
    LOGI("[IR_JNI]: WriteRegister, addr=%d, value=%d", addr, value);
    WriteRegister(addr, value);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_ftd_gyn_IrisguiActicity_RawCamSetLed(
        JNIEnv *env,
        jobject obj,/* this */
        jint led1,
        jint led2) {
    LOGI("[IR_JNI]: WriteRegister, led1=%d, led2=%d", led1, led2);
    SetLed(led1, led2);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_ftd_gyn_IrisguiActicity_RawCamRegisterFrameCallback(
        JNIEnv *env,
        jobject obj/* this */) {
    LOGI("[IR_JNI]: RawCam_RegisterFrameCallback...");
    RegisterFrameCallback(dataCallback);
    return 0;
}