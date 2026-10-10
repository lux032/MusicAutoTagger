package com.lux032.musicautotagger.web;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.service.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LyricsBackfillServletTest {
    @TempDir Path dir;
    static class FakeBackfill extends LyricsBackfillService {
        boolean started, preview, cancelled;
        FakeBackfill(MusicConfig config,LyricsService lyrics) { super(config,lyrics,new TagWriterService(config)); }
        public synchronized boolean start(boolean preview) { if(started)return false; started=true;this.preview=preview;return true; }
        public synchronized void cancel() { cancelled=true; }
        public synchronized Map<String,Object> getStatus() { return Map.of("running",started,"preview",preview); }
    }
    HttpServletRequest request(String action, boolean auth, boolean csrf) {
        HttpSession session=(HttpSession)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpSession.class},(p,m,a)->m.getName().equals("getAttribute")?(a[0].equals("authUser")?(auth?"admin":null):"token"):null);
        return (HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->switch(m.getName()) {
            case "getPathInfo"->"/"+action; case "getRequestURI"->"/api/lyrics-backfill/"+action;
            case "getSession"->session; case "getHeader"->csrf?"token":"wrong"; default->null;
        });
    }
    static class Response {
        int status; StringWriter body=new StringWriter();
        HttpServletResponse servlet() { return (HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletResponse.class},(p,m,a)->{
            if(m.getName().equals("setStatus"))status=(int)a[0];
            return m.getName().equals("getWriter")?new PrintWriter(body):null;
        }); }
    }
    @Test void authCsrfPreviewConflictCancelAndStatus() throws Exception {
        var ctor=MusicConfig.class.getDeclaredConstructor();ctor.setAccessible(true);var config=ctor.newInstance();config.setOutputDirectory(dir.toString());
        try(var lyrics=new LyricsService(config);var service=new FakeBackfill(config,lyrics)) {
            var servlet=new LyricsBackfillServlet(service);
            Response response=new Response(); boolean[] called={false};
            new AuthFilter().doFilter(request("status",false,true),response.servlet(),(req,resp)->called[0]=true);
            assertEquals(401,response.status);assertFalse(called[0]);
            response=new Response();servlet.doPost(request("start",true,false),response.servlet());assertEquals(403,response.status);assertFalse(service.started);
            response=new Response();servlet.doPost(request("preview",true,true),response.servlet());assertEquals(200,response.status);assertTrue(service.preview);
            response=new Response();servlet.doPost(request("start",true,true),response.servlet());assertEquals(409,response.status);
            response=new Response();servlet.doGet(request("status",true,true),response.servlet());assertEquals(200,response.status);assertTrue(response.body.toString().contains("preview"));
            response=new Response();servlet.doPost(request("cancel",true,true),response.servlet());assertEquals(200,response.status);assertTrue(service.cancelled);
        }
    }
}
