package org.example.seedancegenarate.agent.api;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.agent.application.AgentApplication;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.UserTokenService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;

/** SSE is a replaceable view, never a work queue. Reconnect on any node reads MySQL facts. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentStream {
    private final AgentApplication app;
    private final UserTokenService tokens;
    private final ConcurrentHashMap<SseEmitter,Connection> clients=new ConcurrentHashMap<>();
    private boolean stopping;
    private final ScheduledExecutorService ticks=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"agent-sse-tick");t.setDaemon(true);return t;});
    private final ExecutorService senders=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(100),
            r->{var t=new Thread(r,"agent-sse-send");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final class Connection {
        final long user,id; final String token; long revision=-1,lastHeartbeat=0,lastSnapshot=0;
        final AtomicBoolean sending=new AtomicBoolean();
        final AtomicBoolean closed=new AtomicBoolean();
        Connection(long user,long id,String token) { this.user=user; this.id=id; this.token=token; }
    }
    private record SessionKey(long user,long conversation) {}
    // Owned only by one refresh's tasks (or one open); never stored on a connection.
    private final class RefreshRead {
        private final SessionKey key;
        private Long revision;
        private Boolean media;
        private AgentViews.Snapshot snapshot;
        private RuntimeException failure;
        RefreshRead(SessionKey key) { this.key=key; }
        synchronized long revision() {
            if(failure!=null) throw failure;
            try {
                if(revision==null) revision=app.revision(key.user(),key.conversation());
                return revision;
            } catch(RuntimeException e) { failure=e; throw e; }
        }
        synchronized boolean hasMedia() {
            if(failure!=null) throw failure;
            try {
                if(media==null) media=app.hasMedia(key.user(),key.conversation());
                return media;
            } catch(RuntimeException e) { failure=e; throw e; }
        }
        synchronized AgentViews.Snapshot snapshot() {
            if(failure!=null) throw failure;
            try {
                if(snapshot==null) snapshot=app.snapshot(key.user(),key.conversation());
                return snapshot;
            } catch(RuntimeException e) { failure=e; throw e; }
        }
    }
    public synchronized SseEmitter open(long user,long id,String token) {
        if(stopping) throw new BusinessException(503,"服务正在停止，请稍后重连");
        app.revision(user,id);
        if(clients.size()>=100 || clients.values().stream().filter(c->c.user==user).count()>=3)
            throw new BusinessException(429,"打开的 Agent 页面过多，请关闭其他页面");
        SseEmitter emitter=createEmitter(); Connection c=new Connection(user,id,token);
        clients.put(emitter,c);
        emitter.onCompletion(()->detach(emitter,c));
        emitter.onTimeout(()->detach(emitter,c));
        emitter.onError(e->{ detach(emitter,c); log.debug("Agent SSE transport closed: conversation={}",c.id,e); });
        dispatch(emitter,c,new RefreshRead(new SessionKey(user,id))); return emitter;
    }
    SseEmitter createEmitter() { return new SseEmitter(300_000L); }
    @PostConstruct public void start() { ticks.scheduleWithFixedDelay(this::refresh,2,2,TimeUnit.SECONDS); }
    public void refresh() {
        var reads=new HashMap<SessionKey,RefreshRead>();
        clients.forEach((emitter,c)->dispatch(emitter,c,
                reads.computeIfAbsent(new SessionKey(c.user,c.id),RefreshRead::new)));
    }
    private void dispatch(SseEmitter emitter,Connection c,RefreshRead read) {
        if(c.closed.get() || !c.sending.compareAndSet(false,true)) return;
        try { senders.execute(()->{try { publish(emitter,c,read); } finally { c.sending.set(false); }}); }
        catch(RejectedExecutionException e) { c.sending.set(false); }
    }
    private void publish(SseEmitter emitter,Connection c,RefreshRead read) {
        synchronized(c) {
            if(c.closed.get()) return;
            try {
                long now=System.currentTimeMillis();
                if(now-c.lastHeartbeat>=15000) {
                    var user=tokens.getUserByToken(c.token);
                    if(user==null || user.getId()!=c.user) { complete(emitter,c); return; }
                    if(!write(emitter,c,SseEmitter.event().comment("heartbeat"))) return;
                    c.lastHeartbeat=now;
                }
                long revision=read.revision();
                if(c.closed.get()) return;
                if(revision<=c.revision && !(now-c.lastSnapshot>=15000 && read.hasMedia())) return;
                var snapshot=read.snapshot();
                if(!write(emitter,c,SseEmitter.event().name("snapshot").id(Long.toString(snapshot.revision())).data(snapshot))) return;
                c.revision=snapshot.revision();
                c.lastSnapshot=now;
            } catch(Exception e) {
                log.warn("Agent SSE snapshot failed: conversation={}, user={}",c.id,c.user,e);
                complete(emitter,c);
            }
        }
    }
    private boolean write(SseEmitter emitter,Connection c,SseEmitter.SseEventBuilder event) {
        if(c.closed.get()) return false;
        try { emitter.send(event); return !c.closed.get(); }
        catch(IOException | IllegalStateException e) {
            // Spring/the servlet container owns completion after a failed write.
            detach(emitter,c);
            log.debug("Agent SSE send stopped: conversation={}",c.id,e);
            return false;
        }
    }
    private boolean detach(SseEmitter emitter,Connection c) {
        boolean first=c.closed.compareAndSet(false,true);
        clients.remove(emitter,c);
        return first;
    }
    private void complete(SseEmitter emitter,Connection c) {
        if(!detach(emitter,c)) return;
        try { emitter.complete(); }
        catch(RuntimeException e) {
            // onError may win between our closed check and the container dispatch.
            log.debug("Agent SSE completion raced with container cleanup: conversation={}",c.id,e);
        }
    }
    @PreDestroy public synchronized void close() {
        stopping=true;
        ticks.shutdownNow(); senders.shutdownNow();
        clients.forEach(this::complete);
        clients.clear();
    }
}
