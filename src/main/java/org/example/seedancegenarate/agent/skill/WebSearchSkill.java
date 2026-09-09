package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class WebSearchSkill implements CreativeSkill {
    private final SearchProvider provider;
    private final ObjectMapper json;
    public WebSearchSkill(SearchProvider provider,ObjectMapper json) {this.provider=provider;this.json=json;}
    public boolean available() {return provider.available();}
    public SkillDescriptor descriptor() {
        var schema=json.createObjectNode().put("type","object").put("additionalProperties",false);
        schema.putArray("required").add("query");var fields=schema.putObject("properties");
        fields.putObject("query").put("type","string").put("minLength",1).put("maxLength",300)
                .put("description","仅公开资料关键词，不传完整对话、文件正文、个人联系方式或凭据；搜索摘要不是事实核验。");
        fields.putObject("domains").put("type","array").put("maxItems",5).putObject("items").put("type","string").put("maxLength",253);
        fields.putObject("timeRange").put("type","string").putArray("enum").add("day").add("week").add("month").add("year");
        return new SkillDescriptor("web-search","1.0.0","搜索公开文字资料，保存有来源的检索摘要；不读网页正文，不搜索媒体，不证明事实已核实。每计划最多3次请求（含重试）。",schema,"WEB_RESEARCH");
    }
    public void validate(JsonNode input) {
        if(input==null||!input.isObject())throw invalid();
        input.fieldNames().forEachRemaining(f->{if(!Set.of("query","domains","timeRange").contains(f))throw invalid();});
        var query=input.path("query");
        if(!query.isTextual()||query.asText().isBlank()||query.asText().length()>300)throw invalid();
        String q=query.asText();
        if(java.util.regex.Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)").matcher(q).find())throw invalid();
        if(q.matches("(?is).*(?:tvly-|sk-[a-z0-9]|bearer\\s|api[_ -]?key\\s*[:=]|password\\s*[:=]|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}).*"))throw invalid();
        if(input.has("timeRange")&&(!input.path("timeRange").isTextual()||!Set.of("day","week","month","year").contains(input.path("timeRange").asText())))throw invalid();
        if(input.has("domains")) {
            var domains=input.path("domains");if(!domains.isArray()||domains.size()>5)throw invalid();
            for(var domain:domains)if(!domain.isTextual()||domain.asText().length()>253
                    ||!domain.asText().matches("[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+")
                    ||!SearchProvider.safePublicUrl("https://"+domain.asText()))throw invalid();
        }
    }
    public SkillResult execute(AgentContext context,JsonNode input) {
        validate(input);var domains=new ArrayList<String>();input.path("domains").forEach(v->domains.add(v.asText()));
        var result=provider.search(new SearchProvider.Request(input.path("query").asText(),List.copyOf(domains),input.path("timeRange").asText(null)));
        String now=Instant.now().toString();
        var data=json.createObjectNode().put("schemaVersion",1).put("topic",input.path("query").asText()).put("retrievedAt",now);
        data.putArray("queries").add(input.path("query").asText());
        var sources=data.putArray("sources");var evidence=data.putArray("evidence");var seen=new HashSet<String>();
        if(result==null||result.sources()==null)throw new SearchProvider.Failure("SEARCH_OUTPUT_INVALID",false);
        for(var item:result.sources()) {
            if(sources.size()==5)break;
            if(item==null||!SearchProvider.safePublicUrl(item.url())||item.snippet()==null||item.snippet().isBlank()||!seen.add(item.url()))continue;
            String host=java.net.URI.create(item.url()).getHost().toLowerCase(Locale.ROOT);
            if(!domains.isEmpty()&&domains.stream().noneMatch(d->host.equalsIgnoreCase(d)||host.endsWith("."+d.toLowerCase(Locale.ROOT))))continue;
            String sourceId="s"+(sources.size()+1),snippet=SearchProvider.clip(item.snippet(),1500);
            sources.addObject().put("sourceId",sourceId).put("provider",result.provider()).put("query",input.path("query").asText())
                    .put("title",SearchProvider.clip(item.title()==null?"来源":item.title(),240)).put("url",item.url()).put("snippet",snippet)
                    .put("retrievedAt",now).putNull("publisher").putNull("publishedAt").put("sourceType","UNKNOWN");
            evidence.addObject().put("sourceId",sourceId).put("excerpt",snippet).put("status","SEARCH_RESULT");
        }
        data.put("status",sources.isEmpty()?"INSUFFICIENT_EVIDENCE":"SEARCH_RESULTS");
        data.putObject("coverage").put("sourceCount",sources.size()).put("verified",false);
        String content=sources.isEmpty()?"未找到符合限制的公开资料。证据不足，请补充来源或调整检索要求；不能据此编造事实。":
                "已保存 "+sources.size()+" 条公开搜索摘要与来源，尚未核验网页正文。引用时保留来源编号，不据此编造设备数量、荣誉或其他无证据事实。";
        return new SkillResult("WEB_RESEARCH","公开资料："+SearchProvider.clip(input.path("query").asText(),100),content,null,data,null);
    }
    private static BusinessException invalid() {return BusinessException.badRequest("搜索只接受有界公开关键词及有效域名/时间范围，不能包含凭据或联系方式");}
    public static void validateResult(SkillResult result) {
        var data=result.data();
        if(data==null||!data.isObject()||data.toString().length()>24000||data.path("schemaVersion").asInt()!=1
                ||!data.path("sources").isArray()||data.path("sources").size()>5||!data.path("evidence").isArray()
                ||data.path("sources").size()!=data.path("evidence").size())throw invalid();
        var snippets=new HashMap<String,String>();
        for(var source:data.path("sources")) {
            String id=source.path("sourceId").asText(),snippet=source.path("snippet").asText();
            if(id.isBlank()||snippets.putIfAbsent(id,snippet)!=null||snippet.isBlank()||snippet.length()>1500
                    ||!SearchProvider.safePublicUrl(source.path("url").asText())||!"UNKNOWN".equals(source.path("sourceType").asText()))throw invalid();
        }
        var evidenceIds=new HashSet<String>();
        for(var evidence:data.path("evidence")) {
            String id=evidence.path("sourceId").asText();
            if(!evidenceIds.add(id)||!Objects.equals(snippets.get(id),evidence.path("excerpt").asText())
                    ||!"SEARCH_RESULT".equals(evidence.path("status").asText()))throw invalid();
        }
        if(!Objects.equals(snippets.isEmpty()?"INSUFFICIENT_EVIDENCE":"SEARCH_RESULTS",data.path("status").asText())
                ||data.path("coverage").path("verified").asBoolean(true))throw invalid();
    }
}
