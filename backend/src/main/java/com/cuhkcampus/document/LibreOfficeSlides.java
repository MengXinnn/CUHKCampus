package com.cuhkcampus.document;

import com.sun.star.beans.PropertyValue;
import com.sun.star.bridge.XUnoUrlResolver;
import com.sun.star.comp.helper.Bootstrap;
import com.sun.star.drawing.XDrawPagesSupplier;
import com.sun.star.frame.XComponentLoader;
import com.sun.star.frame.XDesktop;
import com.sun.star.frame.XStorable;
import com.sun.star.lang.XComponent;
import com.sun.star.uno.UnoRuntime;
import com.sun.star.uno.XComponentContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.Comparator;
import java.util.function.IntConsumer;

@Component
public class LibreOfficeSlides {
    private final String binary;
    public LibreOfficeSlides(@Value("${campus.soffice-binary:soffice}") String binary){this.binary=binary;}
    private static PropertyValue property(String name,Object value){var p=new PropertyValue();p.Name=name;p.Value=value;return p;}
    public void render(Path source,Path directory,IntConsumer count,IntConsumer pageReady) throws Exception {
        Files.createDirectories(directory);
        Path profile=Files.createTempDirectory(directory,"office-");
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        Process office=new ProcessBuilder(binary,"-env:UserInstallation="+profile.toUri(),"--headless","--nologo","--nodefault","--norestore",
            "--accept=socket,host=127.0.0.1,port="+port+",tcpNoDelay=1;urp;StarOffice.ComponentContext")
            .redirectErrorStream(true).redirectOutput(directory.resolve("conversion.log").toFile()).start();
        XComponent document=null;
        XDesktop desktop=null;
        try {
            var local=Bootstrap.createInitialComponentContext(null);
            var resolver=UnoRuntime.queryInterface(XUnoUrlResolver.class,local.getServiceManager().createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver",local));
            XComponentContext remote=null;
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while(remote==null) {
                if(!office.isAlive())throw new IllegalStateException("LibreOffice 启动失败，请查看转换日志");
                try {remote=UnoRuntime.queryInterface(XComponentContext.class,resolver.resolve("uno:socket,host=127.0.0.1,port="+port+";urp;StarOffice.ComponentContext"));}
                catch(com.sun.star.connection.NoConnectException waiting) {
                    if(System.nanoTime()>deadline)throw new IllegalStateException("LibreOffice 启动超时",waiting);
                    Thread.sleep(50);
                }
            }
            Object service=remote.getServiceManager().createInstanceWithContext("com.sun.star.frame.Desktop",remote);
            desktop=UnoRuntime.queryInterface(XDesktop.class,service);
            document=UnoRuntime.queryInterface(XComponentLoader.class,service).loadComponentFromURL(source.toUri().toString(),"_blank",0,
                new PropertyValue[]{property("Hidden",true),property("ReadOnly",true),property("MacroExecutionMode",com.sun.star.document.MacroExecMode.NEVER_EXECUTE),property("UpdateDocMode",com.sun.star.document.UpdateDocMode.NO_UPDATE)});
            int pages=UnoRuntime.queryInterface(XDrawPagesSupplier.class,document).getDrawPages().getCount();
            count.accept(pages);
            var exporter=UnoRuntime.queryInterface(XStorable.class,document);
            for(int page=1;page<=pages;page++) {
                Path target=directory.resolve("page-"+page+".pdf");
                if(!Files.isRegularFile(target)) {
                    Path temporary=directory.resolve("page-"+page+".pending.pdf");
                    exporter.storeToURL(temporary.toUri().toString(),new PropertyValue[]{property("FilterName","impress_pdf_Export"),property("Overwrite",true),
                        property("FilterData",new PropertyValue[]{property("PageRange",String.valueOf(page)),property("ExportHiddenSlides",true)})});
                    Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                }
                pageReady.accept(page);
            }
        } finally {
            try {if(document!=null)document.dispose();if(desktop!=null)desktop.terminate();}
            finally {
                office.destroy();
                office.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);
                if(office.isAlive())office.destroyForcibly();
                try(var paths=Files.walk(profile)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
            }
        }
    }
}
