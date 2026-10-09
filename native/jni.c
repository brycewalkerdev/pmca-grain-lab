#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include <dlfcn.h>
#include "sony_bounds.h"
#include "grain.h"
#include "film.h"
static void throw_error(JNIEnv *env, const char *text) {
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type) (*env)->ThrowNew(env, type, text);
}
static int settings(JNIEnv *env, jintArray values, FilmSettings *s) {
    if (!values || (*env)->GetArrayLength(env, values) != F_COUNT) { throw_error(env, "Invalid effect settings"); return 0; }
    (*env)->GetIntArrayRegion(env, values, 0, F_COUNT, (jint *)s->v);
    if ((*env)->ExceptionCheck(env)) return 0;
    if (!film_valid(s)) { throw_error(env, "Invalid effect settings"); return 0; }
    return 1;
}
/* Writes are used only on the probe's owned scratch buffer and allocated image. */
JNIEXPORT jint JNICALL Java_com_bryce_grainlab_NativeGrain_sonyWrite(JNIEnv *env,jclass cls,jobject memory,jint offset,jbyteArray input){
    typedef int (*type_fn)(void *);
    typedef int (*property_fn)(void *,int,int *);
    typedef int (*write_fn)(void *,const void *,unsigned int,unsigned int);
    int count=input?(*env)->GetArrayLength(env,input):0;
    if(sizeof(void *)!=4||!memory||!sony_read_bounds(0x7fffffff,offset,count)){throw_error(env,"Invalid bounded Sony write");return -1;}
    jclass type=(*env)->FindClass(env,"com/sony/scalar/hardware/DeviceMemory");if(!type)return -1;
    if(!(*env)->IsInstanceOf(env,memory,type)){throw_error(env,"Expected Sony DeviceMemory");return -1;}
    jmethodID valid=(*env)->GetMethodID(env,type,"isValid","()Z");if(!valid)return -1;
    if(!(*env)->CallBooleanMethod(env,memory,valid)||(*env)->ExceptionCheck(env)){if(!(*env)->ExceptionCheck(env))throw_error(env,"Released Sony memory");return -1;}
    jfieldID field=(*env)->GetFieldID(env,type,"mNativeMemory","I");if(!field)return -1;
    uintptr_t handle=(uintptr_t)(uint32_t)(*env)->GetIntField(env,memory,field);
    if(!handle){throw_error(env,"Empty Sony handle");return -1;}
    unsigned char bytes[256];(*env)->GetByteArrayRegion(env,input,0,count,(jbyte *)bytes);if((*env)->ExceptionCheck(env))return -1;
    void *library=dlopen("libcameraseq.so",RTLD_NOW|RTLD_LOCAL);
    if(!library){throw_error(env,"Sony camera sequence library unavailable");return -1;}
    type_fn get_type=(type_fn)dlsym(library,"_ZN6scalar18DeviceMemoryDiadem7getTypeEv");
    property_fn property=(property_fn)dlsym(library,"_ZN6scalar18DeviceMemoryDiadem17getPropertyDiademEiPi");
    write_fn write=(write_fn)dlsym(library,"_ZN6scalar18DeviceMemoryDiadem11writeBufferEPKvjj");
    const char *error=NULL;int size=0,result=-1;
    if(!get_type||!property||!write)error="Required Sony write symbols unavailable";
    else{
        int kind=get_type((void *)handle);
        if(kind!=1&&kind!=2)error="Unsupported Sony memory type";
        else if(!property((void *)handle,2,&size)||!sony_read_bounds(size,offset,count))error="Sony write exceeds memory bounds";
        else{result=write((void *)handle,bytes,(unsigned int)count,(unsigned int)offset);if(result!=count)error="Sony buffer write incomplete";}
    }
    dlclose(library);if(error){throw_error(env,error);return -1;}return result;
}
/* No raw pointer dereference, private-layout offsets, aliases, writes or releases.
 * The SDK handle is passed back to the firmware's exported, bounded methods. */
