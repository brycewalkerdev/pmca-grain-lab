#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <setjmp.h>
#include <dlfcn.h>
#include "jpeglib.h"
#include "sony_upload.h"
typedef struct {struct jpeg_error_mgr base;jmp_buf jump;char message[JMSG_LENGTH_MAX];} UploadError;
typedef struct {struct jpeg_decompress_struct jpeg;UploadError error;FILE *file;void *library;unsigned char *block;int created;} Upload;
static void jpeg_failed(j_common_ptr c){UploadError *e=(UploadError *)c->err;(*c->err->format_message)(c,e->message);longjmp(e->jump,1);}
static void fail(JNIEnv *env,const char *message){jclass cls=(*env)->FindClass(env,"java/io/IOException");if(cls)(*env)->ThrowNew(env,cls,message);}
static void clean(Upload *u){if(u->created)jpeg_destroy_decompress(&u->jpeg);if(u->file)fclose(u->file);if(u->library)dlclose(u->library);free(u->block);free(u);}
JNIEXPORT jintArray JNICALL Java_com_bryce_grainlab_NativeGrain_sonyJpegSize(JNIEnv *env,jclass cls,jstring path){
    Upload *u=(Upload *)calloc(1,sizeof(*u));if(!u){fail(env,"JPEG header allocation failed");return NULL;}
    u->jpeg.err=jpeg_std_error(&u->error.base);u->error.base.error_exit=jpeg_failed;
    if(setjmp(u->error.jump)){fail(env,u->error.message);clean(u);return NULL;}
    const char *name=(*env)->GetStringUTFChars(env,path,NULL);if(!name){clean(u);return NULL;}u->file=fopen(name,"rb");(*env)->ReleaseStringUTFChars(env,path,name);
    if(!u->file){fail(env,"Cannot open processed JPEG");clean(u);return NULL;}
    u->created=1;jpeg_create_decompress(&u->jpeg);jpeg_stdio_src(&u->jpeg,u->file);jpeg_read_header(&u->jpeg,TRUE);
    jint values[2]={(jint)u->jpeg.image_width,(jint)u->jpeg.image_height};jintArray result=(*env)->NewIntArray(env,2);if(result)(*env)->SetIntArrayRegion(env,result,0,2,values);clean(u);return result;
}
JNIEXPORT void JNICALL Java_com_bryce_grainlab_NativeGrain_sonyUpload(JNIEnv *env,jclass cls,jobject target,jstring path,jintArray geometry){
    typedef int (*type_fn)(void *);typedef int (*property_fn)(void *,int,int *);typedef int (*write_fn)(void *,const void *,unsigned int,unsigned int);
    int v[8];if(sizeof(void *)!=4||!target||!geometry||(*env)->GetArrayLength(env,geometry)!=8){fail(env,"Invalid Sony upload arguments");return;}
    (*env)->GetIntArrayRegion(env,geometry,0,8,v);if((*env)->ExceptionCheck(env))return;
    jclass memory=(*env)->FindClass(env,"com/sony/scalar/hardware/DeviceMemory");if(!memory)return;
    if(!(*env)->IsInstanceOf(env,target,memory)){fail(env,"Expected Sony image memory");return;}
    jmethodID valid=(*env)->GetMethodID(env,memory,"isValid","()Z");if(!valid)return;
    if(!(*env)->CallBooleanMethod(env,target,valid)||(*env)->ExceptionCheck(env)){if(!(*env)->ExceptionCheck(env))fail(env,"Released Sony upload target");return;}
    jfieldID field=(*env)->GetFieldID(env,memory,"mNativeMemory","I");if(!field)return;
    void *handle=(void *)(uintptr_t)(uint32_t)(*env)->GetIntField(env,target,field);if(!handle){fail(env,"Empty Sony upload handle");return;}
    Upload *u=(Upload *)calloc(1,sizeof(*u));if(!u){fail(env,"Upload allocation failed");return;}
    u->jpeg.err=jpeg_std_error(&u->error.base);u->error.base.error_exit=jpeg_failed;
    if(setjmp(u->error.jump)){fail(env,u->error.message);clean(u);return;}
    const char *name=(*env)->GetStringUTFChars(env,path,NULL);if(!name){clean(u);return;}u->file=fopen(name,"rb");(*env)->ReleaseStringUTFChars(env,path,name);
    if(!u->file){fail(env,"Cannot open processed JPEG for upload");clean(u);return;}
    u->created=1;jpeg_create_decompress(&u->jpeg);jpeg_stdio_src(&u->jpeg,u->file);jpeg_read_header(&u->jpeg,TRUE);
    if(!sony_layout_valid(v,(int)u->jpeg.image_width,(int)u->jpeg.image_height)||(u->jpeg.num_components!=1&&u->jpeg.num_components!=3)||u->jpeg.progressive_mode){fail(env,"Processed JPEG does not match supported Sony canvas");clean(u);return;}
    u->library=dlopen("libcameraseq.so",RTLD_NOW|RTLD_LOCAL);if(!u->library){fail(env,"Sony upload library unavailable");clean(u);return;}
    type_fn type=(type_fn)dlsym(u->library,"_ZN6scalar18DeviceMemoryDiadem7getTypeEv");property_fn property=(property_fn)dlsym(u->library,"_ZN6scalar18DeviceMemoryDiadem17getPropertyDiademEiPi");write_fn write=(write_fn)dlsym(u->library,"_ZN6scalar18DeviceMemoryDiadem11writeBufferEPKvjj");
    int size=0;if(!type||!property||!write||type(handle)!=2||!property(handle,2,&size)||size!=v[7]){fail(env,"Sony upload allocation validation failed");clean(u);return;}
    u->jpeg.out_color_space=u->jpeg.num_components==1?JCS_GRAYSCALE:JCS_YCbCr;jpeg_start_decompress(&u->jpeg);
    int stride=v[2]*2,rows=65536/stride;u->block=(unsigned char *)malloc((size_t)stride*rows);
    if(!u->block){fail(env,"Upload row allocation failed");clean(u);return;}
    JSAMPARRAY line=(*u->jpeg.mem->alloc_sarray)((j_common_ptr)&u->jpeg,JPOOL_IMAGE,u->jpeg.output_width*u->jpeg.output_components,1);
    while(u->jpeg.output_scanline<u->jpeg.output_height){
        int first=(int)u->jpeg.output_scanline,n=0;
        while(n<rows&&u->jpeg.output_scanline<u->jpeg.output_height){jpeg_read_scanlines(&u->jpeg,line,1);sony_pack_row(u->block+n*stride,line[0],v[0],v[2],v[5],u->jpeg.output_components);n++;}
        int64_t offset=v[4]+(int64_t)(first+v[6])*stride;unsigned int count=(unsigned int)(n*stride);
        if(offset<0||offset>size||count>(unsigned int)(size-offset)||write(handle,u->block,count,(unsigned int)offset)!=(int)count){fail(env,"Sony pixel upload incomplete");clean(u);return;}
    }
    jpeg_finish_decompress(&u->jpeg);clean(u);
}
