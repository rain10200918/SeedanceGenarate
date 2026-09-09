package org.example.seedancegenarate.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Model protocol adapter only: no Tools, routing, retries, memory or business execution. */
@Slf4j
@Component
public class LangChain4jPlannerClient {
    private final ObjectMapper json;
    private final HttpClient http;
    private final LlmChatClient errors;

    @Autowired
    public LangChain4jPlannerClient(PromptOptimizeConfig config,ObjectMapper json,LlmChatClient errors) {
        this(json,HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()==null?5000:config.getConnectTimeoutMs())).build(),errors);
    }
    LangChain4jPlannerClient(ObjectMapper json,HttpClient http,LlmChatClient errors) {
        this.json=json;this.http=http;this.errors=errors;
    }

    /** Same first three arguments as the legacy client so token accounting retains the durable identity. */
    public LlmChatResponse chat(LlmChannelSpec channel,List<Map<String,Object>> messages,LlmCallMeta meta,JsonNode schema) {
        long started=System.nanoTime();var transport=new Transport(channel);
        int inputChars=LlmChatClient.inputTextChars(messages);
        log.info("Planner structured request turn={}, step={}, channel={}, model={}, timeoutMs={}, outputLimit={}, tokenParam={}, inputChars={}, schemaChars={}",
                meta.agentTurnId(),meta.decisionStep(),channel.name(),channel.model(),channel.timeoutMs(),channel.maxTokens(),channel.tokenParam(),inputChars,schema.toString().length());
        try {
            var builder=OpenAiChatModel.builder().baseUrl("http://planner.invalid/").apiKey(channel.apiKey())
                    .modelName(channel.model()).temperature(channel.temperature()).maxRetries(0)
                    .strictJsonSchema(true).logRequests(false).logResponses(false)
                    .customParameters(Map.of("stream",false))
                    .timeout(Duration.ofMillis(channel.timeoutMs())).httpClientBuilder(transport);
            var tokenParam=channel.tokenParam()==null?LlmChannelSpec.TokenParam.MAX_TOKENS:channel.tokenParam();
            if(tokenParam==LlmChannelSpec.TokenParam.MAX_TOKENS)builder.maxTokens(channel.maxTokens());
            if(tokenParam==LlmChannelSpec.TokenParam.MAX_COMPLETION_TOKENS)builder.maxCompletionTokens(channel.maxTokens());
            List<ChatMessage> input=messages.stream().map(m->{
                return switch(String.valueOf(m.get("role"))) {
                    case "system" -> (ChatMessage) SystemMessage.from((String)m.get("content"));
                    case "user" -> userMessage(m.get("content"));
                    default -> throw new IllegalArgumentException("Planner message role not allowed");
                };
            }).toList();
            var format=ResponseFormat.builder().type(ResponseFormatType.JSON).jsonSchema(JsonSchema.builder()
                    .name("agent_decision").rootElement(JsonRawSchema.from(schema.toString())).build()).build();
            var response=builder.build().chat(ChatRequest.builder().messages(input).responseFormat(format).build());
            String content=response.aiMessage().text();
            if(content==null || content.isBlank())throw outputError("MODEL_OUTPUT_INVALID",transport.promptTokens,transport.completionTokens);
            log.info("Planner structured response turn={}, step={}, channel={}, model={}, httpStatus={}, latencyMs={}, finishReason={}, contentChars={}, promptTokens={}, completionTokens={}",
                    meta.agentTurnId(),meta.decisionStep(),channel.name(),channel.model(),transport.status,
                    (System.nanoTime()-started)/1000000,transport.finishReason,content.length(),transport.promptTokens,transport.completionTokens);
            return new LlmChatResponse(transport.content,transport.promptTokens,transport.completionTokens);
        } catch(Exception error) {
            // SDK exceptions may contain the request/response or headers. Never retain that cause.
            LlmChannelException safe=transport.failure!=null?transport.failure:error instanceof LlmChannelException e?e:
                    LlmChannelException.terminal("Planner adapter "+error.getClass().getSimpleName(),null)
                            .classified(transport.status==null?"MODEL_INVALID_REQUEST":"MODEL_OUTPUT_INVALID",false,transport.status,null,transport.promptTokens,transport.completionTokens);
            log.warn("Planner structured failure turn={}, step={}, channel={}, model={}, code={}, retryable={}, httpStatus={}, providerCode={}, kind={}",
                    meta.agentTurnId(),meta.decisionStep(),channel.name(),channel.model(),safe.code(),safe.retryable(),safe.httpStatus(),safe.providerCode(),error.getClass().getSimpleName());
            throw safe;
        }
    }

    /** Only Gateway-created text/image parts; never tools, arbitrary roles, or executable callbacks. */
    private static UserMessage userMessage(Object value) {
        if(value instanceof String text)return UserMessage.from(text);
        if(!(value instanceof List<?> parts) || parts.isEmpty() || parts.size()>5)
            throw new IllegalArgumentException("Planner content must be bounded text and images");
        var contents=new java.util.ArrayList<Content>();int images=0;
        for(var raw:parts) {
            if(!(raw instanceof Map<?,?> part))throw new IllegalArgumentException("Invalid content part");
            if("text".equals(part.get("type")) && part.get("text") instanceof String text)contents.add(TextContent.from(text));
            else if("image_url".equals(part.get("type")) && part.get("image_url") instanceof Map<?,?> image
                    && image.get("url") instanceof String url && ++images<=4)contents.add(ImageContent.from(url));
            else throw new IllegalArgumentException("Unsupported Planner content part");
        }
        return UserMessage.from(contents);
    }

    private static LlmChannelException outputError(String code,Integer input,Integer output) {
        return LlmChannelException.terminal("Planner response rejected",null).classified(code,false,200,null,input,output);
    }

    /** Preserve legacy full endpoints and JDK transport semantics; SDK alone builds the model payload. */
    private final class Transport implements HttpClientBuilder,dev.langchain4j.http.client.HttpClient {
        private final LlmChannelSpec channel;
        private LlmChannelException failure;
        private Integer status,promptTokens,completionTokens;
        private String finishReason,content;
        private Transport(LlmChannelSpec channel) {this.channel=channel;}
        public Duration connectTimeout(){return http.connectTimeout().orElse(Duration.ofSeconds(5));}
        public HttpClientBuilder connectTimeout(Duration ignored){return this;}
        public Duration readTimeout(){return Duration.ofMillis(channel.timeoutMs());}
        public HttpClientBuilder readTimeout(Duration ignored){return this;}
        public dev.langchain4j.http.client.HttpClient build(){return this;}
        public void execute(dev.langchain4j.http.client.HttpRequest request,ServerSentEventParser parser,ServerSentEventListener listener) {
            throw new UnsupportedOperationException("Planner streaming is disabled");
        }
        public SuccessfulHttpResponse execute(dev.langchain4j.http.client.HttpRequest request) {
            try {
                var sent=HttpRequest.newBuilder(URI.create(channel.baseUrl())).timeout(readTimeout())
                        .header("Authorization","Bearer "+channel.apiKey()).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(request.body())).build();
                var received=http.send(sent,HttpResponse.BodyHandlers.ofString());status=received.statusCode();
                if(status<200 || status>=300) {
                    failure=errors.httpFailure(status,received.body(),"Planner HTTP "+status);
                    if((status==400 || status==422) && schemaRejected(received.body()))
                        failure.classified("MODEL_SCHEMA_UNSUPPORTED",false,status,null,null,null);
                    throw failure;
                }
                validateResponse(received.body());
                return SuccessfulHttpResponse.builder().statusCode(status).headers(received.headers().map()).body(received.body()).build();
            } catch(LlmChannelException e) {failure=e;throw e;}
            catch(Exception e) {
                var classified=LlmChatClient.classify(e);
                failure=LlmChannelException.terminal(classified.reason(),null).classified(classified.code(),classified.retryable(),status,classified.providerCode(),promptTokens,completionTokens);
                throw failure;
            }
        }
        private void validateResponse(String body) {
            JsonNode root;
            try {root=json.readTree(body);}catch(Exception e){throw outputError("MODEL_OUTPUT_INVALID",null,null);}
            if(root==null)throw outputError("MODEL_OUTPUT_INVALID",null,null);
            promptTokens=count(root.path("usage").path("prompt_tokens"));
            completionTokens=count(root.path("usage").path("completion_tokens"));
            var choice=root.path("choices").path(0);String finish=choice.path("finish_reason").asText();
            finishReason=switch(finish){case "stop","length","tool_calls","content_filter" -> finish;default -> "unknown";};
            if(finish.equals("length"))throw outputError("MODEL_OUTPUT_TRUNCATED",promptTokens,completionTokens);
            var content=choice.path("message").path("content");
            if(root.path("choices").size()!=1 || !content.isTextual() || content.asText().isBlank()
                    || content.asText().length()>24000 || finish.equals("tool_calls") || finish.equals("content_filter")
                    || choice.path("message").hasNonNull("tool_calls")
                    || choice.path("message").hasNonNull("function_call"))
                throw outputError("MODEL_OUTPUT_INVALID",promptTokens,completionTokens);
            this.content=content.textValue();
        }
        private Integer count(JsonNode n){return n.isIntegralNumber() && n.canConvertToInt() && n.intValue()>=0?n.intValue():null;}
        private boolean schemaRejected(String body) {
            try {
                var error=json.readTree(body).path("error");
                String param=error.path("param").asText("");String code=error.path("code").asText("");
                return param.equals("response_format") || param.startsWith("response_format.")
                        || code.equals("unsupported_response_format") || code.equals("unsupported_json_schema");
            }catch(Exception ignored){return false;}
        }
    }
}