JNIEXPORT jbyteArray JNICALL Java_com_bryce_grainlab_NativeGrain_sonyRead(JNIEnv *env,jclass cls,jobject memory,jint offset,jint count){
    typedef int (*type_fn)(void *);
    typedef int (*property_fn)(void *,int,int *);
    typedef int (*read_fn)(void *,void *,unsigned int,unsigned int);
    if(sizeof(void *)!=4||!memory||!sony_read_bounds(0x7fffffff,offset,count)){throw_error(env,"Invalid Sony read request or non-32-bit ABI");return NULL;}
    jclass type=(*env)->FindClass(env,"com/sony/scalar/hardware/DeviceMemory");if(!type)return NULL;
    if(!(*env)->IsInstanceOf(env,memory,type)){throw_error(env,"Expected Sony DeviceMemory");return NULL;}
    jmethodID valid=(*env)->GetMethodID(env,type,"isValid","()Z");if(!valid)return NULL;
    if(!(*env)->CallBooleanMethod(env,memory,valid)||(*env)->ExceptionCheck(env)){if(!(*env)->ExceptionCheck(env))throw_error(env,"Released Sony memory");return NULL;}
    jfieldID field=(*env)->GetFieldID(env,type,"mNativeMemory","I");if(!field)return NULL;
    uintptr_t handle=(uintptr_t)(uint32_t)(*env)->GetIntField(env,memory,field);
    if(!handle){throw_error(env,"Empty Sony handle");return NULL;}
    void *library=dlopen("libcameraseq.so",RTLD_NOW|RTLD_LOCAL);
    if(!library){throw_error(env,"Sony camera sequence library unavailable");return NULL;}
    type_fn get_type=(type_fn)dlsym(library,"_ZN6scalar18DeviceMemoryDiadem7getTypeEv");
    property_fn property=(property_fn)dlsym(library,"_ZN6scalar18DeviceMemoryDiadem17getPropertyDiademEiPi");
    read_fn read=(read_fn)dlsym(library,"_ZN6scalar18DeviceMemoryDiadem10readBufferEPvjj");
    const char *error=NULL;int size=0,result=-1;unsigned char bytes[256];
    if(!get_type||!property||!read)error="Required Sony buffer symbols unavailable";
    else{
        int kind=get_type((void *)handle);
        if(kind!=1&&kind!=2)error="Unsupported Sony memory type";
        else if(!property((void *)handle,2,&size)||!sony_read_bounds(size,offset,count))error="Sony read exceeds memory bounds";
        else{result=read((void *)handle,bytes,(unsigned int)count,(unsigned int)offset);if(result!=count)error="Sony buffer read incomplete";}
    }
    dlclose(library);
    if(error){throw_error(env,error);return NULL;}
    jbyteArray output=(*env)->NewByteArray(env,count);
    if(output)(*env)->SetByteArrayRegion(env,output,0,count,(jbyte *)bytes);return output;
}
JNIEXPORT void JNICALL Java_com_bryce_grainlab_NativeGrain_reset(JNIEnv *e, jclass c) { grain_reset(); }
JNIEXPORT void JNICALL Java_com_bryce_grainlab_NativeGrain_cancel(JNIEnv *e, jclass c) { grain_cancel(); }
JNIEXPORT jint JNICALL Java_com_bryce_grainlab_NativeGrain_progress(JNIEnv *e, jclass c) { return grain_progress(); }
JNIEXPORT jint JNICALL Java_com_bryce_grainlab_NativeGrain_stage(JNIEnv *e,jclass c){return film_stage();}
JNIEXPORT jstring JNICALL Java_com_bryce_grainlab_NativeGrain_backend(JNIEnv *e,jclass c){return (*e)->NewStringUTF(e,film_backend());}
JNIEXPORT jdoubleArray JNICALL Java_com_bryce_grainlab_NativeGrain_timings(JNIEnv *e,jclass c){
    double values[6];film_timings(values);jdoubleArray array=(*e)->NewDoubleArray(e,6);
    if(array)(*e)->SetDoubleArrayRegion(e,array,0,6,values);return array;
}
JNIEXPORT void JNICALL Java_com_bryce_grainlab_NativeGrain_process(JNIEnv *env, jclass cls,
    jstring source, jstring target, jintArray values) {
    FilmSettings s; if (!settings(env, values, &s)) return;
    const char *input = (*env)->GetStringUTFChars(env, source, NULL); if (!input) return;
    const char *output = (*env)->GetStringUTFChars(env, target, NULL);
    if (!output) { (*env)->ReleaseStringUTFChars(env, source, input); return; }
    char message[256] = "Processing failed";
    int result = film_process(input, output, &s, message, sizeof(message));
    (*env)->ReleaseStringUTFChars(env, source, input); (*env)->ReleaseStringUTFChars(env, target, output);
    if (result) throw_error(env, result == 1 ? "Cancelled" : message);
}
JNIEXPORT jintArray JNICALL Java_com_bryce_grainlab_NativeGrain_preview(JNIEnv *env, jclass cls,
    jstring source, jintArray values, jboolean detail) {
    FilmSettings s; if (!settings(env, values, &s)) return NULL;
    const char *input = (*env)->GetStringUTFChars(env, source, NULL); if (!input) return NULL;
    uint32_t *pixels = NULL; int width = 0, height = 0; char message[256] = "Preview failed";
    int result = film_preview(input, &s, detail, &pixels, &width, &height, message, sizeof(message));
    (*env)->ReleaseStringUTFChars(env, source, input);
    if (result) { free(pixels); throw_error(env, result == 1 ? "Cancelled" : message); return NULL; }
    jintArray array = (*env)->NewIntArray(env, 2 + width * height);
    if (array) {
        jint dimensions[2] = {width, height};
        (*env)->SetIntArrayRegion(env, array, 0, 2, dimensions);
        (*env)->SetIntArrayRegion(env, array, 2, width * height, (jint *)pixels);
    }
    free(pixels); return array;
}
