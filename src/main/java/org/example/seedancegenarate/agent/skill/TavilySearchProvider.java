package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import static org.example.seedancegenarate.agent.skill.SearchProvider.*;

@Component
public class TavilySearchProvider implements SearchProvider {
    private final String key;
    private final ObjectMapper json;
    private final HttpClient http;
    @Autowired
    public TavilySearchProvider(@Value("${TAVILY_API_KEY:}") String key,ObjectMapper json) {
        this(key,json,HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build());
    }
    TavilySearchProvider(String key,ObjectMapper json,HttpClient http) {this.key=key;this.json=json;this.http=http;}
    public boolean available() {return key!=null&&!key.isBlank();}
    public Result search(Request request) {
        if(!available())throw new Failure("SEARCH_NOT_CONFIGURED",false);
        try {
            var body=json.createObjectNode().put("query",request.query()).put("search_depth","basic")
                    .put("max_results",5).put("include_answer",false).put("include_raw_content",false).put("include_images",false);
            body.set("include_domains",json.valueToTree(request.domains()));
            if(request.timeRange()!=null)body.put("time_range",request.timeRange());
            var call=HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+key)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var response=http.send(call,info->new BoundedBody());
            {
                int status=response.statusCode();
                if(status!=200)throw new Failure(status==429?"SEARCH_RATE_LIMITED":status==401||status==403?
                        "SEARCH_AUTHENTICATION_FAILED":status==432||status==433?"SEARCH_QUOTA_EXHAUSTED":status>=500?"SEARCH_TEMPORARILY_UNAVAILABLE":"SEARCH_INVALID_REQUEST",status==429||status>=500);
                byte[] bytes=response.body();
                if(bytes.length>256000)throw new Failure("SEARCH_OUTPUT_INVALID",false);
                var result=json.readTree(bytes);
                if(result==null||!result.path("results").isArray())throw new Failure("SEARCH_OUTPUT_INVALID",false);
                var sources=new ArrayList<Source>();
                for(var source:result.path("results")) {
                    if(sources.size()==5)break;
                    if(!source.path("title").isTextual()||!source.path("content").isTextual()||!source.path("url").isTextual())continue;
                    String url=source.path("url").asText();
                    if(!safePublicUrl(url))continue;
                    String snippet=clip(source.path("content").asText(),1500);
                    if(snippet.isBlank())continue;
                    sources.add(new Source(clip(source.path("title").asText(),240),url,snippet));
                }
                return new Result("tavily",sources);
            }
        } catch(Failure e) {throw e;}
        catch(HttpTimeoutException e) {throw new Failure("SEARCH_TIMEOUT",true);}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new Failure("SEARCH_INTERRUPTED",false);}
        catch(java.io.IOException e) {
            for(Throwable cause=e;cause!=null;cause=cause.getCause()) {
                if(cause instanceof Failure f)throw f;
                if(cause instanceof java.util.concurrent.TimeoutException||cause instanceof HttpTimeoutException)throw new Failure("SEARCH_TIMEOUT",true);
                if(cause instanceof com.fasterxml.jackson.core.JsonProcessingException)throw new Failure("SEARCH_OUTPUT_INVALID",false);
            }
            throw new Failure("SEARCH_CONNECTION_FAILED",true);
        }
        catch(Exception e) {throw new Failure("SEARCH_INVALID_REQUEST",false);}
    }
    /** Bounds both total body bytes and stalled-body wall time; cancellation closes the HTTP exchange. */
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private volatile java.util.concurrent.Flow.Subscription subscription;
        BoundedBody() {this(20000);}
        BoundedBody(long timeoutMs) {
            result.orTimeout(timeoutMs,java.util.concurrent.TimeUnit.MILLISECONDS).whenComplete((v,e)->{
                if(e!=null&&subscription!=null)subscription.cancel();
            });
        }
        public java.util.concurrent.CompletionStage<byte[]> getBody() {return result;}
        public void onSubscribe(java.util.concurrent.Flow.Subscription s) {subscription=s;if(result.isDone())s.cancel();else s.request(1);}
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(buffer.remaining()>256000-bytes.size()) {result.completeExceptionally(new Failure("SEARCH_OUTPUT_INVALID",false));return;}
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) {result.completeExceptionally(error);}
        public void onComplete() {result.complete(bytes.toByteArray());}
    }
}
