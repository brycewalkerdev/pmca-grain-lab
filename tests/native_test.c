#include "sony_upload.h"
#include "sony_bounds.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "jpeglib.h"
#include "grain.h"
#include "film.h"
#include "reference.h"
#define CHECK(c, msg) do { if (!(c)) { fprintf(stderr, "FAIL: %s\n", msg); exit(1); } } while (0)
static const unsigned char exif[] = {
    'E','x','i','f',0,0, 'I','I',42,0,8,0,0,0,
    1,0, 0x12,1,3,0,1,0,0,0,6,0,0,0, 26,0,0,0,
    0,0,0,0,0,0
};
static int patterned;
static int dimension_exif;
static void le32(unsigned char *p,unsigned int n){for(int i=0;i<4;i++)p[i]=(unsigned char)(n>>(i*8));}
static void dimension_marker(struct jpeg_compress_struct *c,int width,int height){
    unsigned char data[104]={0};memcpy(data,"Exif\0\0II",8);data[8]=42;data[10]=8;data[14]=4;
    unsigned int tags[4]={0x112,0x100,0x101,0x8769},values[4]={6,(unsigned int)width,(unsigned int)height,62};
    for(int i=0;i<4;i++){unsigned char *p=data+16+i*12;p[0]=tags[i];p[1]=tags[i]>>8;p[2]=i?4:3;p[4]=1;le32(p+8,values[i]);}
    le32(data+64,92);data[68]=2;
    for(int i=0;i<2;i++){unsigned char *p=data+70+i*12;p[0]=2+i;p[1]=0xa0;p[2]=4;p[4]=1;le32(p+8,i?height:width);}
    if(dimension_exif==2){
        data[6]=data[7]='M';data[8]=0;data[9]=42;data[10]=0;data[13]=8;data[14]=0;data[15]=4;
        for(int i=0;i<6;i++){
            unsigned char *p=i<4?data+16+i*12:data+70+(i-4)*12;
            unsigned char n=p[0];p[0]=p[1];p[1]=n;n=p[2];p[2]=p[3];p[3]=n;
            p[4]=p[5]=p[6]=0;p[7]=1;
            if(p[3]==3){n=p[8];p[8]=p[9];p[9]=n;}
            else{n=p[8];p[8]=p[11];p[11]=n;n=p[9];p[9]=p[10];p[10]=n;}
        }
        data[64]=data[65]=data[66]=0;data[67]=92;data[68]=0;data[69]=2;
    }
    jpeg_write_marker(c,JPEG_APP0+1,data,sizeof(data));
}
static void fixture(const char *path, int width, int height, int progressive) {
    FILE *f = fopen(path, "wb"); CHECK(f, "fixture open");
    struct jpeg_compress_struct c; struct jpeg_error_mgr e;
    c.err = jpeg_std_error(&e); jpeg_create_compress(&c); jpeg_stdio_dest(&c, f);
    c.image_width = width; c.image_height = height; c.input_components = 3; c.in_color_space = JCS_RGB;
    jpeg_set_defaults(&c); jpeg_set_quality(&c, 98, TRUE);
    if (progressive) jpeg_simple_progression(&c);
    jpeg_start_compress(&c, TRUE);
    if(dimension_exif)dimension_marker(&c,width,height);else jpeg_write_marker(&c, JPEG_APP0 + 1, exif, sizeof(exif));
    jpeg_write_marker(&c, JPEG_COM, (const unsigned char *)"Grain Lab fixture", 17);
    jpeg_write_marker(&c, JPEG_APP0+2, (const unsigned char *)"MPF\0stale-index", 15);
    jpeg_write_marker(&c, JPEG_APP0+2, (const unsigned char *)"ICC_PROFILE\0\1\1test", 18);
    unsigned char *row = malloc(width * 3); CHECK(row, "fixture row");
    while (c.next_scanline < c.image_height) {
        for (int x=0; x<width; x++) {
            row[x*3] = patterned ? c.next_scanline * 3 % 256 : 96;
            row[x*3+1] = patterned ? x * 3 % 256 : 128;
            row[x*3+2] = patterned ? (x + c.next_scanline) * 2 % 256 : 160;
        }
        JSAMPROW r = row; jpeg_write_scanlines(&c, &r, 1);
    }
    jpeg_finish_compress(&c); jpeg_destroy_compress(&c); free(row); CHECK(!fclose(f), "fixture close");
}
static double verify(const char *path, int width, int height) {
    FILE *f = fopen(path,"rb"); CHECK(f,"output readable");
    struct jpeg_decompress_struct c; struct jpeg_error_mgr e;
    c.err=jpeg_std_error(&e); jpeg_create_decompress(&c); jpeg_stdio_src(&c,f);
    jpeg_save_markers(&c,JPEG_APP0+1,65535); jpeg_save_markers(&c,JPEG_APP0+2,65535); jpeg_save_markers(&c,JPEG_COM,65535);
    jpeg_read_header(&c,TRUE); CHECK(c.image_width==width && c.image_height==height,"full resolution retained");
    int found=0, comment=0, icc=0;
    for(jpeg_saved_marker_ptr m=c.marker_list;m;m=m->next) {
        if(m->marker==JPEG_APP0+1 && m->data_length==sizeof(exif)) {
            found=1; CHECK(m->data[24]==6,"EXIF orientation preserved");
            CHECK(!m->data[28]&&!m->data[29]&&!m->data[30]&&!m->data[31],"stale EXIF thumbnail detached");
        }
        if(m->marker==JPEG_COM) comment=1;
        if(m->marker==JPEG_APP0+2) {
            CHECK(m->data_length<4||memcmp(m->data,"MPF\0",4),"obsolete MPF offsets removed");
            if(m->data_length>=12&&!memcmp(m->data,"ICC_PROFILE\0",12))icc=1;
        }
    }
    CHECK(found && comment && icc,"EXIF, ICC and comment retained");
    c.out_color_space=JCS_YCbCr; jpeg_start_decompress(&c);
    unsigned char *row=malloc(c.output_width*3); double sum=0,sum2=0; long count=0;
    while(c.output_scanline<c.output_height) {
        JSAMPROW r=row; jpeg_read_scanlines(&c,&r,1);
        for(unsigned int x=0;x<c.output_width;x++) { double v=row[x*3];sum+=v;sum2+=v*v;count++; }
    }
    jpeg_finish_decompress(&c);jpeg_destroy_decompress(&c);free(row);fclose(f);
    return sum2/count-(sum/count)*(sum/count);
}
static FilmContext *effect_map(FilmSettings *s, int bright) {
    FilmContext *f = film_create(s, 128, 128, 128, 128); CHECK(f, "effect context");
    unsigned char row[128 * 3];
    for (int y = 0; y < 128; y++) {
        for (int x = 0; x < 128; x++) for (int c = 0; c < 3; c++)
            row[x * 3 + c] = bright && x >= 64 && x <= 84 && y >= 32 && y <= 96 ? 255 : 32;
        film_feed(f, row, y, 128, 128);
    }
    CHECK(!film_finish_map(f), "effect blur"); return f;
}
static void effect_checks(void) {
    FilmSettings s = {{0,2,123,0,100,75,0,100,0,0,0,0,0,0,0,0}};
    unsigned char p[3]; FilmContext *f;
    s.v[F_HALATION] = 100; f = effect_map(&s, 1); p[0]=p[1]=p[2]=32; film_pixel(f,61,64,p);
    CHECK(p[0] > p[1] && p[1] >= p[2] && p[0] > 32, "warm halation outside highlight"); film_free(f);
    f=effect_map(&s,0);p[0]=p[1]=p[2]=32;film_pixel(f,61,64,p);CHECK(p[0]==32&&p[1]==32&&p[2]==32,"halation respects threshold");film_free(f);
    s.v[F_HALATION]=0;s.v[F_BLOOM]=100;f=effect_map(&s,1);p[0]=p[1]=p[2]=32;film_pixel(f,61,64,p);
    CHECK(p[0]>32&&p[0]==p[1]&&p[1]==p[2],"neutral source produces neutral bloom");film_free(f);
    s.v[F_BLOOM]=0;s.v[F_DIFFUSION]=100;f=effect_map(&s,1);p[0]=p[1]=p[2]=255;film_pixel(f,64,64,p);
    CHECK(p[0]<255&&p[0]>100,"diffusion softens bright edge");film_free(f);
    s.v[F_DIFFUSION]=0;s.v[F_VIGNETTE]=100;f=film_create(&s,128,128,0,0);p[0]=p[1]=p[2]=128;film_pixel(f,0,0,p);
    CHECK(p[0]<90,"vignette darkens corners");p[0]=p[1]=p[2]=128;film_pixel(f,64,64,p);CHECK(p[0]>=127,"vignette retains centre");film_free(f);
    s.v[F_VIGNETTE]=0;s.v[F_LEAK]=100;f=film_create(&s,128,128,0,0);int leak=0;
    for(int x=0;x<128;x+=127){p[0]=p[1]=p[2]=32;film_pixel(f,x,64,p);if(p[0]>p[1]&&p[0]>32)leak++;}
    CHECK(leak>0,"light leak illuminates an edge");film_free(f);
    s.v[F_LEAK]=0;
    for(int effect=F_DUST;effect<=F_SCRATCH;effect++) {
        s.v[effect]=100;f=film_create(&s,128,128,0,0);int changed=0;
        for(int y=0;y<128;y++)for(int x=0;x<128;x++){p[0]=p[1]=p[2]=180;film_pixel(f,x,y,p);if(p[0]<180)changed++;}
        CHECK(changed>0&&changed<128*128,"sparse film defects");film_free(f);s.v[effect]=0;
    }
    float sx,sy,rx,ry,bx,by;film_coordinates(&s,10,20,128,128,&sx,&sy,&rx,&ry,&bx,&by);
    CHECK(fabsf(sx-10)<.001f&&fabsf(sy-20)<.001f&&fabsf(rx-sx)<.001f,"disabled lens identity");
    s.v[F_ABERRATION]=100;film_coordinates(&s,10,20,128,128,&sx,&sy,&rx,&ry,&bx,&by);
    CHECK(rx<sx&&bx>sx,"chromatic radial channel separation");
    s.v[F_ABERRATION]=0;s.v[F_DISTORTION]=100;film_coordinates(&s,10,20,128,128,&sx,&sy,&rx,&ry,&bx,&by);CHECK(sx<10,"barrel direction");
    s.v[F_DISTORTION]=-100;film_coordinates(&s,10,20,128,128,&sx,&sy,&rx,&ry,&bx,&by);CHECK(sx>10,"pincushion direction");
    s.v[F_DISTORTION]=0;s.v[F_EDGE_SOFTNESS]=100;CHECK(film_needs_map(&s),"edge softness prepares neighbourhood map");
    s.v[F_GRAIN]=101;CHECK(!film_valid(&s),"invalid native parameter contract");
    puts("PASS: halation, bloom, threshold, diffusion, vignette, leaks, dust, scratches, lens coordinates");
}
static float full_sample(unsigned char *image, int w, int h, float x, float y, int c) {
    int x0=x,y0=y,x1=x0+1<w?x0+1:x0,y1=y0+1<h?y0+1:y0;
    float fx=x-x0,fy=y-y0;
    return (1-fy)*((1-fx)*image[(y0*w+x0)*3+c]+fx*image[(y0*w+x1)*3+c])+
        fy*((1-fx)*image[(y1*w+x0)*3+c]+fx*image[(y1*w+x1)*3+c]);
}
static void lens_reference(const char *source) {
    patterned=1;fixture(source,128,96,0);patterned=0;
    FILE *file=fopen(source,"rb");struct jpeg_decompress_struct d;struct jpeg_error_mgr e;
    d.err=jpeg_std_error(&e);jpeg_create_decompress(&d);jpeg_stdio_src(&d,file);jpeg_read_header(&d,TRUE);
    d.out_color_space=JCS_RGB;jpeg_start_decompress(&d);
    unsigned char *image=malloc(128*96*3);CHECK(image,"reference buffer");
    while(d.output_scanline<d.output_height){JSAMPROW row=image+d.output_scanline*128*3;jpeg_read_scanlines(&d,&row,1);}
    jpeg_finish_decompress(&d);jpeg_destroy_decompress(&d);fclose(file);
    FilmSettings s={{0,2,1,0,30,75,0,40,0,0,0,0,0,100,0,0}};
    for(int direction=-100;direction<=100;direction+=100) {
        s.v[F_DISTORTION]=direction;uint32_t *pixels=NULL;int w,h;char message[256];grain_reset();
        CHECK(film_preview(source,&s,0,&pixels,&w,&h,message,sizeof(message))==0,message);
        CHECK(w==128&&h==96,"reference dimensions");
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            float sx,sy,rx,ry,bx,by;film_coordinates(&s,x,y,w,h,&sx,&sy,&rx,&ry,&bx,&by);
            float xs[3]={rx,sx,bx},ys[3]={ry,sy,by};
            for(int c=0;c<3;c++) {
                int expected=(int)(full_sample(image,w,h,xs[c],ys[c],c)+.5f);
                int actual=(pixels[y*w+x]>>(16-c*8))&255;
                CHECK(abs(expected-actual)<=1,"rolling row lens output matches independent full-image sampling");
            }
        }
        free(pixels);
    }
    free(image);puts("PASS: rolling row lens resampling matches full-image reference for all three directions");
}
static void same_file(const char *a,const char *b){
    FILE *fa=fopen(a,"rb"),*fb=fopen(b,"rb");CHECK(fa&&fb,"copy files");
    unsigned char x[4096],y[4096];size_t nx,ny;
    do{nx=fread(x,1,sizeof(x),fa);ny=fread(y,1,sizeof(y),fb);CHECK(nx==ny&&!memcmp(x,y,nx),"all-off copy is byte identical");}while(nx);
    fclose(fa);fclose(fb);
}
static void pixel_equivalence(void){
    int dimensions[3][2]={{127,95},{320,240},{6000,4000}};
    FilmSettings cases[6]={{{0,2,1,0,30,75,0,40,0,0,0,0,0,0,0,0}},
        {{35,2,1,0,30,75,0,40,0,0,0,0,0,0,0,0}},
        {{0,2,1,60,30,75,40,40,30,20,0,0,0,0,0,20}},
        {{40,3,-123,40,100,40,100,100,70,60,80,100,100,40,70,50}},
        {{60,8,123,30,50,95,60,80,40,80,50,50,50,20,-90,80}},
        {{100,1,0,0,30,75,0,40,0,100,100,100,100,100,0,0}}};
    int worst=0;long count=0;
    for(int dimension=0;dimension<3;dimension++)for(int test=0;test<6;test++){
        int w=dimensions[dimension][0],h=dimensions[dimension][1];
        int map=film_needs_map(&cases[test]);
        FilmContext *f=film_create(&cases[test],w,h,map?32:0,map?24:0);
        ReferenceContext *r=reference_create(&cases[test],w,h,map?32:0,map?24:0);
        CHECK(f&&r,"comparison contexts");
        if(map){unsigned char row[32*3];for(int y=0;y<24;y++){
            for(int x=0;x<32;x++){row[x*3]=(x*19+y*17)&255;row[x*3+1]=(x*13+y*23)&255;row[x*3+2]=(x*7+y*31)&255;}
            film_feed(f,row,y,32,24);reference_feed(r,row,y,32,24);
        }CHECK(!film_finish_map(f)&&!reference_finish_map(r),"comparison maps");}
        for(int n=0;n<3000;n++){
            int x=(n*37)%w,y=(n*29)%h;unsigned char a[3]={(n*7)&255,(n*13)&255,(n*31)&255},b[3];memcpy(b,a,3);
            film_pixel(f,x,y,a);reference_pixel(r,x,y,b);
            for(int c=0;c<3;c++){int delta=abs(a[c]-b[c]);if(delta>worst)worst=delta;CHECK(delta<=1,"optimized pixel output stays within one code value of original");count++;}
        }
        film_free(f);reference_free(r);
    }
    printf("PASS: %ld optimized/reference channel comparisons; maximum difference %d/255\n",count,worst);
}
static unsigned int get_le32(const unsigned char *p){return p[0]|(unsigned int)p[1]<<8|(unsigned int)p[2]<<16|(unsigned int)p[3]<<24;}
static unsigned int get_exif32(const unsigned char *p,int little){return little?get_le32(p):(unsigned int)p[0]<<24|(unsigned int)p[1]<<16|(unsigned int)p[2]<<8|p[3];}
static void half_resolution(const char *source,const char *output){
    for(int endian=1;endian<=2;endian++){
    dimension_exif=endian;fixture(source,640,480,0);dimension_exif=0;
    FilmSettings s={{30,2,123,30,40,75,20,50,10,20,10,10,10,20,50,20,1}};char message[256];grain_reset();
    CHECK(!film_process(source,output,&s,message,sizeof(message)),message);
    FILE *f=fopen(output,"rb");CHECK(f,"half output open");struct jpeg_decompress_struct d;struct jpeg_error_mgr e;
    d.err=jpeg_std_error(&e);jpeg_create_decompress(&d);jpeg_stdio_src(&d,f);jpeg_save_markers(&d,JPEG_APP0+1,65535);jpeg_read_header(&d,TRUE);
    CHECK(d.image_width==320&&d.image_height==240,"half dimensions");int found=0;
    for(jpeg_saved_marker_ptr m=d.marker_list;m;m=m->next)if(m->data_length==104&&!memcmp(m->data,"Exif\0\0",6)){
        int little=m->data[6]=='I';found=1;CHECK(m->data[little?24:25]==6,"half orientation preserved");
        CHECK(get_exif32(m->data+36,little)==320&&get_exif32(m->data+48,little)==240,"half TIFF dimensions updated");
        CHECK(get_exif32(m->data+78,little)==320&&get_exif32(m->data+90,little)==240,"half EXIF pixel dimensions updated");
        CHECK(get_le32(m->data+64)==0,"half thumbnail pointer detached");
    }CHECK(found,"half EXIF present");jpeg_destroy_decompress(&d);fclose(f);
    double times[6];film_timings(times);CHECK(times[5]>=0&&film_stage()==4&&grain_progress()==100,"timings and finished stage");
    }
    puts("PASS: half resolution, resized EXIF/TIFF dimensions, orientation, completed stage and timings");
}
int main(int argc,char **argv) {
    int layout[8]={4,2,8,4,0,2,1,64};unsigned char packed[16],ycc[12]={10,20,30,40,60,80,90,100,110,120,130,140};
    CHECK(sony_layout_valid(layout,4,2),"Sony upload padded viewport geometry");layout[7]=63;CHECK(!sony_layout_valid(layout,4,2),"Sony upload undersized allocation refused");layout[7]=64;layout[5]=3;CHECK(!sony_layout_valid(layout,4,2),"Sony upload odd chroma offset refused");layout[5]=2;
    sony_pack_row(packed,ycc,4,8,2,3);CHECK(packed[0]==128&&packed[1]==0&&packed[4]==40&&packed[5]==10&&packed[6]==55&&packed[7]==40&&packed[8]==115&&packed[9]==90&&packed[10]==125&&packed[11]==120&&packed[12]==128,"Sony upload full-range UYVY pair averaging and padding");
    unsigned char gray[4]={0,1,254,255};sony_pack_row(packed,gray,4,8,2,1);CHECK(packed[4]==128&&packed[5]==0&&packed[6]==128&&packed[7]==1,"Sony upload grayscale chroma neutral");
    CHECK(sony_read_bounds(4096,0,64)&&sony_read_bounds(4096,4032,64),"Sony read valid endpoints");
    CHECK(!sony_read_bounds(4096,-1,64)&&!sony_read_bounds(4096,4033,64)&&!sony_read_bounds(4096,0,257)&&!sony_read_bounds(4096,0,0)&&!sony_read_bounds(4096,2147483647,64),"Sony read bounds and overflow");

    CHECK(argc==2,"test output directory");
    effect_checks();
    pixel_equivalence();
    char source[1024],output[1024],other[1024],message[256];
    snprintf(source,sizeof(source),"%s/source.jpg",argv[1]);
    snprintf(output,sizeof(output),"%s/grain.jpg",argv[1]);
    snprintf(other,sizeof(other),"%s/other.jpg",argv[1]);
    lens_reference(source);
    fixture(source,640,480,0);
    for(int s=1;s<=8;s++) for(int y=0;y<100;y++) for(int x=0;x<100;x++) {
        CHECK(grain_delta(x,y,128,0,s,1)==0,"zero strength");
        CHECK(grain_delta(x,y,128,100,s,1)==grain_delta(x,y,128,100,s,1),"deterministic texture");
        CHECK(abs(grain_delta(x,y,128,100,s,1))<=77,"bounded grain");
    }
    grain_reset();CHECK(grain_process(source,output,70,2,123,message,sizeof(message))==0,message);
    CHECK(grain_progress()==100,"progress completes");
    double variance=verify(output,640,480);CHECK(variance>10,"grain produces luminance texture");
    grain_reset();CHECK(grain_process(source,other,0,2,123,message,sizeof(message))==0,message);
    same_file(source,other);
    grain_reset();grain_cancel();CHECK(grain_process(source,other,70,2,123,message,sizeof(message))==1,"cancellation");
    grain_reset();CHECK(grain_process(source,source,70,2,123,message,sizeof(message))==-1,"reject source overwrite");
    CHECK(grain_process(source,other,70,0,123,message,sizeof(message))==-1,"reject invalid size");
    FILE *bad=fopen(other,"wb");fputs("not a jpeg",bad);fclose(bad);
    CHECK(grain_process(other,output,70,2,123,message,sizeof(message))==-1,"malformed JPEG is recoverable");
    fixture(source,640,480,1);grain_reset();
    CHECK(grain_process(source,output,70,2,123,message,sizeof(message))==-1,"progressive rejected before full-image buffering");
    fixture(source,6000,4000,0);grain_reset();
    CHECK(grain_process(source,output,35,2,123,message,sizeof(message))==0,message);
    verify(output,6000,4000);
    FilmSettings all = {{35,2,123,40,30,75,30,40,20,20,20,20,15,50,100,20}};
    grain_reset();CHECK(film_process(source,output,&all,message,sizeof(message))==0,message);verify(output,6000,4000);
    all.v[F_DISTORTION]=-100;grain_reset();CHECK(film_process(source,output,&all,message,sizeof(message))==0,message);
    uint32_t *preview=NULL;int w=0,h=0;grain_reset();CHECK(film_preview(source,&all,1,&preview,&w,&h,message,sizeof(message))==0,message);
    CHECK(w==640&&h==300&&preview,"bounded detailed preview");free(preview);preview=NULL;
    grain_reset();CHECK(film_preview(source,&all,0,&preview,&w,&h,message,sizeof(message))==0,message);
    CHECK(w<=960&&h<=640&&preview,"bounded fit preview");free(preview);
    puts("PASS: all effects together at 24 MP, both lens directions, shared preview/export engine");
    half_resolution(source,other);
    puts("PASS: native grain, full-resolution 24 MP save, metadata, zero strength, cancellation, malformed/progressive input");
    return 0;
}
