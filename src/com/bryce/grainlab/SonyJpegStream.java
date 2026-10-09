package com.bryce.grainlab;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.concurrent.CountDownLatch;

/** Same native stream operation as public encode, with its callback code retained. */
final class SonyJpegStream {
    static InputStream encode(Object exporter,Object image,Object options,StringBuilder report,SonyAcceleration.Observer observer)throws Exception{
        Class<?> listener=Class.forName("com.sony.scalar.graphics.JpegExporter$onEventListener");
        final CountDownLatch finished=new CountDownLatch(1);final int[] error={Integer.MIN_VALUE};final InputStream[] stream={null};
        Object callback=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},new InvocationHandler(){
            public Object invoke(Object proxy,Method method,Object[] args)throws Exception{
                String name=method.getName();
                if(name.equals("onEvent")){error[0]=((Integer)args[0]).intValue();stream[0]=(InputStream)args[1];finished.countDown();return null;}
                if(name.equals("waitEvent")){finished.await();return null;}
                if(name.equals("getInputStream"))return stream[0];
                if(name.equals("hashCode"))return Integer.valueOf(System.identityHashCode(proxy));
                if(name.equals("equals"))return Boolean.valueOf(proxy==args[0]);
                if(name.equals("toString"))return "Grain Lab JPEG callback";
                return null;
            }
        });
        Method encode=exporter.getClass().getDeclaredMethod("EncodeImage",Class.forName("com.sony.scalar.graphics.OptimizedImage"),options.getClass(),boolean.class,int.class,listener);
        encode.setAccessible(true);encode.invoke(null,image,options,true,0,callback);
        // Outer probe bounds the UI wait. Keep image/exporter alive until callback.
        finished.await();report.append("JPEG stream callback: ").append(error[0]).append(", stream=").append(stream[0]!=null).append('\n');
        if(observer!=null)observer.progress(report.toString());
        if(error[0]!=0){if(stream[0]!=null)stream[0].close();throw new IllegalStateException("Sony JPEG encode error "+error[0]);}
        if(stream[0]==null)throw new IllegalStateException("Sony reported JPEG success without a stream");
        return stream[0];
    }
}
