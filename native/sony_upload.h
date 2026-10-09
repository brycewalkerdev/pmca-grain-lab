#ifndef SONY_UPLOAD_H
#define SONY_UPLOAD_H
#include <stdint.h>
/* target w/h, canvas w/h, byte offset, target x/y, allocation bytes */
static int sony_layout_valid(const int *v,int w,int h){
    return v&&w>0&&h>0&&w<=8192&&h<=8192&&!(w&1)&&v[0]==w&&v[1]==h&&v[2]>=w&&v[2]<=8192&&!(v[2]&1)&&v[3]>=h&&v[3]<=8192&&v[4]>=0&&v[5]>=0&&!(v[5]&1)&&v[6]>=0&&(int64_t)v[5]+w<=v[2]&&(int64_t)v[6]+h<=v[3]&&(int64_t)v[4]+2LL*v[2]*v[3]<=v[7];
}
static void sony_pack_row(unsigned char *dst,const unsigned char *src,int w,int canvas,int x,int components){
    int i;for(i=0;i<canvas;i+=2){dst[2*i]=128;dst[2*i+1]=0;dst[2*i+2]=128;dst[2*i+3]=0;}
    for(i=0;i<w;i+=2){unsigned char *p=dst+2*(x+i);
        if(components==1){p[0]=p[2]=128;p[1]=src[i];p[3]=src[i+1];}
        else{p[0]=(unsigned char)(((unsigned)src[3*i+1]+src[3*i+4]+1)/2);p[1]=src[3*i];p[2]=(unsigned char)(((unsigned)src[3*i+2]+src[3*i+5]+1)/2);p[3]=src[3*i+3];}
    }
}
#endif
