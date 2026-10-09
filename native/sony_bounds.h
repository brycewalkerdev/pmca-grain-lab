#ifndef SONY_BOUNDS_H
#define SONY_BOUNDS_H
/* Small reads only; subtraction prevents offset+count overflow. */
static int sony_read_bounds(int size,int offset,int count){
    return size>0&&offset>=0&&count>0&&count<=256&&offset<=size&&count<=size-offset;
}
#endif
